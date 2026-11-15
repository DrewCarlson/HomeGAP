package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ActionResponse
import homegap.api.ZoneRecordRequest
import homegap.api.ZoneRecordsResponse
import homegap.dashboard.DashboardRole
import homegap.dashboard.DashboardUser
import homegap.zone.ZoneRecords
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/**
 * The records of the DNS zones, for the Zone records tab: `GET /dns/zones`,
 * `POST /dns/zones/{zone}/records`, and `PUT` and `DELETE /dns/zones/{zone}/records/{id}`.
 */
@Suppress("unused") // The dependency graph creates this class.
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class ZoneRecordRoutes(private val records: ZoneRecords) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/dns/zones") {
            get { list() }
            route("/{zone}/records") {
                post { add() }
                route("/{id}") {
                    put { update() }
                    delete { remove() }
                }
            }
        }
    }

    private suspend fun RoutingContext.list() {
        val canEdit = call.principal<DashboardUser>()?.role != DashboardRole.READ_ONLY
        call.respond(ZoneRecordsResponse(canEdit, records.zones()))
    }

    private suspend fun RoutingContext.add() {
        val message = records.add(zone(), call.receive<ZoneRecordRequest>())
        call.respond(ActionResponse(true, message))
    }

    private suspend fun RoutingContext.update() {
        val message = records.update(zone(), id(), call.receive<ZoneRecordRequest>())
        call.respond(ActionResponse(true, message))
    }

    private suspend fun RoutingContext.remove() {
        call.respond(ActionResponse(true, records.delete(zone(), id())))
    }

    private fun RoutingContext.zone(): String = requireNotNull(call.parameters["zone"]) { "zone required" }

    private fun RoutingContext.id(): String = requireNotNull(call.parameters["id"]) { "record id required" }
}
