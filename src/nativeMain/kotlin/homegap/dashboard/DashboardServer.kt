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
import io.ktor.server.application.createApplicationPlugin
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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

/**
 * The dashboard: the REST API (route groups in `homegap.routes`, mounted by [RoutingControllers])
 * and the embedded SPA. Meant to be exposed only as a private Pangolin resource; every
 * `/api` route also needs the bearer token ([DashboardAuth]). Never returns secret values.
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
        // The pings show a connection that is gone, so its terminal session is free for the next one.
        install(WebSockets) {
            pingPeriod = 20.seconds
            timeout = 40.seconds
            maxFrameSize = 1024 * 1024
        }
        install(SecurityHeaders)
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
        /**
         * The dashboard loads its own files only, and no page can put it in a frame. The docs
         * have scripts in their pages, so they get those too.
         */
        val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
            onCall { call ->
                val docs = call.request.path().startsWith(DocsFiles.PREFIX)
                call.response.header("Content-Security-Policy", if (docs) DOCS_POLICY else POLICY)
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header("X-Frame-Options", "DENY")
                call.response.header("Referrer-Policy", "no-referrer")
            }
        }

        const val POLICY = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; font-src 'self'; worker-src 'self' blob:; child-src blob:; connect-src 'self'; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        const val DOCS_POLICY = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; worker-src 'self' blob:; base-uri 'self'; form-action 'none'; frame-ancestors 'none'"

        const val NO_BUNDLE = "<!doctype html><title>HomeGAP</title><p>Dashboard bundle not embedded " +
            "(built with -Phomegap.skipWeb). API: <a href=\"/api/status\">/api/status</a></p>"
    }
}
