package homegap.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A shell on a node that the dashboard opened. It runs on the orchestrator until someone closes it. */
@Serializable
data class TerminalSessionDto(
    val id: String,
    val nodeId: String,
    val startedAt: Long,
    /** A browser shows this session now. */
    val attached: Boolean = false,
    /** The exit code of the shell, when it ended. */
    val exitCode: Int? = null,
)

@Serializable
data class TerminalOverviewDto(
    /** False when `dashboard.terminal` is off. */
    val enabled: Boolean,
    val sessions: List<TerminalSessionDto> = emptyList(),
)

/** A key for one WebSocket connection to a session. It works one time, for a short time. */
@Serializable
data class TerminalTicketDto(val ticket: String)

/**
 * A control message on the WebSocket of a session, as a text frame. Terminal bytes are binary
 * frames. The orchestrator sends [Hello] first, then output. It sends [Exit] when the shell ends.
 * The browser sends [Resize], and [Ack] for the output that it has drawn.
 */
@Serializable
sealed interface TerminalMessage {
    /** [offset] is the position, in the output of the session, of the first byte that follows. */
    @Serializable
    @SerialName("hello")
    data class Hello(val offset: Long) : TerminalMessage

    @Serializable
    @SerialName("exit")
    data class Exit(val code: Int) : TerminalMessage

    @Serializable
    @SerialName("resize")
    data class Resize(val rows: Int, val columns: Int) : TerminalMessage

    /** The browser has drawn the output up to [offset]. */
    @Serializable
    @SerialName("ack")
    data class Ack(val offset: Long) : TerminalMessage
}
