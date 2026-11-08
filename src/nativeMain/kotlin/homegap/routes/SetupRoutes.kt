package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.NodeSetupRequestDto
import homegap.api.SshKeyRequestDto
import homegap.api.SshLoginDto
import homegap.setup.NodeSetupService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** The add-node wizard: connection test, the node's SSH key, and setup jobs. */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class SetupRoutes(private val setup: NodeSetupService) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/setup") {
            post("/test") { testConnection() }
            post("/keys") { createKey() }
            post("/jobs") { startJob() }
            get("/jobs/{id}") { getJob() }
        }
    }

    /** Logs in with the given credentials and reports what the server is and allows. */
    suspend fun RoutingContext.testConnection() {
        call.respond(setup.test(call.receive<SshLoginDto>()))
    }

    /** Generates, imports or registers the node's SSH key; returns its public half. */
    suspend fun RoutingContext.createKey() {
        call.respond(setup.key(call.receive<SshKeyRequestDto>()))
    }

    /** Starts setting up a server; progress is read from [getJob]. */
    suspend fun RoutingContext.startJob() {
        call.respond(setup.start(call.receive<NodeSetupRequestDto>()))
    }

    suspend fun RoutingContext.getJob() {
        val job = setup.job(requireNotNull(call.parameters["id"]) { "job id required" })
        if (job == null) call.respond(HttpStatusCode.NotFound) else call.respond(job)
    }
}
