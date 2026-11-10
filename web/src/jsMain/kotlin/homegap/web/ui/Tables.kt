package homegap.web.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.colspan
import org.jetbrains.compose.web.dom.*

/** A data table; rows use [Cell] so they turn into labeled cards on narrow screens. */
@Composable
fun DataTable(
    headers: List<String>,
    emptyText: String? = null,
    isEmpty: Boolean = false,
    rows: @Composable () -> Unit,
) {
    Table({ classes("data") }) {
        Thead { Tr { headers.forEach { Th { Text(it) } } } }
        Tbody {
            if (isEmpty && emptyText != null) {
                EmptyRow(headers.size, emptyText)
            }
            rows()
        }
    }
}

/** A table cell that becomes a labeled row on narrow screens (label from the column header). */
@Composable
fun Cell(
    label: String,
    vararg classNames: String,
    content: @Composable () -> Unit
) {
    Td({
        attr("data-label", label)
        if (classNames.isNotEmpty()) {
            classes(*classNames)
        }
    }) {
        Div({ classes("cell") }) { content() }
    }
}

@Composable
private fun EmptyRow(columns: Int, text: String) {
    Tr({ classes("empty") }) {
        Td({
            colspan(columns)
        }) {
            Text(text)
        }
    }
}
