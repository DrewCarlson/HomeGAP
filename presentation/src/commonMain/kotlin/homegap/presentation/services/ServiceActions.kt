package homegap.presentation.services

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.ServiceStatusDto
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.Confirmations
import homegap.presentation.data.ControllerApi
import homegap.presentation.data.PauseScope
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.ConfirmModel

/** The per-service actions, shared by the Overview and the Services page. */
@SingleIn(AppScope::class)
@Inject
class ServiceActions(
    private val api: ControllerApi,
    private val actions: ActionRunner,
    private val confirmations: Confirmations,
) {
    fun forService(service: ServiceStatusDto): List<ActionModel> = buildList {
        if (service.protocol.startsWith("http")) {
            add(
                ActionModel(
                    label = "Open",
                    href = "https://${service.hostname}/",
                )
            )
        }
        add(
            ActionModel(
                label = if (service.paused) "Resume service" else "Pause service",
                onClick = { togglePause(service) },
            )
        )
        add(
            ActionModel(
                label = "Logs",
                location = Location(Route.Diagnostics),
                tooltip = "Container logs on each node",
            )
        )
    }

    fun togglePause(service: ServiceStatusDto) {
        if (service.paused) {
            actions.run("Resume") { api.pause(PauseScope.Service, service.key, false) }
            return
        }
        confirmations.ask(
            ConfirmModel(
                title = "Pause ${service.hostname}?",
                text = "HomeGAP stops changing this service: its records and routes stay as they are now.",
                confirmLabel = "Pause ${service.hostname}",
                consequences = listOf(
                    "Visitors are still served the way they are now",
                    "New label changes on ${service.container} wait until you resume",
                ),
            )
        ) {
            actions.run("Pause") { api.pause(PauseScope.Service, service.key, true) }
        }
    }
}
