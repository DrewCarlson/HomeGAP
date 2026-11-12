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
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * The cluster's users and devices, managed from the dashboard through whichever node's Pangolin
 * is reachable (they all share one database). Every change is written to the reconcile log.
 */
@SingleIn(AppScope::class)
@Inject
class AccessService(private val controller: Controller, @LocalHttp private val http: HttpClient) {
    private val domain = "access"
    private val org get() = controller.config.pangolin.orgId

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The removals that wait for their delay: user id to the message and the undo id. */
    private val removals = mutableMapOf<String, Pair<String, String>>()
    private val removalLock = kotlinx.atomicfu.locks.SynchronizedObject()

    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Grows after every change made from the dashboard; the event stream tells dashboards to reload. */
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = _revision

    /** Marks that users, invitations or devices changed. */
    fun changed() {
        _revision.value++
    }

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
            // Pangolin keeps the last ping in epoch seconds.
            lastSeen = c.lastPing?.let { if (it < 100_000_000_000L) it * 1000 else it },
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

    /**
     * Removes [userId] after [REMOVE_DELAY], so the removal can be undone until then. Returns the
     * message and the undo id.
     */
    suspend fun removeUser(userId: String): Pair<String, String> {
        // A second click must not start a second removal: an undo would stop only one of them.
        synchronized(removalLock) { removals[userId] }?.let { return it }
        val (ready, _) = pangolin()
        val user = ready.api.listUsers(org).firstOrNull { it.id == userId } ?: throw IllegalArgumentException("no such user")
        check(user.isOwner != true) { "the server admin can't be removed" }
        val name = user.email ?: user.username
        val removal = scope.launch {
            delay(REMOVE_DELAY)
            runCatching {
                pangolin().first.api.removeUser(org, userId)
                controller.log.change(domain, "removed user $name")
            }.onFailure {
                if (it is CancellationException) throw it
                controller.log.error(domain, "could not remove $name: ${it.message}")
            }
            synchronized(removalLock) { removals.remove(userId) }
            changed()
        }
        val undo = controller.offerUndo(
            ttlMs = REMOVE_DELAY.inWholeMilliseconds,
        ) {
            removal.cancel()
            synchronized(removalLock) { removals.remove(userId) }
            controller.log.change(domain, "kept $name (removal undone)")
            "Kept $name"
        }
        return ("Removing $name in ${REMOVE_DELAY.inWholeSeconds} s" to undo).also { synchronized(removalLock) { removals[userId] = it } }
    }

    /**
     * A new invite link for an open invitation, with the same email and roles; the old link stops
     * working.
     */
    suspend fun reissueInvitation(inviteId: String): CreateAccessUserResponse {
        val (ready, _) = pangolin()
        val invite = ready.api.listInvitations(org).firstOrNull { it.inviteId == inviteId }
            ?: throw IllegalArgumentException("no such invitation")
        val roles = ready.api.listRoles(org)
        val roleIds = invite.roles.map { it.roleId }.filter { it > 0 }.ifEmpty {
            invite.roles.mapNotNull { role -> roles.firstOrNull { it.name == role.roleName }?.roleId }
        }
        require(roleIds.isNotEmpty()) { "the invitation has no roles to keep" }
        val link = ready.api.createInvite(org, invite.email, roleIds, INVITE_HOURS, regenerate = true)
        controller.log.change(domain, "re-issued the invite link for ${invite.email}")
        changed()
        return CreateAccessUserResponse(true, "New invite link for ${invite.email}, valid ${INVITE_HOURS / 24} days.", link)
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
        require(INVITE_ID.matches(inviteId)) { "not an invitation id" }
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
        val INVITE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        /** How long a removal of a person waits, so it can be undone. */
        val REMOVE_DELAY = 30.seconds
        val PAST = mapOf("archive" to "archived", "unarchive" to "unarchived", "block" to "blocked", "unblock" to "unblocked", "delete" to "deleted")
    }
}
