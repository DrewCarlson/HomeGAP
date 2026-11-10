package homegap.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One event on `/api/events`. Each event's `data` is this type as JSON, with the kind in its
 * `type` field; the SSE `event` field carries the same name.
 */
@Serializable
sealed interface ServerEvent {
    /** The status snapshot, sent when it changed. */
    @Serializable
    @SerialName("status")
    data class Status(val snapshot: StatusSnapshot) : ServerEvent

    /** Log entries after the last one sent (or after `?after=SEQ`). */
    @Serializable
    @SerialName("log")
    data class Log(val entries: List<LogEntryDto>) : ServerEvent
}

/** The SSE event name: the same as the serial name in `type`. */
val ServerEvent.name: String
    get() = when (this) {
        is ServerEvent.Status -> "status"
        is ServerEvent.Log -> "log"
    }
