package homegap.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One event on `/api/events`. Each event's `data` is this type as JSON, with the kind in its
 * `type` field; the SSE `event` field carries the same name.
 */
@Serializable
sealed interface ServerEvent {
    /** The whole status snapshot: the first event on a connection. */
    @Serializable
    @SerialName("status")
    data class Status(val snapshot: StatusSnapshot) : ServerEvent

    /**
     * The snapshot's top-level fields that changed since the last event: [set] replaces them
     * whole and [unset] fields go back to their defaults. [at] is the new `generatedAt`.
     */
    @Serializable
    @SerialName("patch")
    data class Patch(
        val at: Long,
        val set: JsonObject = JsonObject(emptyMap()),
        val unset: List<String> = emptyList(),
    ) : ServerEvent

    /** New measurement samples, taken at [at] (epoch seconds). */
    @Serializable
    @SerialName("metrics")
    data class Metrics(
        val at: Long,
        val points: List<MetricPointDto>,
    ) : ServerEvent

    /** An Add node setup job, sent whenever one of its steps changes. */
    @Serializable
    @SerialName("job")
    data class Job(val job: SetupJobDto) : ServerEvent

    /** The homelab ↔ node speed test running now, a few times a second; null once it ends. */
    @Serializable
    @SerialName("speedtest")
    data class SpeedTestLive(val live: SpeedTestLiveDto? = null) : ServerEvent

    /** Users, invitations or devices changed: reload `/api/access`. */
    @Serializable
    @SerialName("access")
    data class Access(val revision: Long) : ServerEvent

    /** Log entries after the last one sent (or after `?after=SEQ`). */
    @Serializable
    @SerialName("log")
    data class Log(val entries: List<LogEntryDto>) : ServerEvent
}

/** The SSE event name: the same as the serial name in `type`. */
val ServerEvent.name: String
    get() = when (this) {
        is ServerEvent.Status -> "status"
        is ServerEvent.Patch -> "patch"
        is ServerEvent.Metrics -> "metrics"
        is ServerEvent.Job -> "job"
        is ServerEvent.SpeedTestLive -> "speedtest"
        is ServerEvent.Access -> "access"
        is ServerEvent.Log -> "log"
    }
