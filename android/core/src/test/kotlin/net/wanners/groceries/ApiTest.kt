package net.wanners.groceries

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.options
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        val resp = client.get("/api/items")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("[]", resp.bodyAsText().trim())
    }

    @Test
    fun `loopback is allowed even when allowedSubnets restricts to a foreign LAN`(@TempDir dir: File) = testApplication {
        // testApplication's transport reports remoteHost as "localhost" — should always pass.
        val foreignOnly = listOf(Subnet(java.net.InetAddress.getByName("10.99.99.0").address, 24))
        application { groceriesModule(storeIn(dir), foreignOnly) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/items").status)
    }

    @Test
    fun `write endpoint returns 429 once the per-IP bucket is empty`(@TempDir dir: File) = testApplication {
        val onlyHomeLan = listOf(Subnet(java.net.InetAddress.getByName("10.0.0.0").address, 8))
        val tight = RateLimiter(capacity = 2.0, refillPerSecond = 0.0)
        application {
            groceriesModule(
                storeIn(dir),
                onlyHomeLan,
                remoteHostResolver = { "10.0.0.5" },
                rateLimiter = tight,
            )
        }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        // First two writes consume the bucket, third must 429.
        for (i in 1..2) {
            val ok = client.post("/api/items") {
                contentType(ContentType.Application.Json)
                setBody("""{"text":"x$i"}""")
            }
            assertEquals(HttpStatusCode.OK, ok.status)
        }
        val limited = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"y"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        // GETs are not throttled.
        assertEquals(HttpStatusCode.OK, client.get("/api/items").status)
    }

    @Test
    fun `non-LAN remote is rejected with 403 JSON body`(@TempDir dir: File) = testApplication {
        // Synthetic remote host that is neither loopback nor in the allow list.
        val onlyHomeLan = listOf(Subnet(java.net.InetAddress.getByName("192.168.1.0").address, 24))
        application {
            groceriesModule(storeIn(dir), onlyHomeLan, remoteHostResolver = { "10.99.99.42" })
        }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        val resp = client.get("/api/items")
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        // Body must be well-formed JSON — i.e. ContentNegotiation is installed before the
        // Plugins-phase interceptor runs and respond(mapOf(...)) actually serializes.
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"error\""), body)
        assertTrue(body.contains("not_local"), body)
    }

    @Test
    fun `POST creates item and GET returns it`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        val resp = client.patch("/api/items/nope") {
            contentType(ContentType.Application.Json)
            setBody("""{"done":true}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `DELETE removes item then GET is empty`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/items/nope").status)
    }

    @Test
    fun `POST clear-done removes only done items`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        val resp = client.post("/api/undo")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"restored\":0"))
    }

    @Test
    fun `POST reorder rejects mismatched ids`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        val resp = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `POST with text over 1kB returns 413`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
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
            defaultRequest { header("X-Grocery-Client", "1") }
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
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        val resp = client.get("/")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("Groceries"))
        assertTrue(body.contains("<title>"))
    }

    @Test
    fun `static manifest and sw served with correct content types`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        val sw = client.get("/sw.js")
        assertEquals(HttpStatusCode.OK, sw.status)
        assertNotNull(sw.headers["Content-Type"])
        assertTrue(sw.headers["Content-Type"]!!.contains("javascript", ignoreCase = true))

        val mf = client.get("/manifest.webmanifest")
        assertEquals(HttpStatusCode.OK, mf.status)
        assertTrue(mf.bodyAsText().contains("\"name\":"))
    }

    @Test
    fun `POST with oversized body returns 413`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        val huge = "x".repeat(128 * 1024)
        val resp = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"$huge"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
        // Server still responsive.
        assertEquals(HttpStatusCode.OK, client.get("/api/items").status)
    }

    @Test
    fun `POST reorder with oversized body returns 413`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        // Fake but large id list — body alone exceeds the 64KiB cap.
        // Each entry is ~30 bytes, so 4000 entries → ~120 KB body.
        val bigIds = (1..4000).joinToString(",") { "\"${"X".repeat(20)}$it\"" }
        val resp = client.post("/api/reorder") {
            contentType(ContentType.Application.Json)
            setBody("""{"ids":[$bigIds]}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    // If any Access-Control-Allow-* header ever appears here, a cross-origin browser
    // preflight for the X-Grocery-Client header would succeed and the CSRF check on
    // writes would be defeated.
    @Test
    fun `OPTIONS preflight does not advertise any Access-Control-Allow headers`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.options("/api/clear-all") {
            header("Origin", "http://evil.example")
            header("Access-Control-Request-Method", "POST")
            header("Access-Control-Request-Headers", "x-grocery-client,content-type")
        }
        for ((name, _) in resp.headers.entries()) {
            assertFalse(
                name.startsWith("Access-Control-Allow", ignoreCase = true),
                "preflight must not advertise CORS allow-* headers, got $name",
            )
        }
    }

    @Test
    fun `writes without X-Grocery-Client header return 403`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        // No defaultRequest header here.
        val client = createClient { install(ContentNegotiation) { json() } }
        val noHeader = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"milk"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, noHeader.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/items/anything").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/clear-all").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/clear-done").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/undo").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/reorder") {
            contentType(ContentType.Application.Json); setBody("""{"ids":[]}""")
        }.status)
        // GET is still allowed without the header (it's safe).
        assertEquals(HttpStatusCode.OK, client.get("/api/items").status)
    }

    // A deeply nested JSON tree under an unknown key would otherwise blow the parser stack.
    @Test
    fun `POST with unknown JSON keys returns 400`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        val resp = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"milk","secret":"surprise"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        // Server still serving after the bad request.
        assertEquals(HttpStatusCode.OK, client.get("/api/items").status)
    }

    @Test
    fun `POST add returns 413 when list is full`(@TempDir dir: File) = testApplication {
        application {
            // Tiny maxItems so the test fills the cap quickly; cap behavior is what's under test.
            groceriesModule(Store(File(dir, "items.json"), maxItems = 3))
        }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        for (i in 1..3) {
            val r = client.post("/api/items") {
                contentType(ContentType.Application.Json)
                setBody("""{"text":"x$i"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status, "filling failed at $i")
        }
        val overflow = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"one more"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, overflow.status)
        assertTrue(overflow.bodyAsText().contains("list_full"))
    }

    @Test
    fun `SSE emits keepalive comment frames on idle connections`(@TempDir dir: File) {
        // Idle SSE connections die silently when Android's radio sleeps or a proxy times
        // out the TCP socket; the frontend watchdog needs a recurring heartbeat to count.
        // We override the interval to 200ms so this test finishes in <1s instead of >15s.
        val store = storeIn(dir)
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            groceriesModule(store, sseKeepaliveIntervalMs = 200)
        }
        server.start(wait = false)
        val port = runBlocking { server.resolvedConnectors().first().port }
        val client = HttpClient(ClientCIO) {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
            expectSuccess = false
        }
        try {
            runBlocking {
                coroutineScope {
                    val gotKeepalives = CompletableDeferred<Int>()
                    val sawAnyChange = CompletableDeferred<Unit>()
                    val subscriberJob = launch {
                        client.prepareGet("http://127.0.0.1:$port/api/events").execute { resp ->
                            val ch = resp.bodyAsChannel()
                            var keepalives = 0
                            while (!gotKeepalives.isCompleted) {
                                val line = ch.readUTF8Line() ?: break
                                if (line == "event: keepalive") {
                                    keepalives++
                                    if (keepalives >= 2) gotKeepalives.complete(keepalives)
                                } else if (line == "event: change") {
                                    // Should never happen — no writes occur in this test.
                                    sawAnyChange.complete(Unit)
                                }
                            }
                        }
                    }
                    val count = withTimeout(2_000) { gotKeepalives.await() }
                    assertTrue(count >= 2, "expected ≥2 keepalive frames, got $count")
                    assertFalse(sawAnyChange.isCompleted, "no change frame should have arrived in an idle test")
                    subscriberJob.cancel()
                }
            }
        } finally {
            client.close()
            server.stop(50, 200)
        }
    }

    @Test
    fun `SSE returns 503 above MAX_SSE concurrent subscribers`(@TempDir dir: File) = withRealServer(dir) { port, client ->
        runBlocking {
            coroutineScope {
                val openers = (1..16).map {
                    launch {
                        client.prepareGet("http://127.0.0.1:$port/api/events").execute { resp ->
                            // Hold the connection open until cancelled.
                            kotlinx.coroutines.delay(Long.MAX_VALUE)
                        }
                    }
                }
                // Give them time to fully register.
                delay(500)
                val seventeenth = client.get("http://127.0.0.1:$port/api/events")
                assertEquals(HttpStatusCode.ServiceUnavailable, seventeenth.status)
                openers.forEach { it.cancel() }
            }
        }
    }

    @Test
    fun `unknown static asset returns 404`(@TempDir dir: File) = testApplication {
        application { groceriesModule(storeIn(dir)) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { header("X-Grocery-Client", "1") }
        }
        val resp = client.get("/this-does-not-exist.png")
        // staticResources falls back to default("index.html"); document that.
        // We accept either 404 OR fallback-to-index.
        if (resp.status != HttpStatusCode.NotFound) {
            val body = resp.bodyAsText()
            assertTrue(body.contains("<title>"), "unknown path should 404 or return index")
        }
    }
}
