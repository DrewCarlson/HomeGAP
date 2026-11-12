package homegap.presentation.access

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.AccessDeviceDto
import homegap.api.AccessOverviewDto
import homegap.api.ActionResponse
import homegap.api.CreateAccessUserRequest
import homegap.api.Health
import homegap.presentation.common.Badge
import homegap.presentation.core.Presenter
import homegap.presentation.core.rememberEventTrigger
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.AppCoroutineScope
import homegap.presentation.data.ControllerApi
import homegap.presentation.format.TimeFormatter
import homegap.presentation.format.plural
import homegap.presentation.data.Confirmations
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.ActionStyle
import homegap.presentation.ui.ChoiceOption
import homegap.presentation.ui.ConfirmModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.InspectorSection
import homegap.presentation.ui.MarkRow
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.QrCode
import homegap.presentation.ui.Severity
import homegap.presentation.ui.StatusLineModel
import homegap.presentation.ui.StatusMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

data class AccessScreenProps(
    /** `user:<id>`, `device:<id>`, or `add-user` for the form. */
    val selection: String? = null,
    val onOpen: (Location) -> Unit = {},
    val now: Long = 0,
)

@SingleIn(AppScope::class)
@Inject
class AccessScreenPresenter(
    private val api: ControllerApi,
    private val session: homegap.presentation.data.ControllerSession,
    private val actions: ActionRunner,
    private val confirmations: Confirmations,
    private val timeFormatter: TimeFormatter,
    @AppCoroutineScope private val appScope: CoroutineScope,
) : Presenter<AccessScreenProps, AccessScreenModel> {
    @Composable
    override fun model(props: AccessScreenProps): AccessScreenModel {
        // Bumped after a change so the lists reload at once.
        var reload by remember { mutableIntStateOf(0) }
        // Changes made from any dashboard arrive on the event stream; Pangolin's own changes with the slow refresh.
        val changed by session.accessRevision.collectAsState()
        val overview by produceState<AccessOverviewDto?>(null, reload, changed) {
            while (true) {
                runCatchingCancellable { api.access() }
                    .onSuccess { value = it }
                    .onFailure { value = AccessOverviewDto(false, "Could not load users: ${it.message}") }
                delay(REFRESH)
            }
        }

        var email by remember { mutableStateOf("") }
        var setPassword by remember { mutableStateOf(true) }
        var password by remember { mutableStateOf("") }
        var chosenRoles by remember { mutableStateOf<Set<Int>?>(null) }
        var submitting by remember { mutableStateOf(false) }
        var formError by remember { mutableStateOf<String?>(null) }
        var inviteLink by remember { mutableStateOf<String?>(null) }
        // Role edits wait in the Inspector until Save: user id to the roles chosen so far.
        var roleEdits by remember { mutableStateOf<Map<String, Set<Int>>>(emptyMap()) }

        val current = overview ?: return AccessScreenModel.Loading
        val address = current.clientAddress
        if (!current.available || address == null) return AccessScreenModel.Unavailable(
            message = current.note ?: "Not available.",
            clientAddress = address,
            head = PageHeadModel(
                caption = "Access · Pangolin users and devices",
                headline = "Access isn't available right now.",
                actions = listOf(SHARE_LINKS),
            ),
        )

        // New users default to the least-privileged role.
        val roles = chosenRoles ?: current.roles.filter { !it.admin }.take(1).map { it.id }.toSet()
        val request = CreateAccessUserRequest(email.trim(), roles.toList(), password.takeIf { setPassword })
        val problem = validate(request, setPassword)
        val latestRequest by rememberUpdatedState(request)

        val submit = appScope.rememberEventTrigger {
            submitting = true
            formError = null
            try {
                runCatchingCancellable { api.createUser(latestRequest) }
                    .onSuccess { r ->
                        actions.notify(r.message)
                        inviteLink = r.inviteLink
                        if (r.inviteLink == null) {
                            props.onOpen(Location(Route.Access))
                        }
                        email = ""
                        password = ""
                        reload++
                    }
                    .onFailure { formError = it.message }
            } finally {
                submitting = false
            }
        }

        fun change(label: String, call: suspend () -> ActionResponse) = actions.run(label) { call().also { reload++ } }

        val online = current.devices.count { it.online && !it.blocked }
        val expiring = current.invitations.filter { it.expiresAt != null }.minByOrNull { it.expiresAt ?: Long.MAX_VALUE }
        val selection = props.selection
        return AccessScreenModel.Ready(
            head = PageHeadModel(
                caption = "Access · Pangolin users and devices",
                headline = "${plural(current.users.size, "user")}, ${plural(current.devices.size, "device")}, $online online.",
                statusLine = expiring?.let { invite ->
                    StatusLineModel(
                        listOf(
                            StatusMessage(
                                severity = Severity.Info,
                                text = "${plural(current.invitations.size, "open invitation")}, ${invite.email}'s expires ${invite.expiresAt?.let { expiresIn(it, props.now) }}.",
                            )
                        )
                    )
                },
                actions = listOf(
                    SHARE_LINKS,
                    ActionModel(
                        label = "+ Add user",
                        style = ActionStyle.Primary,
                        location = Location(Route.Access, ADD_USER),
                    ),
                ),
            ),
            addUserOpen = selection == ADD_USER,
            inspector = when {
                selection == null || selection == ADD_USER -> null
                selection.startsWith("user:") -> current.users.firstOrNull { "user:${it.id}" == selection }?.let { user ->
                    val saved = user.roleIds.toSet()
                    val chosen = roleEdits[user.id] ?: saved
                    userInspector(
                        user = user,
                        roles = current.roles.map { RoleOptionModel(it.id, it.name, it.id in chosen) },
                        dirty = chosen != saved,
                        devices = current.devices.filter { it.user == (user.email ?: user.username) },
                        onToggleRole = { roleId ->
                            roleEdits = roleEdits + (user.id to if (roleId in chosen) chosen - roleId else chosen + roleId)
                        },
                        onSave = {
                            if (chosen.isEmpty()) {
                                actions.notify("A user needs at least one role", isError = true)
                            } else {
                                change("Save roles") { api.setUserRoles(user.id, chosen.toList()) }
                                roleEdits = roleEdits - user.id
                            }
                        },
                        onCancel = { roleEdits = roleEdits - user.id },
                        onRemove = { removeUser(user.id, user.email ?: user.username) { reload++ } },
                    )
                }

                selection.startsWith("device:") -> current.devices.firstOrNull { "device:${it.id}" == selection }?.let { device ->
                    deviceInspector(
                        device = device,
                        now = props.now,
                        onAction = { action -> deviceAction(device, action) { reload++ } },
                    )
                }

                else -> null
            },
            clientAddress = address,
            qr = remember(address) { QrCode.encode(address) },
            via = "Read through ${current.via}'s Pangolin",
            newUser = NewUserFormModel(
                email = email,
                setPassword = setPassword,
                password = password,
                roles = current.roles.map { RoleOptionModel(it.id, it.name, it.id in roles) },
                error = formError ?: problem.takeIf { email.isNotBlank() },
                canSubmit = problem == null && !submitting,
                submitText = when {
                    submitting -> "Creating…"
                    setPassword -> "Create user"
                    else -> "Create invite link"
                },
                onEmail = {
                    email = it
                    formError = null
                },
                onSetPassword = { setPassword = it },
                onPassword = {
                    password = it
                    formError = null
                },
                onToggleRole = { id ->
                    chosenRoles = if (id in roles) {
                        roles - id
                    } else {
                        roles + id
                    }
                },
                onSubmit = submit::trigger,
            ),
            users = current.users.map { u ->
                UserRowModel(
                    id = u.id,
                    email = u.email ?: u.username,
                    name = u.name,
                    roles = current.roles.map { RoleOptionModel(it.id, it.name, it.id in u.roleIds) },
                    badges = buildList {
                        if (u.owner) add(Badge(Health.OK, "server admin"))
                        if (u.twoFactor) add(Badge(Health.OK, "2FA"))
                        if (u.type != null && u.type != "internal") add(Badge(Health.UNKNOWN, u.type!!))
                    },
                    editable = !u.owner,
                    devices = current.devices.count { it.user == (u.email ?: u.username) },
                )
            },
            invitations = current.invitations.map { i ->
                InvitationRowModel(
                    i.id,
                    i.email,
                    i.roles.joinToString(),
                    i.expiresAt?.let { "expires ${expiresIn(it, props.now)}" })
            },
            devices = current.devices.map { it.toRow() },
            inviteLink = inviteLink,
            onDismissInviteLink = { inviteLink = null },
            onRemoveUser = { id ->
                val user = current.users.firstOrNull { it.id == id }
                removeUser(id, user?.email ?: user?.username ?: id) { reload++ }
            },
            onToggleRole = { userId, roleId ->
                val user = current.users.firstOrNull { it.id == userId }
                if (user != null) {
                    val next = if (roleId in user.roleIds) user.roleIds - roleId else user.roleIds + roleId
                    if (next.isEmpty()) actions.notify("A user needs at least one role", isError = true)
                    else change("Change roles") { api.setUserRoles(userId, next) }
                }
            },
            onRemoveInvitation = { id ->
                val invite = current.invitations.firstOrNull { it.id == id }
                confirmations.ask(
                    ConfirmModel(
                        title = "Revoke the invitation for ${invite?.email ?: id}?",
                        text = "The link stops working. You can invite the person again later.",
                        confirmLabel = "Revoke",
                        danger = true,
                    )
                ) {
                    change("Revoke invitation") { api.removeInvitation(id) }
                }
            },
            onReissueInvitation = { id ->
                appScope.launch {
                    runCatchingCancellable { api.reissueInvitation(id) }
                        .onSuccess { response ->
                            inviteLink = response.inviteLink
                            reload++
                            props.onOpen(Location(Route.Access, ADD_USER))
                        }
                        .onFailure { actions.notify("New link failed: ${it.message}", isError = true) }
                }
            },
            onDeviceAction = { id, action ->
                current.devices.firstOrNull { it.id == id }?.let { device -> deviceAction(device, action) { reload++ } }
            },
        )
    }

    private fun removeUser(id: String, name: String, onDone: () -> Unit) {
        confirmations.ask(
            ConfirmModel(
                title = "Remove $name?",
                text = "$name can no longer sign in to the Pangolin apps, and their devices are disconnected.",
                confirmLabel = "Remove $name",
                consequences = listOf(
                    "Their devices stop connecting",
                    "Undo is offered for 30 s, until the removal goes through",
                ),
                typeToConfirm = name,
                danger = true,
            )
        ) {
            actions.run("Remove user") { api.removeUser(id).also { onDone() } }
        }
    }

    private fun deviceAction(device: AccessDeviceDto, action: String, onDone: () -> Unit) {
        val run = { actions.run("Device $action") { api.deviceAction(device.id, action).also { onDone() } } }
        when (action) {
            "block" -> confirmations.ask(
                ConfirmModel(
                    title = "Block ${device.name}?",
                    text = "Blocking stops this device connecting until you unblock it. The person can still sign in on their other devices.",
                    confirmLabel = "Block ${device.name}",
                    danger = true,
                ),
                onConfirm = { run() },
            )

            "delete" -> confirmations.ask(
                ConfirmModel(
                    title = "Delete ${device.name}?",
                    text = "The device is removed from Pangolin. To connect again it has to sign in again.",
                    confirmLabel = "Delete ${device.name}",
                    typeToConfirm = device.name.ifBlank { "delete" },
                    danger = true,
                ),
                onConfirm = { run() },
            )

            else -> run()
        }
    }

    private fun userInspector(
        user: homegap.api.AccessUserDto,
        roles: List<RoleOptionModel>,
        dirty: Boolean,
        devices: List<AccessDeviceDto>,
        onToggleRole: (Int) -> Unit,
        onSave: () -> Unit,
        onCancel: () -> Unit,
        onRemove: () -> Unit,
    ): InspectorModel = InspectorModel(
        kind = "Person",
        title = user.name ?: user.email ?: user.username,
        status = listOfNotNull(user.email, "server admin".takeIf { user.owner }).joinToString(" · "),
        health = Health.OK,
        sections = listOfNotNull(
            if (user.owner) {
                null
            } else {
                InspectorSection.Choices(
                    title = "Roles",
                    options = roles.map { ChoiceOption(it.id.toString(), it.name, it.selected) },
                    onToggle = { id -> id.toIntOrNull()?.let(onToggleRole) },
                )
            },
            if (dirty) InspectorSection.Note(text = "Unsaved role changes.", severity = Severity.Warn) else null,
            InspectorSection.Facts(
                title = null,
                rows = listOfNotNull(
                    FactRow("Roles", user.roles.joinToString(", ").ifEmpty { "none" }),
                    FactRow("Two-factor", if (user.twoFactor) "on" else "off"),
                    user.type?.let { FactRow("Sign-in", it) },
                ),
            ),
            InspectorSection.Marks(
                title = "Devices · ${devices.size}",
                rows = devices.map { device ->
                    MarkRow(
                        health = if (device.online) Health.OK else Health.UNKNOWN,
                        name = device.name,
                        detail = listOfNotNull(device.agent, device.viaNode?.let { "via $it" }).joinToString(" · "),
                        value = if (device.online) "online" else "offline",
                    )
                },
            ),
        ),
        actions = when {
            user.owner -> emptyList()
            dirty -> listOf(
                ActionModel(label = "Cancel", onClick = onCancel),
                ActionModel(label = "Save", style = ActionStyle.Primary, onClick = onSave),
            )

            else -> listOf(
                ActionModel(
                    label = "Remove",
                    style = ActionStyle.Danger,
                    onClick = onRemove,
                ),
            )
        },
    )

    private fun deviceInspector(device: AccessDeviceDto, now: Long, onAction: (String) -> Unit): InspectorModel = InspectorModel(
        kind = "Device",
        title = device.name,
        status = when {
            device.blocked -> "blocked"
            device.archived -> "archived"
            device.online -> "online" + device.viaNode?.let { " · via $it" }.orEmpty()
            else -> "offline"
        },
        health = when {
            device.blocked -> Health.DOWN
            device.online -> Health.OK
            else -> Health.UNKNOWN
        },
        sections = listOf(
            InspectorSection.Facts(
                title = null,
                rows = listOfNotNull(
                    FactRow("Owner", device.user ?: if (device.kind == "machine") "Machine client" else "Unknown"),
                    device.agent?.let { FactRow("App", it) },
                    device.lastSeen?.let { FactRow("Last seen", homegap.presentation.format.ago(it, now)) },
                    FactRow("Kind", device.kind),
                    device.viaNode?.let { FactRow("Relays through", it) },
                ),
            ),
        ),
        actions = listOf(
            if (device.blocked) {
                ActionModel(label = "Unblock", onClick = { onAction("unblock") })
            } else {
                ActionModel(label = "Block", style = ActionStyle.Danger, onClick = { onAction("block") })
            },
            if (device.archived) {
                ActionModel(label = "Restore", onClick = { onAction("unarchive") })
            } else {
                ActionModel(label = "Archive", onClick = { onAction("archive") })
            },
            ActionModel(label = "Delete", style = ActionStyle.Danger, onClick = { onAction("delete") }),
        ),
    )

    private fun AccessDeviceDto.toRow() = DeviceRowModel(
        id = id,
        name = name,
        owner = user ?: if (kind == "machine") "Machine client" else "Unknown user",
        agent = agent,
        status = when {
            blocked -> Badge(Health.DOWN, "blocked")
            archived -> Badge(Health.UNKNOWN, "archived")
            online -> Badge(Health.OK, "online")
            else -> Badge(Health.UNKNOWN, "offline")
        },
        via = viaNode?.let { "via $it" },
        actions = buildList {
            add(
                if (blocked) DeviceActionModel("Unblock", "unblock") else DeviceActionModel(
                    "Block",
                    "block",
                    danger = true
                )
            )
            add(if (archived) DeviceActionModel("Restore", "unarchive") else DeviceActionModel("Archive", "archive"))
            add(DeviceActionModel("Delete", "delete", danger = true))
        },
    )

    companion object {
        /** The selection that opens the Add user form. */
        const val ADD_USER = "add-user"
        private val REFRESH = 60.seconds
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /** Why [request] can't be sent yet, or null. */
        fun validate(request: CreateAccessUserRequest, setPassword: Boolean): String? = when {
            !EMAIL.matches(request.email) -> "Enter the person's email address"
            request.roleIds.isEmpty() -> "Pick at least one role"
            setPassword && (request.password?.length ?: 0) < 8 -> "The password needs at least 8 characters"
            else -> null
        }
    }
}

/** Opens the read-only share links of the dashboard itself. */
private val SHARE_LINKS = ActionModel(
    label = "Share links",
    location = Location(Route.ShareLinks),
)

/** "in 6 days", "in 5 h", "in 20 min", or "now" once it has passed. Days are rounded. */
internal fun expiresIn(at: Long, now: Long): String {
    val minutes = (at - now) / 60_000
    return when {
        minutes <= 0 -> "now"
        minutes < 90 -> "in $minutes min"
        minutes < 48 * 60 -> "in ${minutes / 60} h"
        else -> "in ${(minutes + 12 * 60) / (24 * 60)} days"
    }
}
