package homegap.access

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.AccessDeviceDto
import homegap.api.AccessInvitationDto
import homegap.api.AccessOverviewDto
import homegap.api.AccessRoleDto
import homegap.api.AccessUserDto
import homegap.api.CreateAccessUserRequest
import homegap.api.CreateAccessUserResponse
import homegap.clients.PangolinBootstrapClient
import homegap.clients.PangolinClient
import homegap.clients.PangolinClientEntry
import homegap.di.LocalHttp
import homegap.reconcile.Controller
import homegap.reconcile.ReadyNode
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The cluster's users and devices, managed from the dashboard through whichever node's Pangolin
 * is reachable (they all share one database). Every change is written to the reconcile log.
 */
@SingleIn(AppScope::class)
@Inject
class AccessService(private val controller: Controller, @LocalHttp private val http: HttpClient) {
    private val domain = "access"
    private val org get() = controller.config.pangolin.orgId

    suspend fun overview(): AccessOverviewDto {
        val config = controller.config
        val clientAddress = config.clientHost?.let { "https://$it" }
        if (config.cluster == null) {
            return AccessOverviewDto(false, "Users and devices are managed here once the cluster is enabled (cluster: in the config).")
        }
        val (ready, exitNodes) = controller.clusterPangolin()
            ?: return AccessOverviewDto(false, "No node's Pangolin is reachable yet.", clientAddress)
        val api = ready.api
        return try {
            coroutineScope {
                val roles = async { api.listRoles(org) }
                val users = async { api.listUsers(org) }
                val invitations = async { api.listInvitations(org) }
                val machines = async { api.listClients(org) }
                val userDevices = async { runCatching { api.listUserDevices(org) }.getOrDefault(emptyList()) }
                val roleList = roles.await()
                val names = roleList.associate { it.roleId to it.name }
                val devices = (machines.await().map { it to "machine" } + userDevices.await().map { it to "user" })
                    .distinctBy { it.first.clientId }
                    .map { (c, kind) -> async { device(api, c, kind, exitNodes) } }
                    .awaitAll()
                AccessOverviewDto(
                    available = true,
                    clientAddress = clientAddress,
                    via = ready.node.id,
                    roles = roleList.map { AccessRoleDto(it.roleId, it.name, it.isAdmin == true) },
                    users = users.await().map { u ->
                        AccessUserDto(
                            id = u.id, email = u.email, username = u.username, name = u.name,
                            roleIds = u.roles.map { it.roleId },
                            roles = u.roles.map { it.roleName ?: names[it.roleId] ?: "role ${it.roleId}" },
                            owner = u.isOwner == true, twoFactor = u.twoFactorEnabled == true, type = u.type,
                        )
                    },
                    invitations = invitations.await().map { i ->
                        AccessInvitationDto(i.inviteId, i.email, i.roles.map { it.roleName ?: names[it.roleId] ?: "role ${it.roleId}" }, i.expiresAt)
                    },
                    devices = devices.sortedWith(compareBy({ !it.online }, { it.user.orEmpty() }, { it.name })),
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            AccessOverviewDto(false, "${ready.node.id}'s Pangolin did not answer: ${e.message}", clientAddress)
        }
    }

    /** The node an online device relays through, from its own record (its exit node). */
    private suspend fun device(api: PangolinClient, c: PangolinClientEntry, kind: String, exitNodes: Map<Int, String>): AccessDeviceDto {
        val via = if (c.online == true) {
            runCatching { api.getClient(c.clientId).exitNodeId }.getOrNull()?.let { exitNodes[it] }
        } else null
        return AccessDeviceDto(
            id = c.clientId,
            name = c.name ?: c.niceId ?: "client ${c.clientId}",
            kind = if (c.userId != null || c.userEmail != null) "user" else kind,
            user = c.userEmail ?: c.username,
            agent = listOfNotNull(c.agent, c.olmVersion?.takeIf { c.agent?.contains(it) != true }).joinToString(" ").ifEmpty { null },
            online = c.online == true,
            archived = c.archived == true,
            blocked = c.blocked == true,
            viaNode = via,
        )
    }

    suspend fun createUser(req: CreateAccessUserRequest): CreateAccessUserResponse {
        val email = req.email.trim().lowercase()
        require(EMAIL.matches(email)) { "enter a valid email address" }
        require(req.roleIds.isNotEmpty()) { "pick at least one role" }
        val password = req.password?.takeIf { it.isNotEmpty() }
        password?.let { require(it.length >= 8) { "the password needs at least 8 characters" } }
        val (ready, _) = pangolin()
        val link = ready.api.createInvite(org, email, req.roleIds, INVITE_HOURS, regenerate = true)
        if (password == null) {
            controller.log.change(domain, "invited $email (invite link valid ${INVITE_HOURS / 24} days)")
            return CreateAccessUserResponse(true, "Invited $email. Send them the invite link; they choose their own password.", link)
        }
        val token = Url(link).parameters["token"] ?: throw IllegalStateException("the invite link has no token")
        val dashboard = ready.dashboardUrl ?: throw IllegalStateException("${ready.node.id}'s dashboard API is not reachable")
        try {
            PangolinBootstrapClient(http, dashboard).signUpWithInvite(email, password, token)
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            // Leave the invite for the person to complete instead.
            throw IllegalStateException("${e.message}. The invite is still open: send them the link, or remove it and try again.")
        }
        controller.log.change(domain, "created user $email")
        return CreateAccessUserResponse(true, "Created $email. They can sign in now.")
    }

    suspend fun removeUser(userId: String): String {
        val (ready, _) = pangolin()
        val user = ready.api.listUsers(org).firstOrNull { it.id == userId } ?: throw IllegalArgumentException("no such user")
        check(user.isOwner != true) { "the server admin can't be removed" }
        ready.api.removeUser(org, userId)
        controller.log.change(domain, "removed user ${user.email ?: user.username}")
        return "Removed ${user.email ?: user.username}"
    }

    suspend fun setRoles(userId: String, roleIds: List<Int>): String {
        require(roleIds.isNotEmpty()) { "a user needs at least one role" }
        val (ready, _) = pangolin()
        val user = ready.api.listUsers(org).firstOrNull { it.id == userId } ?: throw IllegalArgumentException("no such user")
        val have = user.roles.map { it.roleId }.toSet()
        // Add first: Pangolin refuses to remove a user's last role.
        (roleIds.toSet() - have).forEach { ready.api.addUserRole(userId, it) }
        (have - roleIds.toSet()).forEach { ready.api.removeUserRole(userId, it) }
        controller.log.change(domain, "set the roles of ${user.email ?: user.username}")
        return "Roles updated for ${user.email ?: user.username}"
    }

    suspend fun removeInvitation(inviteId: String): String {
        val (ready, _) = pangolin()
        ready.api.removeInvitation(org, inviteId)
        controller.log.change(domain, "removed an open invitation")
        return "Invitation removed"
    }

    /** `archive`, `unarchive`, `block`, `unblock` or `delete`. */
    suspend fun deviceAction(clientId: Int, action: String): String {
        val (ready, _) = pangolin()
        if (action == "delete") ready.api.deleteClient(clientId)
        else {
            require(action in PangolinClient.CLIENT_ACTIONS) { "unknown action $action" }
            ready.api.clientAction(clientId, action)
        }
        controller.log.change(domain, "${PAST[action] ?: action} device $clientId")
        return "Device ${PAST[action] ?: action}"
    }

    private fun pangolin(): Pair<ReadyNode, Map<Int, String>> {
        check(controller.config.cluster != null) { "users and devices are managed here only with the cluster enabled" }
        return controller.clusterPangolin() ?: throw IllegalStateException("no node's Pangolin is reachable right now")
    }

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        /** Long enough to hand the link over; the person sets their own password with it. */
        const val INVITE_HOURS = 7 * 24
        val PAST = mapOf("archive" to "archived", "unarchive" to "unarchived", "block" to "blocked", "unblock" to "unblocked", "delete" to "deleted")
    }
}
