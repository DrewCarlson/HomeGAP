package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ActionResponse
import homegap.api.ApiJson
import homegap.api.TerminalMessage
import homegap.api.TerminalTicketDto
import homegap.dashboard.DashboardAuth
import homegap.terminal.TerminalSession
import homegap.terminal.TerminalSessions
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.launch

/**
 * Shells on the nodes, for the dashboard: `GET /terminal` (the open sessions),
 * `POST /nodes/{id}/terminal?rows=&columns=` (a new session), `POST /terminal/{id}/ticket`
 * (a key for one WebSocket connection) and `DELETE /terminal/{id}`.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class TerminalRoutes(private val sessions: TerminalSessions) : RoutingController {
    override fun init(parent: Route) {
        parent.get("/terminal") { call.respond(sessions.overview()) }
        parent.post("/nodes/{id}/terminal") { open() }
        parent.route("/terminal/{id}") {
            post("/ticket") { call.respond(TerminalTicketDto(sessions.ticket(id()))) }
            delete {
                sessions.close(id())
                call.respond(ActionResponse(true, "Closed the terminal"))
            }
        }
    }

    private suspend fun RoutingContext.open() {
        val q = call.request.queryParameters
        call.respond(sessions.open(id(), q["rows"]?.toIntOrNull() ?: 24, q["columns"]?.toIntOrNull() ?: 80))
    }

    private fun RoutingContext.id(): String = requireNotNull(call.parameters["id"]) { "id required" }
}

/**
 * The WebSocket of a session: `GET /api/terminal/ws?ticket=`. A browser cannot send the dashboard
 * token on a WebSocket, so the ticket from `POST /terminal/{id}/ticket` is the check. The page
 * that opens the socket must be the dashboard itself.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<OpenRoutingController>())
@Inject
class TerminalSocket(private val sessions: TerminalSessions) : OpenRoutingController {
    override fun init(parent: Route, auth: DashboardAuth) {
        parent.webSocket("/api/terminal/ws") {
            val headers = call.request.headers
            if (!sameOrigin(headers[HttpHeaders.Origin], headers[HttpHeaders.Host])) {
                return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "this page cannot open a terminal"))
            }
            val session = sessions.redeem(call.request.queryParameters["ticket"])
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "the ticket is not valid"))
            serve(session)
        }
    }

    private suspend fun DefaultWebSocketServerSession.serve(session: TerminalSession) {
        val connection = Any()
        var sent = session.attach(connection)
        try {
            send(Frame.Text(ApiJson.encodeToString(TerminalMessage.serializer(), TerminalMessage.Hello(sent))))
            val output = launch {
                session.changes.collect {
                    while (session.shows(connection)) {
                        val (at, bytes) = session.output(sent)
                        if (bytes.isEmpty()) break
                        send(Frame.Binary(true, bytes))
                        sent = at + bytes.size
                    }
                    val code = session.exitCode
                    if (code != null && session.output(sent).second.isEmpty()) {
                        send(Frame.Text(ApiJson.encodeToString(TerminalMessage.serializer(), TerminalMessage.Exit(code))))
                    }
                    // A newer connection shows the session now.
                    if (!session.shows(connection)) close(CloseReason(CloseReason.Codes.NORMAL, "opened in another window"))
                }
            }
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> session.input(frame.readBytes())
                    is Frame.Text -> when (val message = runCatching { ApiJson.decodeFromString(TerminalMessage.serializer(), frame.readText()) }.getOrNull()) {
                        is TerminalMessage.Resize -> session.resize(message.rows, message.columns)
                        is TerminalMessage.Ack -> session.ack(message.offset)
                        else -> Unit
                    }
                    else -> Unit
                }
            }
            output.cancel()
        } finally {
            session.detach(connection)
        }
    }

    companion object {
        /** True if the page at [origin] is on the host that this request went to. */
        fun sameOrigin(origin: String?, host: String?): Boolean {
            if (origin == null || host == null) return false
            return origin.substringAfter("://", "").trimEnd('/').equals(host, ignoreCase = true)
        }
    }
}
