package homegap.dashboard

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.ActionResponse
import homegap.api.ApiJson
import homegap.config.AppConfig
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
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * The dashboard: the REST API (route groups in `homegap.routes`, mounted by [RoutingControllers])
 * and the embedded SPA. Meant to be exposed only as a private Pangolin resource; an optional
 * bearer token adds defense in depth. Never returns secret values.
 */
@SingleIn(AppScope::class)
@Inject
class DashboardServer(private val routingControllers: RoutingControllers, private val config: AppConfig) {
    fun start(host: String, port: Int) {
        // Reuse the address: after a restart the old process's connections linger in TIME_WAIT.
        embeddedServer(CIO, configure = { reuseAddress = true; connector { this.host = host; this.port = port } }) { module() }.start(wait = false)
    }

    fun Application.module() {
        install(ContentNegotiation) { json(ApiJson) }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ActionResponse(false, e.message ?: "bad request")) }
            exception<IllegalStateException> { call, e -> call.respond(HttpStatusCode.Conflict, ActionResponse(false, e.message ?: "conflict")) }
            exception<Throwable> { call, e -> call.respond(HttpStatusCode.InternalServerError, ActionResponse(false, e.message ?: "error")) }
        }
        routingControllers.init(this)
        routing {
            get("/healthz") { call.respondText("ok") }
            get(MapFiles.TILES_PATH) { with(MapFiles) { serveTiles(config.map.tiles) } }
            get("/map/{path...}") { with(MapFiles) { serveMapAsset() } }
            get(DocsFiles.PREFIX) { with(DocsFiles) { serveDocs() } }
            get("${DocsFiles.PREFIX}/{path...}") { with(DocsFiles) { serveDocs() } }
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
        // Hashed file names change with their content; index.html names them, so it is revalidated.
        val cacheControl = if (asset.path == "/index.html") "no-cache" else "public, max-age=31536000, immutable"
        call.response.header(HttpHeaders.CacheControl, cacheControl)
        respondAsset(asset)
    }

    private companion object {
        const val NO_BUNDLE = "<!doctype html><title>HomeGAP</title><p>Dashboard bundle not embedded " +
            "(built with -Phomegap.skipWeb). API: <a href=\"/api/status\">/api/status</a></p>"
    }
}
