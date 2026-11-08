package homegap.dashboard

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.ActionResponse
import homegap.routes.RoutingControllers
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * The dashboard: the REST API (route groups in `homegap.routes`, mounted by [RoutingControllers])
 * and the embedded SPA. Meant to be exposed only as a private Pangolin resource; an optional
 * bearer token adds defense in depth. Never returns secret values.
 */
@SingleIn(AppScope::class)
@Inject
class DashboardServer(private val routingControllers: RoutingControllers) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun start(host: String, port: Int) {
        // Reuse the address: after a restart the old process's connections linger in TIME_WAIT.
        embeddedServer(CIO, configure = { reuseAddress = true; connector { this.host = host; this.port = port } }) { module() }.start(wait = false)
    }

    fun Application.module() {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ActionResponse(false, e.message ?: "bad request")) }
            exception<IllegalStateException> { call, e -> call.respond(HttpStatusCode.Conflict, ActionResponse(false, e.message ?: "conflict")) }
            exception<Throwable> { call, e -> call.respond(HttpStatusCode.InternalServerError, ActionResponse(false, e.message ?: "error")) }
        }
        routingControllers.init(this)
        routing {
            get("/healthz") { call.respondText("ok") }
            get("/{path...}") { serveAsset() }
        }
    }

    /** The SPA: known asset paths, everything else falls back to index.html (client-side routes). */
    private suspend fun RoutingContext.serveAsset() {
        val path = call.request.path().let { if (it == "/" || it.isEmpty()) "/index.html" else it }
        val asset = WebAssets.files[path] ?: WebAssets.files["/index.html"]
        if (asset == null) {
            call.respondText(NO_BUNDLE, ContentType.Text.Html)
            return
        }
        if (asset.path != "/index.html") call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        call.response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
        val gzip = asset.gzip
        if (gzip != null && call.request.header(HttpHeaders.AcceptEncoding)?.contains("gzip") == true) {
            call.response.header(HttpHeaders.ContentEncoding, "gzip")
            call.respondBytes(gzip, ContentType.parse(asset.contentType))
        } else {
            call.respondBytes(asset.bytes, ContentType.parse(asset.contentType))
        }
    }

    private companion object {
        const val NO_BUNDLE = "<!doctype html><title>HomeGAP</title><p>Dashboard bundle not embedded " +
            "(built with -Phomegap.skipWeb). API: <a href=\"/api/status\">/api/status</a></p>"
    }
}
