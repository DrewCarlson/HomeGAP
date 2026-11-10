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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

data object AccessScreenProps

@SingleIn(AppScope::class)
@Inject
class AccessScreenPresenter(
    private val api: ControllerApi,
    private val actions: ActionRunner,
    private val timeFormatter: TimeFormatter,
    @AppCoroutineScope private val appScope: CoroutineScope,
) : Presenter<AccessScreenProps, AccessScreenModel> {
    @Composable
    override fun model(props: AccessScreenProps): AccessScreenModel {
        // Bumped after a change so the lists reload at once.
        var reload by remember { mutableIntStateOf(0) }
        val overview by produceState<AccessOverviewDto?>(null, reload) {
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

        val current = overview ?: return AccessScreenModel.Loading
        val address = current.clientAddress
        if (!current.available || address == null) return AccessScreenModel.Unavailable(
            current.note ?: "Not available.", address
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

        return AccessScreenModel.Ready(
            clientAddress = address,
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
                )
            },
            invitations = current.invitations.map { i ->
                InvitationRowModel(
                    i.id,
                    i.email,
                    i.roles.joinToString(),
                    i.expiresAt?.let { "Expires ${timeFormatter.dateTime(it)}" })
            },
            devices = current.devices.map { it.toRow() },
            inviteLink = inviteLink,
            onDismissInviteLink = { inviteLink = null },
            onRemoveUser = { id -> change("Remove user") { api.removeUser(id) } },
            onToggleRole = { userId, roleId ->
                val user = current.users.firstOrNull { it.id == userId }
                if (user != null) {
                    val next = if (roleId in user.roleIds) user.roleIds - roleId else user.roleIds + roleId
                    if (next.isEmpty()) actions.notify("A user needs at least one role", isError = true)
                    else change("Change roles") { api.setUserRoles(userId, next) }
                }
            },
            onRemoveInvitation = { id -> change("Remove invitation") { api.removeInvitation(id) } },
            onDeviceAction = { id, action -> change("Device $action") { api.deviceAction(id, action) } },
        )
    }

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
        private val REFRESH = 15.seconds
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
