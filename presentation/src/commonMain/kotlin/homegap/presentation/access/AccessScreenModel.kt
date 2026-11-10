package homegap.presentation.access

import androidx.compose.runtime.Immutable
import homegap.presentation.common.Badge
import homegap.presentation.core.ScreenModel

sealed interface AccessScreenModel : ScreenModel {
    data object Loading : AccessScreenModel

    /** No cluster, or no node's Pangolin is reachable. */
    @Immutable
    data class Unavailable(
        val message: String,
        val clientAddress: String?
    ) : AccessScreenModel

    @Immutable
    data class Ready(
        /** What to put into the Pangolin apps. */
        val clientAddress: String,
        /** "Read through na1's Pangolin". */
        val via: String,
        val newUser: NewUserFormModel,
        val users: List<UserRowModel>,
        val invitations: List<InvitationRowModel>,
        val devices: List<DeviceRowModel>,
        /** The invite link of the user just invited, to hand over. */
        val inviteLink: String?,
        val onDismissInviteLink: () -> Unit,
        val onRemoveUser: (userId: String) -> Unit,
        val onToggleRole: (userId: String, roleId: Int) -> Unit,
        val onRemoveInvitation: (inviteId: String) -> Unit,
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
    /** Set a password now (the user can sign in at once) instead of handing out an invite link. */
    val setPassword: Boolean,
    val password: String,
    val roles: List<RoleOptionModel>,
    val error: String?,
    val canSubmit: Boolean,
    val submitText: String,
    val onEmail: (String) -> Unit,
    val onSetPassword: (Boolean) -> Unit,
    val onPassword: (String) -> Unit,
    val onToggleRole: (Int) -> Unit,
    val onSubmit: () -> Unit,
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
)

@Immutable
data class DeviceActionModel(
    val label: String,
    val action: String,
    val danger: Boolean = false
)
