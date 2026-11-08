package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.dns.DnsScreenModel
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Tr

@Composable
fun DnsScreen(model: DnsScreenModel) {
    Panel("Records", "Geo records drop offline members automatically; plain records always answer") {
        val headers = listOf("Name", "Type", "Value", "Node", "Routing", "Monitor", "State")
        DataTable(headers, "No DNS observations yet.", model.records.isEmpty()) {
            model.records.forEach { record ->
                key(record.key) {
                    Tr {
                        Cell("Name", "mono", "primary-cell") { Text(record.name) }
                        Cell("Type") { Tag(record.type) }
                        Cell("Value", "mono") { Text(record.value) }
                        Cell("Node") { Text(record.node) }
                        Cell("Routing", "muted") { Text(record.routing) }
                        Cell("Monitor") { record.monitor?.let { Pill(it) } ?: Placeholder("none") }
                        Cell("State") { Pill(record.state) }
                    }
                }
            }
        }
    }
}
