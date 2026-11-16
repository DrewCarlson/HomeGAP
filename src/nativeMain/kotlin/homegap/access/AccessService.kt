package homegap.access

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.AccessDeviceDto
import homegap.api.AccessOverviewDto
import homegap.api.AccessRoleDto
import homegap.api.AccessUserDto
import homegap.api.CreateAccessMachineRequest
import homegap.api.CreateAccessMachineResponse
import homegap.api.CreateAccessUserRequest
import homegap.api.CreateAccessUserResponse
import homegap.config.vpnDir
import homegap.identity.IdentityStore
import homegap.identity.Signed
import homegap.identity.User
import homegap.platform.Files
import homegap.provision.Mesh
import homegap.reconcile.Controller
import homegap.signin.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The users, machines and devices of HomeGAP's own sign-in, for the Access page of the dashboard.
 *
 * The users and machines are in the [IdentityStore] of the homelab. The devices belong to the
 * sign-in server, which writes its list and the connected devices to the folder of the VPN. The
 * orchestrator reads them there. A change to a device goes through the signed directory, which
 * each server obeys. The reconcile log gets each change.
 */
@SingleIn(AppScope::class)
@Inject
class AccessService(private val controller: Controller, private val identity: IdentityStore) {
    private val domain = "access"
    private val _revision = MutableStateFlow(0L)

    /** Grows after each change from the dashboard. The event stream then tells each dashboard to load again. */
    val revision: StateFlow<Long> = _revision

    /** Marks that users or devices changed. */
    fun changed() {
        _revision.value++
    }

    fun overview(): AccessOverviewDto {
        val config = controller.config
        val address = config.signIn.url ?: return AccessOverviewDto(
            available = false,
            note = "Set sign_in.url in the config: it is the address that the apps connect to.",
        )
        val users = identity.users()
        val devices = devices()
        identity.forgetDevices(devices.map { it.id }.toSet())
        val roles = roleNames(users)
        val admins = users.filter { IdentityStore.ADMIN_ROLE in it.roles && !it.blocked }
        return AccessOverviewDto(
            available = true,
            clientAddress = address,
            roles = roles.map { AccessRoleDto(roleId(it), it, admin = it == IdentityStore.ADMIN_ROLE) },
            users = users.sortedBy { it.email }.map { user ->
                // HomeGAP keeps the last admin: with no admin, no person can use each service.
                user.toDto(owner = admins.singleOrNull()?.id == user.id)
            },
            devices = deviceRows(devices, users),
        )
    }

    /** Each role that a user, a machine, a service or the VPN names. Admin and the default role are always there. */
    private fun roleNames(users: List<User>): List<String> {
        val config = controller.config
        val used = buildSet {
            add(IdentityStore.ADMIN_ROLE)
            add(IdentityStore.DEFAULT_ROLE)
            users.forEach { addAll(it.roles) }
            identity.machines().forEach { addAll(it.roles) }
            controller.desired?.services?.forEach { addAll(it.roles) }
            addAll(config.vpn.exitNodes.roles)
            addAll(config.vpn.tunnelDns.roles)
        }
        return used.sortedWith(compareBy({ it != IdentityStore.ADMIN_ROLE }, { it }))
    }

    private fun User.toDto(owner: Boolean) = AccessUserDto(
        id = id,
        email = email,
        username = email,
        name = name.ifBlank { null },
        roleIds = roles.map { roleId(it) },
        roles = roles,
        owner = owner,
        type = "internal",
        blocked = blocked,
    )

    /** The devices of the sign-in server: the apps of the users and the machines that connected. */
    private fun devices(): List<Device> {
        val text = Files.readTextOrNull("${controller.config.vpnDir}/${Mesh.VPN_DEVICES_FILE}") ?: return emptyList()
        return runCatching { Signed.JSON.decodeFromString<List<Device>>(text) }.getOrDefault(emptyList())
    }

    /** The tunnel addresses of the devices that are connected now. */
    private fun connected(): Set<String> {
        val text = Files.readTextOrNull("${controller.config.vpnDir}/${Mesh.VPN_PEERS_FILE}") ?: return emptySet()
        val peers = runCatching { Signed.JSON.decodeFromString<List<Mesh.VpnPeer>>(text) }.getOrDefault(emptyList())
        return peers.map { it.address }.toSet()
    }

    private fun deviceRows(devices: List<Device>, users: List<User>): List<AccessDeviceDto> {
        val online = connected()
        val byUser = users.associateBy { it.id }
        val blocked = identity.directoryView().blockedDevices.toSet()
        val apps = devices.filter { it.userId.isNotEmpty() }.map { device ->
            AccessDeviceDto(
                id = deviceId(device.id),
                name = device.name.ifBlank { "Device" },
                kind = "user",
                user = byUser[device.userId]?.email,
                agent = device.agent,
                viaNode = device.node.takeIf { device.address in online },
                lastSeen = device.lastSeen,
                online = device.address in online,
                blocked = device.id in blocked || byUser[device.userId]?.blocked == true,
            )
        }
        // A machine is in the list also before its first connection.
        val machines = identity.machines().map { machine ->
            val device = devices.firstOrNull { it.id == machine.id }
            val connected = device != null && device.address in online
            AccessDeviceDto(
                id = deviceId(machine.id),
                name = machine.name,
                kind = "machine",
                agent = device?.agent,
                viaNode = device?.node.takeIf { connected },
                lastSeen = device?.lastSeen,
                online = connected,
                blocked = machine.blocked,
            )
        }
        return (apps + machines).sortedBy { it.name.lowercase() }
    }

    /**
     * Creates a user. With a password in [req], the user gets that one. Without it, HomeGAP makes
     * one, and the answer has it once.
     */
    fun createUser(req: CreateAccessUserRequest): CreateAccessUserResponse {
        val roles = roleNames(identity.users()).filter { roleId(it) in req.roleIds }
        require(roles.isNotEmpty()) { "pick at least one role" }
        val (user, generated) = identity.create(req.email, name = "", roles = roles)
        val chosen = req.password?.takeIf { it.isNotEmpty() }
        if (chosen != null) {
            runCatching { identity.setPassword(user.id, chosen) }.onFailure {
                identity.remove(user.id)
                throw it
            }
        }
        val message = "Created the user ${user.email} (${user.roles.joinToString()})"
        controller.log.change(domain, message)
        return CreateAccessUserResponse(true, message, password = generated.takeIf { chosen == null })
    }

    /** Creates a machine. The answer has its id and its secret once. */
    fun createMachine(req: CreateAccessMachineRequest): CreateAccessMachineResponse {
        val roles = roleNames(identity.users()).filter { roleId(it) in req.roleIds }
        require(roles.isNotEmpty()) { "pick at least one role" }
        val (machine, secret) = identity.createMachine(req.name, roles)
        val message = logged("Created the machine ${machine.name} (${machine.roles.joinToString()})")
        return CreateAccessMachineResponse(true, message, machine.id, secret)
    }

    fun removeUser(userId: String): String {
        check(admin(userId) == null) { "This is the last admin. Make another user an admin first." }
        val user = identity.remove(userId)
        return logged("Removed the user ${user.email}")
    }

    fun setRoles(userId: String, roleIds: List<Int>): String {
        val roles = roleNames(identity.users()).filter { roleId(it) in roleIds }
        require(roles.isNotEmpty()) { "a user needs at least one role" }
        check(admin(userId) == null || IdentityStore.ADMIN_ROLE in roles) {
            "This is the last admin. Make another user an admin first."
        }
        val user = identity.setRoles(userId, roles)
        return logged("Set the roles of ${user.email} to ${user.roles.joinToString()}")
    }

    /** Blocks the user or ends the block ([action] is `block` or `unblock`). */
    fun userAction(userId: String, action: String): String = when (action) {
        "block" -> {
            check(admin(userId) == null) { "This is the last admin. Make another user an admin first." }
            logged("Blocked ${identity.setBlocked(userId, true).email}")
        }
        "unblock" -> logged("Ended the block of ${identity.setBlocked(userId, false).email}")
        else -> throw IllegalArgumentException("unknown action $action")
    }

    /** Gives the user a new password. The answer has it once. */
    fun newPassword(userId: String): CreateAccessUserResponse {
        val password = identity.setPassword(userId)
        val user = identity.users().first { it.id == userId }
        return CreateAccessUserResponse(true, logged("Gave ${user.email} a new password"), password = password)
    }

    /** [action] is `block`, `unblock` or `delete`, for a device of a user or for a machine. */
    fun deviceAction(id: Int, action: String): String {
        val machine = identity.machines().firstOrNull { deviceId(it.id) == id }
        if (machine != null) {
            return when (action) {
                "block" -> logged("Blocked the machine ${identity.setMachineBlocked(machine.id, true).name}")
                "unblock" -> {
                    val name = identity.setMachineBlocked(machine.id, false).name
                    logged("Ended the block of the machine $name")
                }
                "delete" -> logged("Removed the machine ${identity.removeMachine(machine.id).name}")
                else -> throw IllegalArgumentException("unknown action $action")
            }
        }
        val device = requireNotNull(devices().firstOrNull { deviceId(it.id) == id }) { "no device $id" }
        val name = device.name.ifBlank { device.id }
        return when (action) {
            "block" -> identity.setDeviceBlocked(device.id, true).let { logged("Blocked the device $name") }
            "unblock" -> {
                identity.setDeviceBlocked(device.id, false)
                logged("Ended the block of the device $name")
            }
            "delete" -> identity.removeDevice(device.id).let { logged("Removed the device $name") }
            else -> throw IllegalArgumentException("unknown action $action")
        }
    }

    /** The user [userId] if that user is the only admin who is not blocked. */
    private fun admin(userId: String): User? {
        val admins = identity.users().filter { IdentityStore.ADMIN_ROLE in it.roles && !it.blocked }
        return admins.singleOrNull()?.takeIf { it.id == userId }
    }

    private fun logged(message: String): String {
        controller.log.change(domain, message)
        return message
    }

    companion object {
        /** The page names a role by a number. The number of a role never changes, because it comes from the name. */
        fun roleId(name: String): Int = name.hashCode() and Int.MAX_VALUE

        /** The page names a device by a number, from its id. */
        fun deviceId(id: String): Int = id.hashCode() and Int.MAX_VALUE
    }
}
