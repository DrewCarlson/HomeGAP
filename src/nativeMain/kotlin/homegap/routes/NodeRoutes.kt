package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ActionResponse
import homegap.api.NodeConfigDto
import homegap.api.SetNodeLocationRequest
import homegap.reconcile.Controller
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** Node lifecycle: add, provision, update recovery, remove and decommission. */
@Suppress("unused") // The dependency graph creates this class.
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class NodeRoutes(private val controller: Controller) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/nodes") {
            post { addNode() }
            route("/{id}") {
                delete { removeNode() }
                post("/provision") { provision() }
                post("/update/retry") { retryUpdate() }
                post("/update/skip") { skipUpdate() }
                post("/decommission") { decommission() }
                put("/location") { setLocation() }
            }
        }
    }

    /** Adds the node to the config file. The next pass provisions it. */
    suspend fun RoutingContext.addNode() {
        val node = call.receive<NodeConfigDto>().toConfig()
        call.respond(ActionResponse(true, controller.addNode(node)))
    }

    suspend fun RoutingContext.provision() {
        val id = nodeId()
        require(controller.config.node(id) != null) { "unknown node $id" }
        controller.requestProvision(id)
        call.respond(ActionResponse(true, "Re-provision of $id queued"))
    }

    suspend fun RoutingContext.retryUpdate() {
        call.respond(ActionResponse(true, controller.retryUpdate(nodeId())))
    }

    suspend fun RoutingContext.skipUpdate() {
        call.respond(ActionResponse(true, controller.skipUpdate(nodeId())))
    }

    /** Moves the node on the maps and in geo DNS steering. Writes its config entry again. */
    suspend fun RoutingContext.setLocation() {
        val request = call.receive<SetNodeLocationRequest>()
        call.respond(ActionResponse(true, controller.setNodeLocation(nodeId(), request.location)))
    }

    /** Removes the node from the config. DNS drains, and the stack stays up until decommission. */
    suspend fun RoutingContext.removeNode() {
        val (message, undo) = controller.removeNode(nodeId())
        call.respond(ActionResponse(true, message, undo))
    }

    /**
     * Stops the stack of a removed node. `?confirm=` must repeat the node id. `?force=true` forgets
     * the node without a teardown.
     */
    suspend fun RoutingContext.decommission() {
        val confirm = call.request.queryParameters["confirm"].orEmpty()
        val force = call.request.queryParameters["force"] == "true"
        call.respond(ActionResponse(true, controller.confirmDecommission(nodeId(), confirm, force)))
    }

    private fun RoutingContext.nodeId(): String = requireNotNull(call.parameters["id"]) { "node id required" }
}
