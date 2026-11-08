package homegap.web.screens

import androidx.compose.runtime.Composable
import homegap.presentation.nodes.AddNodeFormModel
import homegap.presentation.nodes.NodeField
import homegap.presentation.nodes.NodesScreenModel
import homegap.web.ui.ActionButton
import homegap.web.ui.ButtonStyle
import homegap.web.ui.Panel
import homegap.web.ui.TextField
import org.jetbrains.compose.web.dom.Div

@Composable
fun NodesScreen(model: NodesScreenModel) {
    Panel("Nodes") { NodeTable(model.nodes, model.events) }
    AddNodeForm(model.addNode)
}

@Composable
private fun AddNodeForm(form: AddNodeFormModel) {
    Panel("Add node", "Adds an entry to the config file; the next pass provisions it over SSH and extends DNS.", flush = false) {
        Div({ classes("form-grid") }) {
            NodeField.entries.forEach { field ->
                TextField(field.label, form.values[field].orEmpty(), field.placeholder) { form.onChange(field, it) }
            }
        }
        Div({ classes("form-actions") }) {
            ActionButton("Add and provision", ButtonStyle.Primary, enabled = form.canSubmit, onClick = form.onSubmit)
        }
    }
}
