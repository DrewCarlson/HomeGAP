package homegap.dashboard

import homegap.api.*
import homegap.config.ConfigLoader
import homegap.config.GeoLocation
import homegap.config.NodeConfig
import homegap.config.referencedSecrets
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.reconcile.Controller
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/**
 * REST API + embedded SPA. Meant to be exposed only as a private Pangolin resource; an optional
 * bearer token adds defense in depth. Never returns secret values.
 */
class DashboardServer(private val controller: Controller, private val configPath: String) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun start(host: String, port: Int) {
        embeddedServer(CIO, port = port, host = host) { module() }.start(wait = false)
    }

    fun Application.module() {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ActionResponse(false, e.message ?: "bad request")) }
            exception<IllegalStateException> { call, e -> call.respond(HttpStatusCode.Conflict, ActionResponse(false, e.message ?: "conflict")) }
            exception<Throwable> { call, e -> call.respond(HttpStatusCode.InternalServerError, ActionResponse(false, e.message ?: "error")) }
        }
        val token = controller.config.dashboard.authToken()?.resolve()

        routing {
            route("/api") {
                intercept(ApplicationCallPipeline.Plugins) {
                    if (token != null && call.request.header(HttpHeaders.Authorization) != "Bearer $token") {
                        call.respond(HttpStatusCode.Unauthorized, ActionResponse(false, "unauthorized"))
                        finish()
                    }
                }
                get("/status") { call.respond(controller.snapshot()) }
                get("/log") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 300
                    call.respond(LogResponse(controller.log.recent(limit, call.request.queryParameters["domain"])))
                }
                get("/diff") { call.respond(DiffResponse(nowMillis(), controller.lastDiff, controller.desiredStateJson())) }
                get("/secrets") {
                    call.respond(SecretsResponse(controller.config.referencedSecrets().map { (ref, use) ->
                        SecretRefDto(ref.name, use, ref.describe(), ref.resolve() != null)
                    }))
                }
                get("/config") {
                    val cfg = controller.config
                    call.respond(ConfigResponse(
                        path = configPath,
                        writable = cfg.dashboard.allowConfigEdits,
                        yaml = Files.readTextOrNull(configPath) ?: ConfigLoader.encode(cfg),
                        nodes = cfg.nodes.map { it.toDto() },
                    ))
                }
                post("/resync") {
                    controller.trigger("dashboard")
                    call.respond(ActionResponse(true, "Resync queued"))
                }
                post("/nodes/{id}/provision") {
                    val id = call.parameters["id"]!!
                    require(controller.config.node(id) != null) { "unknown node $id" }
                    controller.requestProvision(id)
                    call.respond(ActionResponse(true, "Re-provision of $id queued"))
                }
                post("/nodes/{id}/update/retry") {
                    call.respond(ActionResponse(true, controller.retryUpdate(call.parameters["id"]!!)))
                }
                post("/nodes/{id}/update/skip") {
                    call.respond(ActionResponse(true, controller.skipUpdate(call.parameters["id"]!!)))
                }
                post("/nodes") {
                    val dto = call.receive<NodeConfigDto>()
                    call.respond(ActionResponse(true, controller.addNode(dto.toConfig())))
                }
                delete("/nodes/{id}") {
                    call.respond(ActionResponse(true, controller.removeNode(call.parameters["id"]!!)))
                }
                post("/nodes/{id}/decommission") {
                    val confirm = call.request.queryParameters["confirm"] ?: ""
                    call.respond(ActionResponse(true, controller.confirmDecommission(call.parameters["id"]!!, confirm)))
                }
                get("/speedtest") { call.respond(controller.speedOverview()) }
                post("/speedtest/link") { call.respond(controller.createSpeedLink()) }
                post("/speedtest/nodes/{id}") {
                    val streams = call.request.queryParameters["streams"]?.toIntOrNull()
                    call.respond(controller.runTunnelSpeedTest(call.parameters["id"]!!, streams))
                }
                post("/pause") {
                    val req = call.receive<PauseRequest>()
                    call.respond(ActionResponse(true, controller.setPause(req.scope, req.id, req.paused)))
                }
            }
            get("/healthz") { call.respondText("ok") }
            get("/{path...}") {
                val path = call.request.path().let { if (it == "/" || it.isEmpty()) "/index.html" else it }
                val asset = WebAssets.files[path] ?: WebAssets.files["/index.html"]
                if (asset == null) {
                    call.respondText(
                        "<!doctype html><title>HomeGAP</title><p>Dashboard bundle not embedded (built with -Phomegap.skipWeb). API: <a href=\"/api/status\">/api/status</a></p>",
                        ContentType.Text.Html,
                    )
                    return@get
                }
                if (asset.path != "/index.html") call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                call.respondBytes(asset.bytes, ContentType.parse(asset.contentType))
            }
        }
    }
}

private fun NodeConfig.toDto() = NodeConfigDto(
    id = id, region = region, provider = provider, host = host, os = os, sshUser = sshUser, sshPort = sshPort,
    sshKeyFile = sshKeyFile, latitude = location?.latitude, longitude = location?.longitude, managementHost = managementHost,
)

private fun NodeConfigDto.toConfig(): NodeConfig {
    require(ConfigLoader.NODE_ID.matches(id)) { "node id must match ${ConfigLoader.NODE_ID.pattern}" }
    require(host.isNotBlank()) { "host is required" }
    require(sshKeyFile.isNotBlank()) { "ssh key file is required" }
    return NodeConfig(
        id = id, region = region, provider = provider, host = host, os = os, sshUser = sshUser, sshPort = sshPort,
        sshKeyFile = sshKeyFile,
        location = latitude?.let { lat -> longitude?.let { lon -> GeoLocation(lat, lon) } },
        managementHost = managementHost?.takeIf { it.isNotBlank() },
    )
}
