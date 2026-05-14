package net.wanners.groceries

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AddRequest(val text: String = "")

@Serializable
data class PatchRequest(val text: String? = null, val done: Boolean? = null)

@Serializable
data class ReorderRequest(val ids: List<String> = emptyList())

@Serializable
sealed interface ApiEvent {
    @Serializable
    @SerialName("added")
    data class Added(val item: Item) : ApiEvent

    @Serializable
    @SerialName("updated")
    data class Updated(val item: Item) : ApiEvent

    @Serializable
    @SerialName("removed")
    data class Removed(val id: String) : ApiEvent

    @Serializable
    @SerialName("reordered")
    data class Reordered(val ids: List<String>) : ApiEvent
}

private val apiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

private fun Change.toApi(): ApiEvent = when (this) {
    is Change.Added -> ApiEvent.Added(item)
    is Change.Updated -> ApiEvent.Updated(item)
    is Change.Removed -> ApiEvent.Removed(id)
    is Change.Reordered -> ApiEvent.Reordered(ids)
}

fun Application.groceriesModule(store: Store, allowedSubnets: List<Subnet> = emptyList()) {
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        if (!isAllowedRemote(call.request.local.remoteHost, allowedSubnets)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_local"))
            finish()
        }
    }

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(CallLogging)
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(io.ktor.http.HttpMethod.Patch)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request"))
        }
        exception<NotFoundException> { call, _ ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad_request")))
        }
        exception<kotlinx.serialization.SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed_json"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("unhandled", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal"))
        }
    }

    routing {
        get("/api/items") {
            call.respond(store.snapshot())
        }

        post("/api/items") {
            val body = call.receive<AddRequest>()
            if (body.text.isBlank()) throw IllegalArgumentException("text required")
            if (body.text.length > 1024) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "text_too_long"))
                return@post
            }
            val item = store.add(body.text)
            call.respond(item)
        }

        patch("/api/items/{id}") {
            val id = call.parameters["id"] ?: throw NotFoundException()
            val body = call.receive<PatchRequest>()
            val updated = store.update(id, text = body.text, done = body.done)
                ?: throw NotFoundException()
            call.respond(updated)
        }

        delete("/api/items/{id}") {
            val id = call.parameters["id"] ?: throw NotFoundException()
            if (!store.remove(id)) throw NotFoundException()
            call.respond(HttpStatusCode.NoContent)
        }

        post("/api/clear-done") {
            val ids = store.removeDone()
            call.respond(mapOf("removed" to ids.size))
        }

        post("/api/clear-all") {
            val ids = store.clear()
            call.respond(mapOf("removed" to ids.size))
        }

        post("/api/reorder") {
            val body = call.receive<ReorderRequest>()
            val ok = store.reorder(body.ids)
            if (!ok) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ids must match current set"))
            } else {
                call.respond(mapOf("ok" to true))
            }
        }

        post("/api/undo") {
            val restored = store.undo()
            call.respond(mapOf("restored" to restored.size))
        }

        get("/api/events") {
            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                writeStringUtf8(": connected\n\n")
                flush()
                try {
                    store.changes.collect { change ->
                        val payload = apiJson.encodeToString(
                            ApiEvent.serializer(),
                            change.toApi(),
                        )
                        writeStringUtf8("event: change\ndata: $payload\n\n")
                        flush()
                    }
                } catch (_: CancellationException) {
                    // client disconnected; writer close is handled by Ktor
                }
            }
        }

        staticResources("/", "web") {
            default("index.html")
        }
    }
}
