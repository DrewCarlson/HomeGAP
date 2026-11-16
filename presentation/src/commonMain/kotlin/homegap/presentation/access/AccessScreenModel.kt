package homegap.presentation.access

import androidx.compose.runtime.Immutable
import homegap.presentation.common.Badge
import homegap.presentation.core.ScreenModel
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.QrCode
import homegap.presentation.ui.PageHeadModel

sealed interface AccessScreenModel : ScreenModel {
    data object Loading : AccessScreenModel

    /** The sign-in has no public address yet. */
    @Immutable
    data class Unavailable(
        val message: String,
        val clientAddress: String?,
        override val head: PageHeadModel? = null,
    ) : AccessScreenModel

    @Immutable
    data class Ready(
        override val head: PageHeadModel,
        /** Whether the Add user form is open. */
        val addUserOpen: Boolean,
        override val inspector: InspectorModel?,
        /** What to put into the apps. */
        val clientAddress: String,
        /** The address as a QR code, for phones. */
        val qr: QrCode?,
        /** A note under the app address, or empty. */
        val via: String,
        val newUser: NewUserFormModel,
        val users: List<UserRowModel>,
        val invitations: List<InvitationRowModel>,
        val devices: List<DeviceRowModel>,
        /** The password that HomeGAP made for a user, to hand over. It shows once. */
        val inviteLink: String?,
        /** The user that [inviteLink] is for, if it is a new password of a user that exists. */
        val passwordOwner: String? = null,
        /** Whether the Add machine form is open. */
        val addMachineOpen: Boolean = false,
        val newMachine: NewMachineFormModel,
        /** The id and the secret of the machine just created, to hand over. They show once. */
        val machineSecret: MachineSecretModel? = null,
        val onDismissMachineSecret: () -> Unit = {},
        val onDismissInviteLink: () -> Unit,
        val onRemoveUser: (userId: String) -> Unit,
        val onToggleRole: (userId: String, roleId: Int) -> Unit,
        val onRemoveInvitation: (inviteId: String) -> Unit,
        /** A new invite link for an open invitation, shown to copy. */
        val onReissueInvitation: (inviteId: String) -> Unit,
        val onDeviceAction: (deviceId: Int, action: String) -> Unit,
    ) : AccessScreenModel
}

@Immutable
data class RoleOptionModel(
    val id: Int,
    val name: String,
    val selected: Boolean
)

@Immutable
data class NewUserFormModel(
    val email: String,
    /** Set a password now. If not, HomeGAP makes one and shows it once. */
    val setPassword: Boolean,
    val password: String,
    val roles: List<RoleOptionModel>,
    /** The first thing that stops the form, or what the orchestrator answered. */
    val error: String?,
    /** The problem of each field, shown under it. A field that is still empty shows none. */
    val emailError: String? = null,
    val rolesError: String? = null,
    val passwordError: String? = null,
    /** What the orchestrator answered when it refused the user. */
    val serverError: String? = null,
    val canSubmit: Boolean,
    val submitText: String,
    val onEmail: (String) -> Unit,
    val onSetPassword: (Boolean) -> Unit,
    val onPassword: (String) -> Unit,
    val onToggleRole: (Int) -> Unit,
    val onSubmit: () -> Unit,
)

@Immutable
data class NewMachineFormModel(
    val name: String,
    val roles: List<RoleOptionModel>,
    /** What the orchestrator answered when it refused the machine. */
    val serverError: String?,
    val canSubmit: Boolean,
    val submitText: String,
    val onName: (String) -> Unit,
    val onToggleRole: (Int) -> Unit,
    val onSubmit: () -> Unit,
)

@Immutable
data class MachineSecretModel(
    val name: String,
    val id: String,
    val secret: String,
)

@Immutable
data class UserRowModel(
    val id: String,
    val email: String,
    val name: String?,
    val roles: List<RoleOptionModel>,
    val badges: List<Badge>,
    /** The server admin can't be removed or have its roles changed here. */
    val editable: Boolean,
    val devices: Int = 0,
    val location: Location = Location(Route.Access, "user:$id"),
)

@Immutable
data class InvitationRowModel(
    val id: String,
    val email: String,
    val roles: String,
    val expires: String?
)

@Immutable
data class DeviceRowModel(
    val id: Int,
    val name: String,
    /** The owner's email, or "Machine client". */
    val owner: String,
    val agent: String?,
    val status: Badge,
    /** "via na1" while online. */
    val via: String?,
    val actions: List<DeviceActionModel>,
    val location: Location = Location(Route.Access, "device:$id"),
)

@Immutable
data class DeviceActionModel(
    val label: String,
    val action: String,
    val danger: Boolean = false
)
