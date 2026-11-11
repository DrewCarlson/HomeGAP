package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.sharing.ShareLinksEvent
import homegap.presentation.sharing.ShareLinksScreenModel
import homegap.web.ui.*
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.*

private val SHARE_LINK_HEADERS = listOf("Label", "Created", "Expires", "Last used", "")

/** Share links over the Access page: a wide dialog on desktops, a drawer on tablets, full screen on phones. */
@Composable
fun ShareLinksOverlay(
    model: ShareLinksScreenModel,
    viewport: Viewport,
    onClose: () -> Unit,
) {
    Overlay(
        label = "Share links",
        titleId = "share-links-title",
        style = when (viewport) {
            Viewport.Phone -> OverlayStyle.FullScreen
            Viewport.Tablet -> OverlayStyle.Drawer
            else -> OverlayStyle.Modal
        },
        onDismiss = onClose,
    ) {
        Div({ classes("overlay-panel", "wide-panel") }) {
            Div({ classes("wizard-head") }) {
                Div {
                    Div({ classes("caption") }) { Text("Access › Share links") }
                    H2({ id("share-links-title") }) { Text("Read-only links to this dashboard") }
                }
                IconButton(
                    iconPath = Icons.CLOSE,
                    label = "Close Share links",
                    onClick = onClose,
                )
            }
            Div({ classes("wide-panel-body") }) {
                ScrollHint()
                ShareLinksScreen(model)
            }
        }
    }
}

@Composable
private fun ShareLinksScreen(model: ShareLinksScreenModel) {
    when (model) {
        ShareLinksScreenModel.Loading -> Div({ classes("loading") }) { Text("Loading…") }
        is ShareLinksScreenModel.Disabled -> Div({ classes("banner", "info") }) { Text(model.message) }
        is ShareLinksScreenModel.Ready -> {
            NewLink(model)
            Links(model)
        }
    }
}

@Composable
private fun NewLink(model: ShareLinksScreenModel.Ready) {
    val send = model.onEvent
    Panel(
        title = "New link",
        subtitle = "Whoever opens the link sees every page except Access and this one, and can't change anything.",
        flush = false,
    ) {
        val created = model.created
        if (created != null) {
            P { Text("The link for “${created.label}”. Copy it now: it is shown only once.") }
            Div({ classes("copy-row") }) {
                Span({ classes("mono", "small", "ellipsis") }) { Text(created.url) }
                ActionButton(
                    text = "Copy link",
                    small = true,
                    onClick = { window.navigator.clipboard.writeText(created.url) },
                )
                ActionButton(
                    text = "Done",
                    small = true,
                    onClick = { send(ShareLinksEvent.DismissCreated) },
                )
            }
            return@Panel
        }
        Form(attrs = {
            classes("share-form")
            onSubmit { event ->
                event.preventDefault()
                if (model.canCreate) {
                    send(ShareLinksEvent.Create)
                }
            }
        }) {
            TextField(
                label = "Label",
                value = model.label,
                placeholder = "Who it is for, such as Sam",
                onChange = { send(ShareLinksEvent.Label(it)) },
            )
            Label {
                Text("Expires after")
                Segmented(
                    options = model.expiries,
                    selected = model.expiry,
                    label = { it.label },
                    onSelect = { send(ShareLinksEvent.Expiry(it)) },
                )
            }
            model.error?.let { error ->
                Div({ classes("banner", "error") }) { Text(error) }
            }
            Div({ classes("inline-row") }) {
                // Submits the form, so Enter in the label field creates the link too.
                Button({
                    classes("btn", "primary")
                    attr("type", "submit")
                    if (!model.canCreate) {
                        disabled()
                    }
                }) {
                    Text(model.createText)
                }
            }
        }
    }
}

@Composable
private fun Links(model: ShareLinksScreenModel.Ready) {
    Panel(
        title = "Open links",
        subtitle = "Revoking a link stops it at once, also in a browser that has it open.",
    ) {
        DataTable(
            headers = SHARE_LINK_HEADERS,
            emptyText = "No share links yet.",
            isEmpty = model.links.isEmpty(),
        ) {
            model.links.forEach { link ->
                key(link.id) {
                    Tr {
                        Cell("Label", "primary-cell") {
                            B { Text(link.label) }
                        }
                        Cell("Created") { Text(link.created) }
                        Cell("Expires") { Text(link.expires) }
                        Cell("Last used") { Text(link.lastUsed) }
                        Cell("", "actions") {
                            ActionButton(
                                text = "Revoke",
                                style = ButtonStyle.Danger,
                                small = true,
                                onClick = { model.onEvent(ShareLinksEvent.Revoke(link.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}
