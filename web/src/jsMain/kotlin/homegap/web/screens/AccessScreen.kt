package homegap.web.screens

import androidx.compose.runtime.Composable
import homegap.presentation.ui.FieldKind
import androidx.compose.runtime.key
import homegap.presentation.access.AccessScreenModel
import homegap.presentation.access.DeviceRowModel
import homegap.presentation.access.MachineSecretModel
import homegap.presentation.access.NewMachineFormModel
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

private const val APP_HELP = "Enter this address in the Pangolin app, or scan the code."

private const val GENERATED_HELP = "Off: HomeGAP makes a password and shows it once."

/** Who can sign in to the apps, and their devices. */
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
                    qr = QrCode.encode(address),
                )
            }
        }

        is AccessScreenModel.Ready -> {
            People(
                model = model,
                onOpen = onOpen,
            )
            Devices(
                rows = model.devices,
                onOpen = onOpen,
            )
            ClientAddress(
                address = model.clientAddress,
                qr = model.qr,
            )
            if (model.addMachineOpen) {
                AddMachine(
                    form = model.newMachine,
                    secret = model.machineSecret,
                    viewport = viewport,
                    onDismissSecret = model.onDismissMachineSecret,
                    onClose = { onOpen(Location(Route.Access)) },
                )
            }
            if (model.addUserOpen) {
                AddUser(
                    form = model.newUser,
                    newPassword = model.newPassword,
                    passwordOwner = model.passwordOwner,
                    viewport = viewport,
                    onDismissPassword = model.onDismissNewPassword,
                    onClose = { onOpen(Location(Route.Access)) },
                )
            }
        }
    }
}

@Composable
private fun ClientAddress(address: String, qr: QrCode?) {
    Figure(title = "App address") {
        Div({ classes("app-address-text") }) {
            Div({ classes("copy-row") }) {
                Span({ classes("mono", "app-address-value") }) { Text(address) }
                ActionButton(
                    text = "Copy",
                    small = true,
                    onClick = { window.navigator.clipboard.writeText(address) },
                )
            }
            P({ classes("muted", "small") }) { Text(APP_HELP) }
            // The code opens on request: it is for a phone that is at hand.
            qr?.let { code ->
                Details({ classes("qr-details") }) {
                    Summary { Text("Show QR") }
                    QrImage(
                        code = code,
                        label = "QR code for $address",
                    )
                }
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
                            val roles = user.roles.filter { it.selected }.joinToString(", ") { it.name }
                            // A blocked user and the only admin show in the list, not only in the Inspector.
                            Text((listOf(roles) + user.badges.map { it.text }).joinToString(" · "))
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
private fun Devices(rows: List<DeviceRowModel>, onOpen: (Location) -> Unit) {
    Figure(
        title = "Devices",
        flush = true,
        actions = { Span({ classes("caption", "muted") }) { Text("Devices of the VPN and machine clients") } },
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

/** The Add machine form. After Create it shows the id and the secret of the machine, once. */
@Composable
private fun AddMachine(
    form: NewMachineFormModel,
    secret: MachineSecretModel?,
    viewport: Viewport,
    onDismissSecret: () -> Unit,
    onClose: () -> Unit,
) {
    Overlay(
        label = "Add a machine",
        titleId = "add-machine-title",
        style = when (viewport) {
            Viewport.Phone -> OverlayStyle.Sheet
            else -> OverlayStyle.Drawer
        },
        onDismiss = onClose,
    ) {
        Div({ classes("overlay-panel", "form-panel") }) {
            Div({ classes("wizard-head") }) {
                Div {
                    Div({ classes("caption") }) { Text(secret?.name ?: "New machine") }
                    H2({ id("add-machine-title") }) { Text("Add a machine") }
                }
                IconButton(
                    iconPath = Icons.CLOSE,
                    label = "Close",
                    onClick = onClose,
                )
            }
            Div({ classes("form-body") }) {
                ScrollHint()
                if (secret != null) {
                    P { Text("Give the machine its id and secret. HomeGAP shows the secret only once.") }
                    CopyValue(
                        label = "Id",
                        value = secret.id,
                    )
                    CopyValue(
                        label = "Secret",
                        value = secret.secret,
                    )
                } else {
                    TextField(
                        label = "Name",
                        value = form.name,
                        placeholder = "backup server",
                        kind = FieldKind.Identifier,
                        onChange = form.onName,
                    )
                    Div({ classes("field") }) {
                        Span({ classes("muted") }) { Text("Roles") }
                        RoleChips(
                            roles = form.roles,
                            enabled = true,
                            onToggle = form.onToggleRole,
                        )
                    }
                    form.serverError?.let { ErrorText(it, mono = false) }
                }
            }
            Div({ classes("form-actions") }) {
                if (secret != null) {
                    ActionButton(
                        text = "Done",
                        style = ButtonStyle.Primary,
                        onClick = {
                            onDismissSecret()
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

/** A value with its name and a Copy button. */
@Composable
private fun CopyValue(label: String, value: String) {
    Div({ classes("field") }) {
        Span({ classes("muted") }) { Text(label) }
        Div({ classes("copy-row") }) {
            Span({ classes("mono", "small", "ellipsis") }) { Text(value) }
            ActionButton(
                text = "Copy",
                small = true,
                onClick = { window.navigator.clipboard.writeText(value) },
            )
        }
    }
}

/** The Add user form: a drawer or dialog with explicit Cancel and Create. */
@Composable
private fun AddUser(
    form: NewUserFormModel,
    newPassword: String?,
    passwordOwner: String?,
    viewport: Viewport,
    onDismissPassword: () -> Unit,
    onClose: () -> Unit,
) {
    // The same panel shows a new password of a user that exists.
    val title = if (passwordOwner != null) "New password" else "Add a user"
    Overlay(
        label = title,
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
                    Div({ classes("caption") }) { Text(passwordOwner ?: "New user") }
                    H2({ id("add-user-title") }) { Text(title) }
                }
                IconButton(
                    iconPath = Icons.CLOSE,
                    label = "Close",
                    onClick = onClose,
                )
            }
            Div({ classes("form-body") }) {
                ScrollHint()
                if (newPassword != null) {
                    P { Text("Give this password to the person. HomeGAP shows it only once.") }
                    Div({ classes("copy-row") }) {
                        Span({ classes("mono", "small", "ellipsis") }) { Text(newPassword) }
                        ActionButton(
                            text = "Copy password",
                            small = true,
                            onClick = { window.navigator.clipboard.writeText(newPassword) },
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
                        description = if (form.setPassword) "They can sign in at once. Share it with them privately." else GENERATED_HELP,
                        checked = form.setPassword,
                        onChange = form.onSetPassword,
                    )
                    if (form.setPassword) {
                        TextField(
                            label = "Password",
                            value = form.password,
                            placeholder = "at least 12 characters",
                            type = InputType.Password,
                            error = form.passwordError,
                            onChange = form.onPassword,
                        )
                    }
                    form.serverError?.let { ErrorText(it, mono = false) }
                }
            }
            Div({ classes("form-actions") }) {
                if (newPassword != null) {
                    ActionButton(
                        text = "Done",
                        style = ButtonStyle.Primary,
                        onClick = {
                            onDismissPassword()
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
