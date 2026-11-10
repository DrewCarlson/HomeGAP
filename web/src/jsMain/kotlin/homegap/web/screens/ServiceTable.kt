package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import homegap.presentation.services.ServiceRowModel
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Tr

/** Labeled services with per-node health; without [onTogglePause] (overview) the actions column is left out. */
@Composable
fun ServiceTable(
    services: List<ServiceRowModel>,
    onTogglePause: ((key: String, paused: Boolean) -> Unit)?
) {
    val headers = remember(onTogglePause) {
        listOfNotNull(
            "Hostname",
            "Kind",
            "Protocol / TLS",
            "Access",
            "Regions",
            "Health per node",
            "".takeUnless { onTogglePause == null }
        )
    }
    DataTable(
        headers = headers,
        emptyText = "No containers carry homegap.expose labels yet.",
        isEmpty = services.isEmpty()
    ) {
        services.forEach { service ->
            key(service.key) {
                Tr {
                    Cell("Hostname", "primary-cell") {
                        Div({ classes("cell-title") }) {
                            B({ classes("mono") }) {
                                Text(service.hostname)
                            }
                            if (service.paused) {
                                Tag("paused")
                            }
                        }
                        service.wildcard?.let { Muted(it, mono = true) }
                        Muted(service.container)
                    }
                    Cell("Kind") { Tag(service.kind) }
                    Cell("Protocol / TLS") { Text(service.protocol) }
                    Cell("Access") { Text(service.access) }
                    Cell("Regions") {
                        Div({
                            classes("checks")
                        }) {
                            service.regions.forEach { Tag(it) }
                        }
                    }
                    Cell("Health per node") { Badges(service.perNode) }
                    if (onTogglePause != null) {
                        Cell("Actions", "actions-cell") {
                            ActionButton(
                                text = if (service.paused) "Resume" else "Pause",
                                small = true
                            ) {
                                onTogglePause(service.key, service.paused)
                            }
                        }
                    }
                }
            }
        }
    }
}
