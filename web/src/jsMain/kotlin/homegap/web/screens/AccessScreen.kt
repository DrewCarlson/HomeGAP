package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.access.*
import homegap.presentation.app.BannerKind
import homegap.presentation.app.BannerModel
import homegap.web.ui.*
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.*

private const val APP_HELP = "In the Pangolin app, choose a self-hosted server and enter this address. It's served by every " +
    "node and DNS sends each device to the nearest healthy one, so signing in works while the homelab is down."

private const val INVITE_HELP = "Without a password, HomeGAP creates an invite link (valid 7 days) for you to send; the person " +
    "opens it and picks their own password."

@Composable
fun AccessScreen(model: AccessScreenModel) {
    when (model) {
        AccessScreenModel.Loading -> Div({ classes("loading") }) { Text("Loading…") }
        is AccessScreenModel.Unavailable -> {
            Banner(BannerModel(BannerKind.Info, model.message))
            model.clientAddress?.let { ClientAddress(it, null) }
        }
        is AccessScreenModel.Ready -> {
            Div({ classes("two-col") }) {
                ClientAddress(model.clientAddress, model.via)
                NewUser(model.newUser, model.inviteLink, model.onDismissInviteLink)
            }
            Users(model)
            if (model.invitations.isNotEmpty()) Invitations(model.invitations, model.onRemoveInvitation)
            Devices(model.devices, model.onDeviceAction)
        }
    }
}

@Composable
private fun ClientAddress(address: String, via: String?) {
    Panel("Pangolin app address", via, flush = false) {
        Div({ classes("copy-row") }) {
            Span({ classes("mono") }) { Text(address) }
            ActionButton("Copy", small = true) { window.navigator.clipboard.writeText(address) }
        }
        P({ classes("muted") }) { Text(APP_HELP) }
    }
}

@Composable
private fun NewUser(form: NewUserFormModel, inviteLink: String?, onDismiss: () -> Unit) {
    Panel("Add a user", flush = false) {
        TextField("Email", form.email, "person@example.com", InputType.Email, form.onEmail)
        Div({ classes("field") }) {
            Span({ classes("muted") }) { Text("Roles") }
            RoleChips(form.roles, enabled = true, onToggle = form.onToggleRole)
        }
        CheckOption("Set a password now", "They can sign in straight away. Share it with them privately.", form.setPassword, onChange = form.onSetPassword)
        if (form.setPassword) TextField("Password", form.password, "at least 8 characters", InputType.Password, form.onPassword)
        else P({ classes("muted") }) { Text(INVITE_HELP) }
        form.error?.let { ErrorText(it, mono = false) }
        ActionButton(form.submitText, ButtonStyle.Primary, enabled = form.canSubmit, onClick = form.onSubmit)
        inviteLink?.let { link ->
            Div({ classes("links") }) {
                Div({ classes("copy-row") }) {
                    Span({ classes("link-label") }) { Text("Invite link") }
                    Span({ classes("mono") }) { Text(link.substringBefore("?") + "?…") }
                    ActionButton("Copy", small = true) { window.navigator.clipboard.writeText(link) }
                    ActionButton("Done", small = true, onClick = onDismiss)
                }
            }
        }
    }
}

/** Multi-select role buttons. */
@Composable
private fun RoleChips(roles: List<RoleOptionModel>, enabled: Boolean, onToggle: (Int) -> Unit) {
    Div({ classes("segmented") }) {
        roles.forEach { role ->
            Button({
                if (role.selected) classes("active")
                if (!enabled) disabled()
                onClick { onToggle(role.id) }
            }) { Text(role.name) }
        }
    }
}

@Composable
private fun Users(model: AccessScreenModel.Ready) {
    Panel("Users", "Everyone who can sign in, on every node") {
        DataTable(listOf("User", "Roles", "", ""), "No users yet.", model.users.isEmpty()) {
            model.users.forEach { user ->
                key(user.id) {
                    Tr {
                        Cell("User") {
                            B { Text(user.email) }
                            user.name?.let { Muted(it) }
                        }
                        Cell("Roles") { RoleChips(user.roles, user.editable) { model.onToggleRole(user.id, it) } }
                        Cell("") { Badges(user.badges) }
                        Cell("", "actions") {
                            if (user.editable) ActionButton("Remove", ButtonStyle.Danger, small = true) {
                                if (window.confirm("Remove ${user.email}? They can no longer sign in, and their devices stop working.")) {
                                    model.onRemoveUser(user.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Invitations(rows: List<InvitationRowModel>, onRemove: (String) -> Unit) {
    Panel("Open invitations") {
        DataTable(listOf("Email", "Roles", "Expires", "")) {
            rows.forEach { row ->
                key(row.id) {
                    Tr {
                        Cell("Email") { Text(row.email) }
                        Cell("Roles") { Text(row.roles) }
                        Cell("Expires") { row.expires?.let { Muted(it) } ?: Placeholder() }
                        Cell("", "actions") { ActionButton("Remove", small = true) { onRemove(row.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Devices(rows: List<DeviceRowModel>, onAction: (Int, String) -> Unit) {
    Panel("Devices", "Pangolin apps and machine clients, and the node each one relays through") {
        DataTable(listOf("Device", "Owner", "Status", "Node", ""), "No devices have connected yet.", rows.isEmpty()) {
            rows.forEach { row ->
                key(row.id) {
                    Tr {
                        Cell("Device") {
                            B { Text(row.name) }
                            row.agent?.let { Muted(it) }
                        }
                        Cell("Owner") { Text(row.owner) }
                        Cell("Status") { Pill(row.status, small = true) }
                        Cell("Node") { row.via?.let { Text(it) } ?: Placeholder() }
                        Cell("", "actions") {
                            row.actions.forEach { a ->
                                ActionButton(a.label, if (a.danger) ButtonStyle.Danger else ButtonStyle.Default, small = true) {
                                    if (!a.danger || window.confirm("${a.label} ${row.name}?")) onAction(row.id, a.action)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
