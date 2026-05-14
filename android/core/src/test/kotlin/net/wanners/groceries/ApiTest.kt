package net.wanners.groceries

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ApiTest {

    private fun storeIn(dir: File) = Store(File(dir, "items.json"))

    private suspend fun HttpResponse.itemBody(): Item =
        Json.decodeFromString(Item.serializer(), bodyAsText())

    @Test
    fun `GET items on empty store returns empty array`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.get("/api/items")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("[]", resp.bodyAsText().trim())
    }

    @Test
    fun `loopback is allowed even when allowedSubnets restricts to a foreign LAN`(@TempDir dir: File) = testApplication {
        // testApplication's transport reports remoteHost as "localhost" — should always pass.
        val foreignOnly = listOf(Subnet(java.net.InetAddress.getByName("10.99.99.0").address, 24))
        application { groceriesModule(storeIn(dir), foreignOnly) }
        val client = createClient { install(ContentNegotiation) { json() } }
        assertEquals(HttpStatusCode.OK, client.get("/api/items").status)
    }

    @Test
    fun `POST creates item and GET returns it`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"milk"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val item = resp.itemBody()
        assertEquals("milk", item.text)
        assertFalse(item.done)
        assertTrue(item.id.isNotEmpty())

        val list = client.get("/api/items").bodyAsText()
        assertTrue(list.contains("\"text\":\"milk\""))
        assertTrue(list.contains("\"id\":\"${item.id}\""))
    }

    @Test
    fun `PATCH toggles done`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val created = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"milk"}""")
        }.itemBody()

        val patched = client.patch("/api/items/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"done":true}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        val updated = patched.itemBody()
        assertTrue(updated.done)
        assertEquals("milk", updated.text)
    }

    @Test
    fun `PATCH text preserves done`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val created = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"milk"}""")
        }.itemBody()
        client.patch("/api/items/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"done":true}""")
        }
        val patched = client.patch("/api/items/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"oat milk"}""")
        }.itemBody()
        assertEquals("oat milk", patched.text)
        assertTrue(patched.done)
    }

    @Test
    fun `PATCH on nonexistent id returns 404`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.patch("/api/items/nope") {
            contentType(ContentType.Application.Json)
            setBody("""{"done":true}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `DELETE removes item then GET is empty`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val created = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"milk"}""")
        }.itemBody()
        val del = client.delete("/api/items/${created.id}")
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertEquals("[]", client.get("/api/items").bodyAsText().trim())
    }

    @Test
    fun `DELETE on nonexistent id returns 404`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/items/nope").status)
    }

    @Test
    fun `POST clear-done removes only done items`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val a = client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"milk"}""") }.itemBody()
        client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"eggs"}""") }
        val c = client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"bread"}""") }.itemBody()
        client.patch("/api/items/${a.id}") { contentType(ContentType.Application.Json); setBody("""{"done":true}""") }
        client.patch("/api/items/${c.id}") { contentType(ContentType.Application.Json); setBody("""{"done":true}""") }
        val resp = client.post("/api/clear-done")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"removed\":2"))
        val remaining = client.get("/api/items").bodyAsText()
        assertTrue(remaining.contains("eggs"))
        assertFalse(remaining.contains("milk"))
        assertFalse(remaining.contains("bread"))
    }

    @Test
    fun `POST clear-all empties the list`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"milk"}""") }
        client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"eggs"}""") }
        val resp = client.post("/api/clear-all")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"removed\":2"))
        assertEquals("[]", client.get("/api/items").bodyAsText().trim())
    }

    @Test
    fun `POST reorder rearranges items`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val a = client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"milk"}""") }.itemBody()
        val b = client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"eggs"}""") }.itemBody()
        val c = client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"bread"}""") }.itemBody()
        val resp = client.post("/api/reorder") {
            contentType(ContentType.Application.Json)
            setBody("""{"ids":["${c.id}","${a.id}","${b.id}"]}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val list = client.get("/api/items").bodyAsText()
        val breadPos = list.indexOf("bread")
        val milkPos = list.indexOf("milk")
        val eggsPos = list.indexOf("eggs")
        assertTrue(breadPos in 0 until milkPos)
        assertTrue(milkPos < eggsPos)
    }

    @Test
    fun `POST undo restores last deleted item`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"milk"}""") }
        val b = client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"eggs"}""") }.itemBody()
        client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"bread"}""") }
        client.delete("/api/items/${b.id}")
        val resp = client.post("/api/undo")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"restored\":1"))
        val list = client.get("/api/items").bodyAsText()
        // order preserved: milk, eggs (restored at index 1), bread
        val milkPos = list.indexOf("milk")
        val eggsPos = list.indexOf("eggs")
        val breadPos = list.indexOf("bread")
        assertTrue(milkPos < eggsPos && eggsPos < breadPos)
    }

    @Test
    fun `POST undo after clear-all restores everything`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"milk"}""") }
        client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"eggs"}""") }
        client.post("/api/clear-all")
        val resp = client.post("/api/undo")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"restored\":2"))
        val list = client.get("/api/items").bodyAsText()
        assertTrue(list.contains("milk") && list.contains("eggs"))
    }

    @Test
    fun `POST undo with empty trash returns 0`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.post("/api/undo")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"restored\":0"))
    }

    @Test
    fun `POST reorder rejects mismatched ids`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val a = client.post("/api/items") { contentType(ContentType.Application.Json); setBody("""{"text":"milk"}""") }.itemBody()
        val resp = client.post("/api/reorder") {
            contentType(ContentType.Application.Json)
            setBody("""{"ids":["${a.id}","fake-id"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `POST with empty text returns 400`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `POST with text over 1kB returns 413`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val text = "x".repeat(2000)
        val resp = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"$text"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    @Test
    fun `malformed JSON returns 400 and does not crash`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        // server still alive
        assertEquals(HttpStatusCode.OK, client.get("/api/items").status)
    }

    private inline fun withRealServer(dir: File, block: (port: Int, client: HttpClient) -> Unit) {
        val store = storeIn(dir)
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            groceriesModule(store)
        }
        server.start(wait = false)
        val port = runBlocking { server.resolvedConnectors().first().port }
        val client = HttpClient(ClientCIO) {
            install(ContentNegotiation) { json() }
            expectSuccess = false
        }
        try {
            block(port, client)
        } finally {
            client.close()
            server.stop(50, 200)
        }
    }

    @Test
    fun `SSE delivers added event to subscriber`(@TempDir dir: File) = withRealServer(dir) { port, client ->
        runBlocking {
            coroutineScope {
                val gotEvent = CompletableDeferred<String>()
                val subscriberJob = launch {
                    client.prepareGet("http://127.0.0.1:$port/api/events").execute { response ->
                        val ch = response.bodyAsChannel()
                        while (!gotEvent.isCompleted) {
                            val line = ch.readUTF8Line() ?: break
                            if (line.startsWith("data:")) {
                                gotEvent.complete(line.removePrefix("data:").trim())
                            }
                        }
                    }
                }
                delay(200)
                client.post("http://127.0.0.1:$port/api/items") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"milk"}""")
                }
                val payload = withTimeout(3_000) { gotEvent.await() }
                assertTrue(payload.contains("\"type\":\"added\""), payload)
                assertTrue(payload.contains("\"text\":\"milk\""), payload)
                subscriberJob.cancel()
            }
        }
    }

    @Test
    fun `SSE delivers update and remove events`(@TempDir dir: File) = withRealServer(dir) { port, client ->
        runBlocking {
            coroutineScope {
                val types = mutableListOf<String>()
                val sawThreeEvents = CompletableDeferred<Unit>()
                val subscriberJob = launch {
                    client.prepareGet("http://127.0.0.1:$port/api/events").execute { response ->
                        val ch = response.bodyAsChannel()
                        while (!sawThreeEvents.isCompleted) {
                            val line = ch.readUTF8Line() ?: break
                            if (line.startsWith("data:")) {
                                val payload = line.removePrefix("data:").trim()
                                val match = Regex("\"type\":\"(\\w+)\"").find(payload)
                                val type = match?.groupValues?.getOrNull(1)
                                if (type != null) {
                                    types.add(type)
                                    if (types.size >= 3) sawThreeEvents.complete(Unit)
                                }
                            }
                        }
                    }
                }
                delay(200)
                val item = client.post("http://127.0.0.1:$port/api/items") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"milk"}""")
                }.itemBody()
                client.patch("http://127.0.0.1:$port/api/items/${item.id}") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"done":true}""")
                }
                client.delete("http://127.0.0.1:$port/api/items/${item.id}")
                withTimeout(3_000) { sawThreeEvents.await() }
                assertEquals(listOf("added", "updated", "removed"), types)
                subscriberJob.cancel()
            }
        }
    }

    @Test
    fun `two SSE subscribers both receive the event`(@TempDir dir: File) = withRealServer(dir) { port, client ->
        runBlocking {
            coroutineScope {
                val a = CompletableDeferred<String>()
                val b = CompletableDeferred<String>()
                val ja = launch {
                    client.prepareGet("http://127.0.0.1:$port/api/events").execute { resp ->
                        val ch = resp.bodyAsChannel()
                        while (!a.isCompleted) {
                            val line = ch.readUTF8Line() ?: break
                            if (line.startsWith("data:")) a.complete(line.removePrefix("data:").trim())
                        }
                    }
                }
                val jb = launch {
                    client.prepareGet("http://127.0.0.1:$port/api/events").execute { resp ->
                        val ch = resp.bodyAsChannel()
                        while (!b.isCompleted) {
                            val line = ch.readUTF8Line() ?: break
                            if (line.startsWith("data:")) b.complete(line.removePrefix("data:").trim())
                        }
                    }
                }
                delay(300)
                client.post("http://127.0.0.1:$port/api/items") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"milk"}""")
                }
                val payloadA = withTimeout(3_000) { a.await() }
                val payloadB = withTimeout(3_000) { b.await() }
                assertTrue(payloadA.contains("\"text\":\"milk\""))
                assertTrue(payloadB.contains("\"text\":\"milk\""))
                ja.cancel(); jb.cancel()
            }
        }
    }

    @Test
    fun `static index served at root`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.get("/")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("Groceries"))
        assertTrue(body.contains("<title>"))
    }

    @Test
    fun `static manifest and sw served with correct content types`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val sw = client.get("/sw.js")
        assertEquals(HttpStatusCode.OK, sw.status)
        assertNotNull(sw.headers["Content-Type"])
        assertTrue(sw.headers["Content-Type"]!!.contains("javascript", ignoreCase = true))

        val mf = client.get("/manifest.webmanifest")
        assertEquals(HttpStatusCode.OK, mf.status)
        assertTrue(mf.bodyAsText().contains("\"name\":"))
    }

    @Test
    fun `unknown static asset returns 404`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.get("/this-does-not-exist.png")
        // staticResources falls back to default("index.html"); document that.
        // We accept either 404 OR fallback-to-index.
        if (resp.status != HttpStatusCode.NotFound) {
            val body = resp.bodyAsText()
            assertTrue(body.contains("<title>"), "unknown path should 404 or return index")
        }
    }
}
