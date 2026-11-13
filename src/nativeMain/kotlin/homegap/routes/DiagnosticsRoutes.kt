package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.diagnostics.NodeDiagnostics
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/**
 * Read-only views of a node: `GET /nodes/{id}/logs?service=&since=&tail=` (a container's logs)
 * and `GET /nodes/{id}/diagnostics` (a bundle of the node's state). Both answer [homegap.api.NodeTextDto].
 * Each read runs a script on the node.
 */
@Suppress("unused") // The dependency graph creates this class.
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class DiagnosticsRoutes(private val diagnostics: NodeDiagnostics) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/nodes/{id}") {
            get("/logs") { logs() }
            get("/diagnostics") { bundle() }
        }
    }

    suspend fun RoutingContext.logs() {
        val query = call.request.queryParameters
        val service = requireNotNull(query["service"]) { "service required" }
        call.respond(diagnostics.logs(nodeId(), service, query["since"], query["tail"]?.toIntOrNull()))
    }

    suspend fun RoutingContext.bundle() {
        call.respond(diagnostics.bundle(nodeId()))
    }

    private fun RoutingContext.nodeId(): String = requireNotNull(call.parameters["id"]) { "node id required" }
}
