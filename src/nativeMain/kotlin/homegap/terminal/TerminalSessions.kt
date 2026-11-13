package homegap.terminal

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.TerminalOverviewDto
import homegap.api.TerminalSessionDto
import homegap.clients.SshPool
import homegap.clients.SshSession
import homegap.platform.Crypto
import homegap.platform.Pty
import homegap.platform.nowMillis
import homegap.reconcile.Controller
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shells that the dashboard has open on the nodes. A shell runs on the orchestrator, through
 * the SSH connection that the orchestrator already has to the node. The browser holds no key.
 *
 * A session stays when its browser goes away, so a reload shows the same shell again. It ends
 * when the shell exits, when someone closes it, when no browser used it for [IDLE_MS], or when
 * the orchestrator restarts.
 */
@SingleIn(AppScope::class)
@Inject
class TerminalSessions(private val controller: Controller, private val ssh: SshPool) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val sessions = LinkedHashMap<String, TerminalSession>()
    private val tickets = Tickets()

    val enabled: Boolean get() = controller.config.dashboard.terminal

    init {
        scope.launch {
            while (true) {
                delay(30_000)
                runCatching { sweep() }
            }
        }
    }

    fun overview(): TerminalOverviewDto = TerminalOverviewDto(enabled, synchronized(lock) { sessions.values.map { it.dto() } })

    /** Starts a shell on [nodeId] in a terminal of [rows] by [columns]. */
    suspend fun open(nodeId: String, rows: Int, columns: Int): TerminalSessionDto {
        check(enabled) { "The terminal is off (dashboard.terminal)." }
        check(!controller.dryRun) { "A terminal needs remote access (dry run is on)." }
        val node = requireNotNull(controller.config.node(nodeId)) { "unknown node $nodeId" }
        synchronized(lock) {
            check(sessions.size < MAX_TOTAL) { "There are $MAX_TOTAL terminals open. Close one first." }
            check(sessions.values.count { it.nodeId == nodeId } < MAX_PER_NODE) { "$nodeId has $MAX_PER_NODE terminals open. Close one first." }
        }
        val argv = ssh.session(node).interactive(shellCommand(controller.config.pangolin.installDir))
        val pty = withContext(Dispatchers.IO) { Pty.spawn(argv, rows, columns, env = mapOf("TERM" to "xterm-256color")) }
        val session = TerminalSession(Crypto.token(16), nodeId, pty, scope)
        synchronized(lock) { sessions[session.id] = session }
        controller.log.change(DOMAIN, "opened a terminal", nodeId)
        return session.dto()
    }

    fun session(id: String): TerminalSession? = synchronized(lock) { sessions[id] }

    /** A key for one WebSocket connection to session [id]. */
    fun ticket(id: String): String {
        requireNotNull(session(id)) { "unknown terminal $id" }
        return tickets.issue(id)
    }

    /** The session that [ticket] is for. A ticket works one time. */
    fun redeem(ticket: String?): TerminalSession? = tickets.redeem(ticket)?.let(::session)

    suspend fun close(id: String) {
        val session = synchronized(lock) { sessions.remove(id) } ?: return
        session.close()
        val minutes = (nowMillis() - session.startedAt) / 60_000
        controller.log.change(DOMAIN, "closed a terminal after $minutes min", session.nodeId)
    }

    /** Closes each session that ended, that nobody used for [IDLE_MS], or that is older than [MAX_AGE_MS]. */
    private suspend fun sweep() {
        val now = nowMillis()
        val done = synchronized(lock) {
            sessions.values.filter {
                (it.exitCode != null && !it.attached) || (!it.attached && now - it.lastActivity > IDLE_MS) || now - it.startedAt > MAX_AGE_MS
            }.map { it.id }
        }
        done.forEach { close(it) }
    }

    private fun TerminalSession.dto() = TerminalSessionDto(id, nodeId, startedAt, attached, exitCode)

    /** Single-use keys, each good for [TICKET_MS]. */
    private class Tickets {
        private val lock = SynchronizedObject()
        private val open = mutableMapOf<String, Pair<String, Long>>()

        fun issue(sessionId: String): String = synchronized(lock) {
            val now = nowMillis()
            open.entries.removeAll { it.value.second < now }
            Crypto.token(32).also { open[it] = sessionId to now + TICKET_MS }
        }

        fun redeem(ticket: String?): String? = synchronized(lock) {
            val (sessionId, until) = open.remove(ticket ?: return@synchronized null) ?: return@synchronized null
            sessionId.takeIf { nowMillis() <= until }
        }
    }

    companion object {
        const val DOMAIN = "terminal"
        const val MAX_PER_NODE = 2
        const val MAX_TOTAL = 6
        const val IDLE_MS = 15 * 60_000L
        const val MAX_AGE_MS = 8 * 3_600_000L
        const val TICKET_MS = 30_000L

        /** The shell starts in the install directory of the stack, as a login shell. */
        fun shellCommand(installDir: String): String =
            "cd ${SshSession.shQuote(installDir)} 2>/dev/null; exec \"\${SHELL:-/bin/bash}\" -l"
    }
}
