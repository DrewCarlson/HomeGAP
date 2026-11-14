package homegap.web.screens

import androidx.compose.runtime.Composable
import homegap.presentation.ui.FieldKind
import androidx.compose.runtime.key
import homegap.presentation.access.AccessScreenModel
import homegap.presentation.access.DeviceRowModel
import homegap.presentation.access.InvitationRowModel
import homegap.presentation.access.NewUserFormModel
import homegap.presentation.access.RoleOptionModel
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.QrCode
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.*

private const val APP_HELP =
    "In the Pangolin app, choose a self-hosted server and enter this address, or scan the code from a phone. " +
        "Every node serves it, so devices connect to the nearest one."

private const val INVITE_HELP =
    "Off: HomeGAP creates an invite link, valid 7 days, for you to send. The person opens it and picks their own password."

/** Who can sign in to the Pangolin apps, their invitations and devices. */
@Composable
fun AccessScreen(
    model: AccessScreenModel,
    viewport: Viewport,
    onOpen: (Location) -> Unit,
) {
    when (model) {
        AccessScreenModel.Loading -> Div({ classes("loading") }) { Text("Loading…") }

        is AccessScreenModel.Unavailable -> {
            P({ classes("banner", "info") }) { Text(model.message) }
            model.clientAddress?.let { address ->
                ClientAddress(
                    address = address,
                    via = null,
                    qr = QrCode.encode(address),
                )
            }
        }

        is AccessScreenModel.Ready -> {
            ClientAddress(
                address = model.clientAddress,
                via = model.via,
                qr = model.qr,
            )
            People(
                model = model,
                onOpen = onOpen,
            )
            if (model.invitations.isNotEmpty()) {
                Invitations(
                    rows = model.invitations,
                    onReissue = model.onReissueInvitation,
                    onRemove = model.onRemoveInvitation,
                )
            }
            Devices(
                rows = model.devices,
                onOpen = onOpen,
            )
            if (model.addUserOpen) {
                AddUser(
                    form = model.newUser,
                    inviteLink = model.inviteLink,
                    viewport = viewport,
                    onDismissLink = model.onDismissInviteLink,
                    onClose = { onOpen(Location(Route.Access)) },
                )
            }
        }
    }
}

@Composable
private fun ClientAddress(address: String, via: String?, qr: QrCode?) {
    Figure(title = "Pangolin app address", note = via) {
        Div({ classes("app-address") }) {
            qr?.let { code ->
                QrImage(
                    code = code,
                    label = "QR code for $address",
                )
            }
            Div({ classes("app-address-text") }) {
                Div({ classes("copy-row") }) {
                    Span({ classes("mono", "app-address-value") }) { Text(address) }
                    ActionButton(
                        text = "Copy",
                        small = true,
                        onClick = { window.navigator.clipboard.writeText(address) },
                    )
                }
                P({ classes("muted") }) { Text(APP_HELP) }
            }
        }
    }
}

@Composable
private fun People(model: AccessScreenModel.Ready, onOpen: (Location) -> Unit) {
    Figure(
        title = "People",
        flush = true,
        actions = { Span({ classes("caption", "muted") }) { Text("Everyone who can sign in, on every node") } },
    ) {
        Ul({ classes("people") }) {
            model.users.forEach { user ->
                key(user.id) {
                    Li({ classes("person") }) {
                        NavLink(
                            href = HashRouter.href(user.location),
                            onNavigate = { onOpen(user.location) },
                            classNames = listOf("person-name"),
                        ) {
                            Span({ classes("avatar") }) { Text(user.email.take(1).uppercase()) }
                            Span {
                                B { Text(user.name ?: user.email.substringBefore('@')) }
                                Span({ classes("mono", "muted", "small") }) { Text(" ${user.email}") }
                            }
                        }
                        Span({ classes("muted", "small") }) {
                            Text(user.roles.filter { it.selected }.joinToString(", ") { it.name })
                        }
                        Span({ classes("mono", "muted", "small", "wide-only") }) {
                            Text("${user.devices} ${if (user.devices == 1) "device" else "devices"}")
                        }
                    }
                }
            }
        }
    }
}

/** Multi-select role buttons. */
@Composable
private fun RoleChips(
    roles: List<RoleOptionModel>,
    enabled: Boolean,
    onToggle: (Int) -> Unit,
) {
    Div({ classes("role-chips") }) {
        roles.forEach { role ->
            Button({
                classes("chip")
                if (role.selected) {
                    classes("active")
                }
                if (!enabled) {
                    disabled()
                }
                attr("aria-pressed", role.selected.toString())
                onClick { onToggle(role.id) }
            }) {
                Text(if (role.selected) "✓ ${role.name}" else "+ ${role.name}")
            }
        }
    }
}

@Composable
private fun Invitations(
    rows: List<InvitationRowModel>,
    onReissue: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Figure(
        title = "Open invitations",
        flush = true,
    ) {
        Ul({ classes("people") }) {
            rows.forEach { row ->
                key(row.id) {
                    Li({ classes("person", "invite") }) {
                        Span({ classes("mono") }) { Text(row.email) }
                        Span({ classes("muted", "small") }) { Text(row.roles) }
                        row.expires?.let { Span({ classes("muted", "small", "wide-only") }) { Text(it) } }
                        Span({ classes("row-menu") }) {
                            KebabMenu(
                                label = "Actions for the invitation to ${row.email}",
                                items = listOf(
                                    MenuItem(
                                        label = "New link",
                                        onClick = { onReissue(row.id) },
                                    ),
                                    MenuItem(
                                        label = "Revoke",
                                        danger = true,
                                        onClick = { onRemove(row.id) },
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Devices(rows: List<DeviceRowModel>, onOpen: (Location) -> Unit) {
    Figure(
        title = "Devices",
        flush = true,
        actions = { Span({ classes("caption", "muted") }) { Text("Pangolin apps and machine clients") } },
    ) {
        if (rows.isEmpty()) {
            P({ classes("figure-note") }) { Text("No devices have connected yet.") }
        }
        Div({ classes("device-grid") }) {
            rows.forEach { row ->
                key(row.id) {
                    NavLink(
                        href = HashRouter.href(row.location),
                        onNavigate = { onOpen(row.location) },
                        classNames = listOf("device-card"),
                    ) {
                        Div({ classes("edge-card-head") }) {
                            B { Text(row.name) }
                            Span({ classes("mono", "small", row.status.health.toneClass) }) { Text(row.status.text) }
                        }
                        Div({ classes("muted", "small") }) {
                            Text(listOfNotNull(row.agent, row.owner).joinToString(" · "))
                        }
                        row.via?.let { Div({ classes("mono", "small") }) { Text(it) } }
                    }
                }
            }
        }
    }
}

/** The Add user form: a drawer or dialog with explicit Cancel and Create. */
@Composable
private fun AddUser(
    form: NewUserFormModel,
    inviteLink: String?,
    viewport: Viewport,
    onDismissLink: () -> Unit,
    onClose: () -> Unit,
) {
    Overlay(
        label = "Add a user",
        titleId = "add-user-title",
        style = when (viewport) {
            Viewport.Phone -> OverlayStyle.Sheet
            Viewport.Desktop -> OverlayStyle.Drawer
            else -> OverlayStyle.Drawer
        },
        onDismiss = onClose,
    ) {
        Div({ classes("overlay-panel", "form-panel") }) {
            Div({ classes("wizard-head") }) {
                Div {
                    Div({ classes("caption") }) { Text("New user") }
                    H2({ id("add-user-title") }) { Text("Add a user") }
                }
                IconButton(
                    iconPath = Icons.CLOSE,
                    label = "Close",
                    onClick = onClose,
                )
            }
            Div({ classes("form-body") }) {
                ScrollHint()
                if (inviteLink != null) {
                    P { Text("Send this link to the person. It works for 7 days.") }
                    Div({ classes("copy-row") }) {
                        Span({ classes("mono", "small", "ellipsis") }) { Text(inviteLink) }
                        ActionButton(
                            text = "Copy link",
                            small = true,
                            onClick = { window.navigator.clipboard.writeText(inviteLink) },
                        )
                    }
                } else {
                    TextField(
                        label = "Email",
                        value = form.email,
                        placeholder = "person@example.com",
                        type = InputType.Email,
                        kind = FieldKind.Identifier,
                        error = form.emailError,
                        onChange = form.onEmail,
                    )
                    Div({ classes("field") }) {
                        Span({ classes("muted") }) { Text("Roles") }
                        RoleChips(
                            roles = form.roles,
                            enabled = true,
                            onToggle = form.onToggleRole,
                        )
                        form.rolesError?.let { Span({ classes("field-note", "error") }) { Text(it) } }
                    }
                    CheckOption(
                        title = "Set a password now",
                        description = if (form.setPassword) "They can sign in at once. Share it with them privately." else INVITE_HELP,
                        checked = form.setPassword,
                        onChange = form.onSetPassword,
                    )
                    if (form.setPassword) {
                        TextField(
                            label = "Password",
                            value = form.password,
                            placeholder = "at least 8 characters",
                            type = InputType.Password,
                            error = form.passwordError,
                            onChange = form.onPassword,
                        )
                    }
                    form.serverError?.let { ErrorText(it, mono = false) }
                }
            }
            Div({ classes("form-actions") }) {
                if (inviteLink != null) {
                    ActionButton(
                        text = "Done",
                        style = ButtonStyle.Primary,
                        onClick = {
                            onDismissLink()
                            onClose()
                        },
                    )
                } else {
                    ActionButton(
                        text = "Cancel",
                        onClick = onClose,
                    )
                    ActionButton(
                        text = form.submitText,
                        style = ButtonStyle.Primary,
                        enabled = form.canSubmit,
                        onClick = form.onSubmit,
                    )
                }
            }
        }
    }
}
