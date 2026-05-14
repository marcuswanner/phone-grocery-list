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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
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

private const val MAX_BODY_BYTES = 64L * 1024L
private const val CSRF_HEADER = "X-Grocery-Client"
private const val CSRF_VALUE = "1"
private const val MAX_SSE = 16
private val sseCount = java.util.concurrent.atomic.AtomicInteger(0)

private suspend fun io.ktor.server.application.ApplicationCall.rejectIfBodyTooLarge(): Boolean {
    val len = request.headers["Content-Length"]?.toLongOrNull()
    if (len != null && len > MAX_BODY_BYTES) {
        respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "body_too_large"))
        return true
    }
    return false
}

// A custom-header check is enough on its own: a custom header is a non-simple
// request, so a cross-origin browser would need a preflight, and with CORS
// uninstalled the preflight fails. Same-origin PWA fetches set this header explicitly.
private suspend fun io.ktor.server.application.ApplicationCall.rejectIfNoCsrfHeader(): Boolean {
    if (request.headers[CSRF_HEADER] != CSRF_VALUE) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "missing_client_header"))
        return true
    }
    return false
}

private fun Change.toApi(): ApiEvent = when (this) {
    is Change.Added -> ApiEvent.Added(item)
    is Change.Updated -> ApiEvent.Updated(item)
    is Change.Removed -> ApiEvent.Removed(id)
    is Change.Reordered -> ApiEvent.Reordered(ids)
}

fun Application.groceriesModule(
    store: Store,
    allowedSubnets: List<Subnet> = emptyList(),
    // Overridable so callers can resolve the remote host from elsewhere — e.g. testApplication
    // always reports loopback, so tests need to inject a non-loopback host to cover the
    // foreign-IP rejection path.
    remoteHostResolver: (io.ktor.server.application.ApplicationCall) -> String? = { it.request.local.remoteHost },
    rateLimiter: RateLimiter = RateLimiter(),
) {
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        val host = remoteHostResolver(call)
        if (!isAllowedRemote(host, allowedSubnets)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_local"))
            finish()
            return@intercept
        }
        // Throttle only state-changing methods. GETs (including /api/items, /api/events
        // and the static PWA assets) stay unconstrained so a slow tab repaint can't 429.
        val method = call.request.httpMethod
        val isWrite = method == io.ktor.http.HttpMethod.Post ||
            method == io.ktor.http.HttpMethod.Patch ||
            method == io.ktor.http.HttpMethod.Delete
        if (isWrite && !rateLimiter.allow(host)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
            finish()
        }
    }

    install(ContentNegotiation) {
        // Reject unknown keys on request bodies. Our request shapes are flat (AddRequest,
        // PatchRequest, ReorderRequest) so deeply nested unknown JSON would otherwise let a
        // 64 KiB body nest 60k levels deep and StackOverflow the parser.
        json(Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        })
    }
    install(CallLogging)
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
            if (call.rejectIfNoCsrfHeader()) return@post
            if (call.rejectIfBodyTooLarge()) return@post
            val body = call.receive<AddRequest>()
            if (body.text.isBlank()) throw IllegalArgumentException("text required")
            if (body.text.length > 1024) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "text_too_long"))
                return@post
            }
            val item = try {
                store.add(body.text)
            } catch (e: IllegalArgumentException) {
                if (e.message == "list_full") {
                    call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "list_full"))
                    return@post
                }
                throw e
            }
            call.respond(item)
        }

        patch("/api/items/{id}") {
            if (call.rejectIfNoCsrfHeader()) return@patch
            if (call.rejectIfBodyTooLarge()) return@patch
            val id = call.parameters["id"] ?: throw NotFoundException()
            val body = call.receive<PatchRequest>()
            val updated = store.update(id, text = body.text, done = body.done)
                ?: throw NotFoundException()
            call.respond(updated)
        }

        delete("/api/items/{id}") {
            if (call.rejectIfNoCsrfHeader()) return@delete
            val id = call.parameters["id"] ?: throw NotFoundException()
            if (!store.remove(id)) throw NotFoundException()
            call.respond(HttpStatusCode.NoContent)
        }

        post("/api/clear-done") {
            if (call.rejectIfNoCsrfHeader()) return@post
            val ids = store.removeDone()
            call.respond(mapOf("removed" to ids.size))
        }

        post("/api/clear-all") {
            if (call.rejectIfNoCsrfHeader()) return@post
            val ids = store.clear()
            call.respond(mapOf("removed" to ids.size))
        }

        post("/api/reorder") {
            if (call.rejectIfNoCsrfHeader()) return@post
            // Body size cap is the real bound here (~64 KiB → ~5k base62 ids, matches the
            // store's item cap). reorder() itself rejects mismatched id sets.
            if (call.rejectIfBodyTooLarge()) return@post
            val body = call.receive<ReorderRequest>()
            val ok = store.reorder(body.ids)
            if (!ok) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ids must match current set"))
            } else {
                call.respond(mapOf("ok" to true))
            }
        }

        post("/api/undo") {
            if (call.rejectIfNoCsrfHeader()) return@post
            val restored = store.undo()
            call.respond(mapOf("restored" to restored.size))
        }

        get("/api/events") {
            if (sseCount.incrementAndGet() > MAX_SSE) {
                sseCount.decrementAndGet()
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "too_many_subscribers"))
                return@get
            }
            try {
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
            } finally {
                sseCount.decrementAndGet()
            }
        }

        staticResources("/", "web") {
            default("index.html")
        }
    }
}
