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
import homegap.clients.PangolinUser
import homegap.clients.PangolinUserRole
import homegap.di.LocalHttp
import homegap.platform.retrying
import homegap.platform.runCatchingCancellable
import homegap.reconcile.Controller
import homegap.reconcile.ReadyNode
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * The users and devices of the cluster. The dashboard manages them through the Pangolin of a node
 * that answers (all nodes share one database). The reconcile log gets each change.
 */
@SingleIn(AppScope::class)
@Inject
class AccessService(private val controller: Controller, @LocalHttp private val http: HttpClient) {
    private val domain = "access"
    private val org get() = controller.config.pangolin.orgId

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The removals that wait for their delay: user id to the message and the undo id. */
    private val removals = mutableMapOf<String, Pair<String, String>>()
    private val removalLock = SynchronizedObject()

    private val _revision = MutableStateFlow(0L)

    /** Grows after each change from the dashboard. The event stream then tells each dashboard to load again. */
    val revision: StateFlow<Long> = _revision

    /** Marks that users, invitations or devices changed. */
    fun changed() {
        _revision.value++
    }

    suspend fun overview(): AccessOverviewDto {
        val config = controller.config
        val clientAddress = config.clientHost?.let { "https://$it" }
        if (config.cluster == null) {
            return AccessOverviewDto(
                available = false,
                note = "Users and devices are managed here once the cluster is enabled (cluster: in the config).",
            )
        }
        val (ready, exitNodes) = controller.clusterPangolin()
            ?: return AccessOverviewDto(false, "No node's Pangolin is reachable yet.", clientAddress)
        return runCatchingCancellable { overview(ready, exitNodes, clientAddress) }.getOrElse { e ->
            AccessOverviewDto(false, "${ready.node.id}'s Pangolin did not answer: ${e.message}", clientAddress)
        }
    }

    private suspend fun overview(
        ready: ReadyNode,
        exitNodes: Map<Int, String>,
        clientAddress: String?,
    ): AccessOverviewDto = coroutineScope {
        val api = ready.api
        val roles = async { api.listRoles(org) }
        val users = async { api.listUsers(org) }
        val invitations = async { api.listInvitations(org) }
        val machines = async { api.listClients(org) }
        val userDevices = async { runCatchingCancellable { api.listUserDevices(org) }.getOrDefault(emptyList()) }
        val roleList = roles.await()
        val roleNames = roleList.associate { it.roleId to it.name }
        val devices = (machines.await().map { it to "machine" } + userDevices.await().map { it to "user" })
            .distinctBy { it.first.clientId }
            .map { (client, kind) -> async { device(api, client, kind, exitNodes) } }
            .awaitAll()
        AccessOverviewDto(
            available = true,
            clientAddress = clientAddress,
            via = ready.node.id,
            roles = roleList.map { AccessRoleDto(it.roleId, it.name, it.isAdmin == true) },
            users = users.await().map { user(it, roleNames) },
            invitations = invitations.await().map { invitation ->
                AccessInvitationDto(
                    id = invitation.inviteId,
                    email = invitation.email,
                    roles = invitation.roles.map { roleName(it, roleNames) },
                    expiresAt = invitation.expiresAt,
                )
            },
            devices = devices.sortedWith(compareBy({ !it.online }, { it.user.orEmpty() }, { it.name })),
        )
    }

    private fun user(user: PangolinUser, roleNames: Map<Int, String>) = AccessUserDto(
        id = user.id,
        email = user.email,
        username = user.username,
        name = user.name,
        roleIds = user.roles.map { it.roleId },
        roles = user.roles.map { roleName(it, roleNames) },
        owner = user.isOwner == true,
        twoFactor = user.twoFactorEnabled == true,
        type = user.type,
    )

    private fun roleName(role: PangolinUserRole, roleNames: Map<Int, String>): String =
        role.roleName ?: roleNames[role.roleId] ?: "role ${role.roleId}"

    /** The node that an online device relays through. The record of the device gives it (its exit node). */
    private suspend fun device(
        api: PangolinClient,
        client: PangolinClientEntry,
        kind: String,
        exitNodes: Map<Int, String>,
    ): AccessDeviceDto {
        val viaNode = if (client.online == true) {
            runCatchingCancellable { api.getClient(client.clientId).exitNodeId }.getOrNull()?.let { exitNodes[it] }
        } else {
            null
        }
        val agent = listOfNotNull(client.agent, client.olmVersion?.takeIf { client.agent?.contains(it) != true })
            .joinToString(" ")
            .ifEmpty { null }
        return AccessDeviceDto(
            id = client.clientId,
            name = client.name ?: client.niceId ?: "client ${client.clientId}",
            kind = if (client.userId != null || client.userEmail != null) "user" else kind,
            user = client.userEmail ?: client.username,
            agent = agent,
            online = client.online == true,
            archived = client.archived == true,
            blocked = client.blocked == true,
            viaNode = viaNode,
            // Pangolin keeps the last ping in epoch seconds.
            lastSeen = client.lastPing?.let { if (it < FIRST_EPOCH_MS) it * 1000 else it },
        )
    }

    suspend fun createUser(req: CreateAccessUserRequest): CreateAccessUserResponse {
        val email = req.email.trim().lowercase()
        require(EMAIL.matches(email)) { "enter a valid email address" }
        require(req.roleIds.isNotEmpty()) { "pick at least one role" }
        val password = req.password?.takeIf { it.isNotEmpty() }
        password?.let { require(it.length >= MIN_PASSWORD_LENGTH) { "the password needs at least 8 characters" } }
        val (ready, _) = pangolin()
        val link = ready.api.createInvite(org, email, req.roleIds, INVITE_HOURS, regenerate = true)
        if (password == null) {
            controller.log.change(domain, "invited $email (invite link valid ${INVITE_HOURS / 24} days)")
            return CreateAccessUserResponse(
                ok = true,
                message = "Invited $email. Send them the invite link; they choose their own password.",
                inviteLink = link,
            )
        }
        val token = checkNotNull(Url(link).parameters["token"]) { "the invite link has no token" }
        val dashboard = checkNotNull(ready.dashboardUrl) { "${ready.node.id}'s dashboard API is not reachable" }
        runCatchingCancellable {
            PangolinBootstrapClient(http, dashboard).signUpWithInvite(email, password, token)
        }.onFailure { e ->
            // The invite stays, so the person can complete it.
            throw IllegalStateException(
                "${e.message}. The invite is still open: send them the link, or remove it and try again.",
            )
        }
        controller.log.change(domain, "created user $email")
        return CreateAccessUserResponse(true, "Created $email. They can sign in now.")
    }

    /**
     * Removes [userId] after [REMOVE_DELAY], so the dashboard can undo the removal until then.
     * If Pangolin refuses, more attempts follow for some minutes.
     * Returns the message and the undo id.
     */
    suspend fun removeUser(userId: String): Pair<String, String> {
        // A second click must not start a second removal: an undo would stop only one of them.
        synchronized(removalLock) { removals[userId] }?.let { return it }
        val (ready, _) = pangolin()
        val user = requireNotNull(ready.api.listUsers(org).firstOrNull { it.id == userId }) { "no such user" }
        check(user.isOwner != true) { "the server admin can't be removed" }
        val name = user.email ?: user.username
        val removal = scope.launch {
            delay(REMOVE_DELAY)
            // Pangolin does not answer while the database moves to a different member.
            var warned = false
            retrying(
                attempts = REMOVE_ATTEMPTS,
                pause = REMOVE_RETRY_PAUSE,
                onRetry = { failure ->
                    if (!warned) {
                        val reason = failure.message
                        controller.log.warn(domain, "could not remove $name yet, a new attempt follows: $reason")
                    }
                    warned = true
                },
            ) {
                pangolin().first.api.removeUser(org, userId)
            }.onSuccess {
                controller.log.change(domain, "removed user $name")
            }.onFailure {
                controller.log.error(domain, "could not remove $name: ${it.message}")
            }
            synchronized(removalLock) { removals.remove(userId) }
            changed()
        }
        val undo = controller.offerUndo(ttlMs = REMOVE_DELAY.inWholeMilliseconds) {
            removal.cancel()
            synchronized(removalLock) { removals.remove(userId) }
            controller.log.change(domain, "kept $name (removal undone)")
            "Kept $name"
        }
        val answer = "Removing $name in ${REMOVE_DELAY.inWholeSeconds} s" to undo
        synchronized(removalLock) { removals[userId] = answer }
        return answer
    }

    /**
     * Makes a new invite link for an open invitation, with the same email and roles. The old link
     * stops.
     */
    suspend fun reissueInvitation(inviteId: String): CreateAccessUserResponse {
        val (ready, _) = pangolin()
        val invite = requireNotNull(ready.api.listInvitations(org).firstOrNull { it.inviteId == inviteId }) {
            "no such invitation"
        }
        val roles = ready.api.listRoles(org)
        val roleIds = invite.roles.map { it.roleId }.filter { it > 0 }.ifEmpty {
            invite.roles.mapNotNull { role -> roles.firstOrNull { it.name == role.roleName }?.roleId }
        }
        require(roleIds.isNotEmpty()) { "the invitation has no roles to keep" }
        val link = ready.api.createInvite(org, invite.email, roleIds, INVITE_HOURS, regenerate = true)
        controller.log.change(domain, "re-issued the invite link for ${invite.email}")
        changed()
        return CreateAccessUserResponse(
            ok = true,
            message = "New invite link for ${invite.email}, valid ${INVITE_HOURS / 24} days.",
            inviteLink = link,
        )
    }

    suspend fun setRoles(userId: String, roleIds: List<Int>): String {
        require(roleIds.isNotEmpty()) { "a user needs at least one role" }
        val (ready, _) = pangolin()
        val user = requireNotNull(ready.api.listUsers(org).firstOrNull { it.id == userId }) { "no such user" }
        val have = user.roles.map { it.roleId }.toSet()
        // Add first: Pangolin refuses to remove a user's last role.
        (roleIds.toSet() - have).forEach { ready.api.addUserRole(userId, it) }
        (have - roleIds.toSet()).forEach { ready.api.removeUserRole(userId, it) }
        val name = user.email ?: user.username
        controller.log.change(domain, "set the roles of $name")
        return "Roles updated for $name"
    }

    suspend fun removeInvitation(inviteId: String): String {
        require(INVITE_ID.matches(inviteId)) { "not an invitation id" }
        val (ready, _) = pangolin()
        ready.api.removeInvitation(org, inviteId)
        controller.log.change(domain, "removed an open invitation")
        return "Invitation removed"
    }

    /** [action] is `archive`, `unarchive`, `block`, `unblock` or `delete`. */
    suspend fun deviceAction(clientId: Int, action: String): String {
        val (ready, _) = pangolin()
        if (action == "delete") {
            ready.api.deleteClient(clientId)
        } else {
            require(action in PangolinClient.CLIENT_ACTIONS) { "unknown action $action" }
            ready.api.clientAction(clientId, action)
        }
        val done = PAST[action] ?: action
        controller.log.change(domain, "$done device $clientId")
        return "Device $done"
    }

    private fun pangolin(): Pair<ReadyNode, Map<Int, String>> {
        check(controller.config.cluster != null) { "users and devices are managed here only with the cluster enabled" }
        return checkNotNull(controller.clusterPangolin()) { "no node's Pangolin is reachable right now" }
    }

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        const val MIN_PASSWORD_LENGTH = 8

        /** Long enough to give the link to the person, who sets a password with it. */
        const val INVITE_HOURS = 7 * 24
        val INVITE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** The time that the removal of a person waits, so the dashboard can undo it. */
        val REMOVE_DELAY = 30.seconds

        /** A failover of the database ends in this time: 10 attempts in 5 minutes. */
        const val REMOVE_ATTEMPTS = 10
        val REMOVE_RETRY_PAUSE = 30.seconds

        /** A time in ms is not below this value. A lower value is in seconds. */
        const val FIRST_EPOCH_MS = 100_000_000_000L

        val PAST = mapOf(
            "archive" to "archived",
            "unarchive" to "unarchived",
            "block" to "blocked",
            "unblock" to "unblocked",
            "delete" to "deleted",
        )
    }
}
