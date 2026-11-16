package homegap.presentation.access

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.AccessDeviceDto
import homegap.api.AccessOverviewDto
import homegap.api.ActionResponse
import homegap.api.CreateAccessMachineRequest
import homegap.api.CreateAccessUserRequest
import homegap.api.Health
import homegap.presentation.common.Badge
import homegap.presentation.core.Presenter
import homegap.presentation.core.rememberEventTrigger
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.AppCoroutineScope
import homegap.presentation.data.ControllerApi
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
    @AppCoroutineScope private val appScope: CoroutineScope,
) : Presenter<AccessScreenProps, AccessScreenModel> {
    @Composable
    override fun model(props: AccessScreenProps): AccessScreenModel {
        // Bumped after a change so the lists reload at once.
        var reload by remember { mutableIntStateOf(0) }
        // Changes made from any dashboard arrive on the event stream. A device that connects shows with the slow refresh.
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
        var newPassword by remember { mutableStateOf<String?>(null) }
        var passwordOwner by remember { mutableStateOf<String?>(null) }
        var machineName by remember { mutableStateOf("") }
        var machineRoles by remember { mutableStateOf<Set<Int>?>(null) }
        var machineError by remember { mutableStateOf<String?>(null) }
        var machineSecret by remember { mutableStateOf<MachineSecretModel?>(null) }
        var creatingMachine by remember { mutableStateOf(false) }
        // Role edits wait in the Inspector until Save: user id to the roles chosen so far.
        var roleEdits by remember { mutableStateOf<Map<String, Set<Int>>>(emptyMap()) }

        val current = overview ?: return AccessScreenModel.Loading
        val address = current.clientAddress
        if (!current.available || address == null) return AccessScreenModel.Unavailable(
            message = current.note ?: "Not available.",
            clientAddress = address,
            head = PageHeadModel(
                caption = "Access",
                headline = "Access isn't available right now.",
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
                        newPassword = r.password
                        passwordOwner = null
                        if (r.password == null) {
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

        // A new machine defaults to the least-privileged role, like a new user.
        val chosenMachineRoles = machineRoles ?: current.roles.filter { !it.admin }.take(1).map { it.id }.toSet()
        val machineRequest = CreateAccessMachineRequest(machineName.trim(), chosenMachineRoles.toList())
        val latestMachine by rememberUpdatedState(machineRequest)
        val submitMachine = appScope.rememberEventTrigger {
            creatingMachine = true
            machineError = null
            try {
                runCatchingCancellable { api.createMachine(latestMachine) }
                    .onSuccess { created ->
                        actions.notify(created.message)
                        machineSecret = MachineSecretModel(latestMachine.name, created.id, created.secret)
                        machineName = ""
                        reload++
                    }
                    .onFailure { machineError = it.message }
            } finally {
                creatingMachine = false
            }
        }

        val online = current.devices.count { it.online && !it.blocked }
        val selection = props.selection
        return AccessScreenModel.Ready(
            head = PageHeadModel(
                caption = "Access",
                headline = "${plural(current.users.size, "user")}, ${plural(current.devices.size, "device")}, $online online.",
                actions = listOf(
                    ActionModel(
                        label = "+ Add machine",
                        location = Location(Route.Access, ADD_MACHINE),
                    ),
                    ActionModel(
                        label = "+ Add user",
                        style = ActionStyle.Primary,
                        location = Location(Route.Access, ADD_USER),
                    ),
                ),
            ),
            addUserOpen = selection == ADD_USER,
            inspector = when {
                selection == null || selection == ADD_USER || selection == ADD_MACHINE -> null
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
                        onBlock = { blockUser(user) { reload++ } },
                        onNewPassword = {
                            appScope.launch {
                                runCatchingCancellable { api.newUserPassword(user.id) }
                                    .onSuccess { response ->
                                        newPassword = response.password
                                        passwordOwner = user.email ?: user.username
                                        props.onOpen(Location(Route.Access, ADD_USER))
                                    }
                                    .onFailure { actions.notify("New password failed: ${it.message}", isError = true) }
                            }
                        },
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
            newUser = NewUserFormModel(
                email = email,
                setPassword = setPassword,
                password = password,
                roles = current.roles.map { RoleOptionModel(it.id, it.name, it.id in roles) },
                error = formError ?: problem.takeIf { email.isNotBlank() },
                emailError = "This is not an email address".takeIf { email.isNotBlank() && !EMAIL.matches(email.trim()) },
                rolesError = "Pick at least one role".takeIf { roles.isEmpty() },
                passwordError = PASSWORD_RULE.takeIf { setPassword && password.isNotEmpty() && password.length < MIN_PASSWORD },
                serverError = formError,
                canSubmit = problem == null && !submitting,
                submitText = if (submitting) "Creating…" else "Create user",
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
                        if (u.blocked) add(Badge(Health.DOWN, "blocked"))
                        if (u.owner) add(Badge(Health.OK, "only admin"))
                        if (u.type != null && u.type != "internal") add(Badge(Health.UNKNOWN, u.type!!))
                    },
                    editable = !u.owner,
                    devices = current.devices.count { it.user == (u.email ?: u.username) },
                )
            },
            devices = current.devices.map { it.toRow() },
            newPassword = newPassword,
            passwordOwner = passwordOwner,
            addMachineOpen = selection == ADD_MACHINE,
            newMachine = NewMachineFormModel(
                name = machineName,
                roles = current.roles.map { RoleOptionModel(it.id, it.name, it.id in chosenMachineRoles) },
                serverError = machineError,
                canSubmit = machineName.isNotBlank() && chosenMachineRoles.isNotEmpty() && !creatingMachine,
                submitText = if (creatingMachine) "Creating…" else "Create machine",
                onName = {
                    machineName = it
                    machineError = null
                },
                onToggleRole = { id ->
                    machineRoles = if (id in chosenMachineRoles) {
                        chosenMachineRoles - id
                    } else {
                        chosenMachineRoles + id
                    }
                },
                onSubmit = submitMachine::trigger,
            ),
            machineSecret = machineSecret,
            onDismissMachineSecret = { machineSecret = null },
            onDismissNewPassword = {
                newPassword = null
                passwordOwner = null
            },
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
            onDeviceAction = { id, action ->
                current.devices.firstOrNull { it.id == id }?.let { device -> deviceAction(device, action) { reload++ } }
            },
        )
    }

    private fun removeUser(id: String, name: String, onDone: () -> Unit) {
        confirmations.ask(
            ConfirmModel(
                title = "Remove $name?",
                text = "$name can no longer sign in, and their devices are disconnected and removed.",
                confirmLabel = "Remove user",
                danger = true,
            )
        ) {
            actions.run("Remove user") { api.removeUser(id).also { onDone() } }
        }
    }

    private fun blockUser(user: homegap.api.AccessUserDto, onDone: () -> Unit) {
        val name = user.email ?: user.username
        if (user.blocked) {
            actions.run("Unblock user") { api.userAction(user.id, "unblock").also { onDone() } }
            return
        }
        confirmations.ask(
            ConfirmModel(
                title = "Block $name?",
                text = "$name cannot sign in, and their devices are disconnected, until you unblock them.",
                confirmLabel = "Block $name",
                danger = true,
            )
        ) {
            actions.run("Block user") { api.userAction(user.id, "block").also { onDone() } }
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
                    text = "The device is removed. To connect again it has to sign in again.",
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
        onBlock: () -> Unit,
        onNewPassword: () -> Unit,
    ): InspectorModel = InspectorModel(
        kind = "Person",
        title = user.name ?: user.email ?: user.username,
        status = listOfNotNull(
            // The title is the address when the user has no name.
            user.email.takeIf { user.name != null },
            "blocked".takeIf { user.blocked },
            "only admin".takeIf { user.owner },
        ).joinToString(" · "),
        health = if (user.blocked) Health.DOWN else Health.OK,
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
            // The only admin keeps its roles and its access. A new password is always possible.
            user.owner -> listOf(ActionModel(label = "New password", onClick = onNewPassword))
            dirty -> listOf(
                ActionModel(label = "Cancel", onClick = onCancel),
                ActionModel(label = "Save", style = ActionStyle.Primary, onClick = onSave),
            )

            else -> listOf(
                ActionModel(label = "New password", onClick = onNewPassword),
                ActionModel(label = if (user.blocked) "Unblock" else "Block", onClick = onBlock),
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
            add(DeviceActionModel("Delete", "delete", danger = true))
        },
    )

    companion object {
        /** The selection that opens the Add user form. */
        const val ADD_USER = "add-user"

        /** The selection that opens the Add machine form. */
        const val ADD_MACHINE = "add-machine"
        private val REFRESH = 60.seconds
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private const val MIN_PASSWORD = 12
        private const val PASSWORD_RULE = "The password needs at least 12 characters"

        /** Why [request] can't be sent yet, or null. */
        fun validate(request: CreateAccessUserRequest, setPassword: Boolean): String? = when {
            !EMAIL.matches(request.email) -> "Enter the person's email address"
            request.roleIds.isEmpty() -> "Pick at least one role"
            setPassword && (request.password?.length ?: 0) < MIN_PASSWORD -> PASSWORD_RULE
            else -> null
        }
    }
}
