package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ActionResponse
import homegap.api.LocationDto
import homegap.api.LocationsResponse
import homegap.api.PlaceDto
import homegap.api.SetHomelabLocationRequest
import homegap.locations.LocationCatalog
import homegap.reconcile.Controller
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** The locations catalog and the homelab's location. */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class LocationRoutes(private val catalog: LocationCatalog, private val controller: Controller) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/locations") {
            get { list() }
            route("/{id}") {
                put { save() }
                delete { remove() }
            }
        }
        parent.put("/homelab/location") { setHomelab() }
    }

    suspend fun RoutingContext.list() {
        val home = controller.config.homelab.location?.let { PlaceDto(it.latitude, it.longitude, it.name) }
        respondRevalidated(LocationsResponse(catalog.all(), home))
    }

    suspend fun RoutingContext.save() {
        val id = requireNotNull(call.parameters["id"])
        val dto = call.receive<LocationDto>()
        require(dto.id == id) { "the id in the path and the body differ" }
        val saved = catalog.put(dto)
        call.respond(ActionResponse(true, "Saved ${saved.name}"))
    }

    suspend fun RoutingContext.remove() {
        val id = requireNotNull(call.parameters["id"])
        val reset = call.request.queryParameters["reset"] == "true"
        catalog.delete(id, reset)
        call.respond(ActionResponse(true, if (reset) "Restored the shipped $id" else "Removed $id"))
    }

    suspend fun RoutingContext.setHomelab() {
        val req = call.receive<SetHomelabLocationRequest>()
        call.respond(ActionResponse(true, controller.setHomelabLocation(req.location)))
    }
}
