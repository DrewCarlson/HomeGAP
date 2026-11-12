package homegap.presentation.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.LogEntryDto
import homegap.api.MetricPointDto
import homegap.api.SessionDto
import homegap.api.SessionRole
import homegap.api.ServerEvent
import homegap.api.SetupJobDto
import homegap.api.SpeedTestLiveDto
import homegap.api.StatusSnapshot
import homegap.api.patched
import homegap.api.snapshotFields
import homegap.api.snapshotOf
import homegap.presentation.core.runCatchingCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.FlowPreview
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why the controller is not reachable, as far as the dashboard can tell. */
sealed interface ConnectionProblem {
    /** The API wants a dashboard token (missing or wrong). */
    data object Unauthorized : ConnectionProblem

    data class Failed(val message: String) : ConnectionProblem
}

/**
 * The live view of the controller: the latest status snapshot and the reconcile log, kept current
 * by `/api/events` while [follow] runs.
 */
@SingleIn(AppScope::class)
@Inject
class ControllerSession(
    private val api: ControllerApi,
    private val visibility: PageVisibility,
) {
    private val _status = MutableStateFlow<StatusSnapshot?>(null)
    val status: StateFlow<StatusSnapshot?> = _status.asStateFlow()

    private val _problem = MutableStateFlow<ConnectionProblem?>(null)
    val problem: StateFlow<ConnectionProblem?> = _problem.asStateFlow()

    private val _failedAttempts = MutableStateFlow(0)

    /**
     * Attempts to reach the controller that failed since it was last reached; 0 while connected.
     * Each one is followed by the wait [retryDelayAfter] gives, unless [reconnect] cuts it short.
     */
    val failedAttempts: StateFlow<Int> = _failedAttempts.asStateFlow()

    /** Wakes the retry loop early (the Reconnect button). */
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    /** Recent log entries, newest first. */
    private val _log = MutableStateFlow<List<LogEntryDto>>(emptyList())
    val log: StateFlow<List<LogEntryDto>> = _log.asStateFlow()

    private var lastLogSeq = 0L

    private val _metrics = MutableStateFlow<Map<String, Map<String, Samples>>>(emptyMap())

    /** Per edge and series (`api`, `mesh`, `in`, `out`), the last hour of samples. */
    val metrics: StateFlow<Map<String, Map<String, Samples>>> = _metrics.asStateFlow()

    private val _jobs = MutableStateFlow<Map<String, SetupJobDto>>(emptyMap())

    /** Add node setup jobs by id, as the event stream reports them. */
    val jobs: StateFlow<Map<String, SetupJobDto>> = _jobs.asStateFlow()

    private val _speedLive = MutableStateFlow<SpeedTestLiveDto?>(null)

    /** The homelab ↔ node speed test running now; null when none runs. */
    val speedLive: StateFlow<SpeedTestLiveDto?> = _speedLive.asStateFlow()

    private val _session = MutableStateFlow<SessionDto?>(null)

    /** Who the token belongs to; null until known. */
    val session: StateFlow<SessionDto?> = _session.asStateFlow()

    /** Signed in with a share link: the dashboard may look, never change. */
    val readOnly: Boolean get() = _session.value?.role == SessionRole.VIEWER

    private val _accessRevision = MutableStateFlow(0L)

    /** Changes when users, invitations or devices changed, so the Access page reloads. */
    val accessRevision: StateFlow<Long> = _accessRevision.asStateFlow()

    /** The current snapshot's fields, which `patch` events update. */
    private var fields: JsonObject? = null

    /**
     * Follows the event stream until canceled, while the page is visible: 30 s after the tab is
     * hidden the stream closes, and it opens again (with a fresh snapshot) once the tab is back.
     */
    @OptIn(FlowPreview::class)
    suspend fun follow() {
        visibility.visible
            .debounce { visible -> if (visible) 0L else HIDDEN_GRACE.inWholeMilliseconds }
            .distinctUntilChanged()
            .collectLatest { visible ->
                if (visible) {
                    stream()
                }
            }
    }

    /**
     * Keeps the event stream open. When it cannot be opened or drops (network, controller
     * restart), polls once to stay current and reconnects shortly after.
     */
    private suspend fun stream() {
        while (true) {
            loadSession()
            loadMetrics()
            // A fresh request each time, so a reconnect asks only for the log entries it missed.
            api.events(lastLogSeq)
                .onEach { onEvent(it) }
                .catch { error ->
                    if (error is ApiException) {
                        _problem.value = error.toProblem()
                    }
                }
                .collect()
            // The stream ended or dropped: poll once to stay current, then reconnect.
            refresh()
            val failing = _problem.value != null
            if (failing) {
                _failedAttempts.value += 1
            }
            // A rejected token stays rejected: wait for a new one, not for a timer.
            if (_problem.value == ConnectionProblem.Unauthorized) {
                retryNow.receive()
            } else {
                withTimeoutOrNull(if (failing) retryDelayAfter(_failedAttempts.value) else RETRY_AFTER_DROP) {
                    retryNow.receive()
                }
            }
        }
    }

    /** Tries to reach the controller now instead of at the end of the current wait. */
    fun reconnect() {
        retryNow.trySend(Unit)
    }

    suspend fun refresh() {
        runCatchingCancellable { api.status() }
            .onSuccess {
                if (_session.value == null) {
                    loadSession()
                }
                setSnapshot(it)
                connected()
            }
            .onFailure {
                _problem.value = (it as? ApiException)?.toProblem()
                    ?: ConnectionProblem.Failed("Controller unreachable: ${it.message}")
            }
    }

    internal fun onEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.Status -> {
                setSnapshot(event.snapshot)
                connected()
            }

            is ServerEvent.Patch -> {
                // A patch only follows a status event on the same stream, so fields are set.
                val patched = fields?.patched(event) ?: return
                fields = patched
                _status.value = snapshotOf(
                    fields = patched,
                    generatedAt = event.at,
                )
            }

            is ServerEvent.Metrics -> appendMetrics(
                atSeconds = event.at,
                points = event.points,
            )

            is ServerEvent.Job -> _jobs.value = _jobs.value + (event.job.id to event.job)

            is ServerEvent.SpeedTestLive -> _speedLive.value = event.live

            is ServerEvent.Access -> _accessRevision.value = event.revision

            is ServerEvent.Log -> appendLog(event.entries)
        }
    }

    /** Asks who the token belongs to; call again after the token changes. */
    suspend fun loadSession() {
        runCatchingCancellable { api.session() }
            .onSuccess { _session.value = it }
            .onFailure { _session.value = null }
    }

    /** Loads the last hour of graphs; on failure the graphs fill from the stream instead. */
    private suspend fun loadMetrics() {
        runCatchingCancellable { api.metrics(rangeSeconds = METRICS_RANGE_SECONDS) }
            .onSuccess { response ->
                _metrics.value = response.series
                    .groupBy { it.edge }
                    .mapValues { (_, series) ->
                        series.associate {
                            it.series to Samples(
                                unit = it.unit,
                                times = it.times,
                                values = it.values,
                            )
                        }
                    }
            }
    }

    private fun appendMetrics(atSeconds: Long, points: List<MetricPointDto>) {
        val oldest = atSeconds - METRICS_RANGE_SECONDS
        val updated = _metrics.value.mapValues { (_, series) -> series.toMutableMap() }.toMutableMap()
        for (point in points) {
            val edge = updated.getOrPut(point.edge) { mutableMapOf() }
            val current = edge[point.series] ?: Samples(
                unit = when (point.series) {
                    "in", "out", "egress" -> "Mb/s"
                    "rps" -> "r/s"
                    else -> "ms"
                },
                times = emptyList(),
                values = emptyList(),
            )
            edge[point.series] = current.plus(
                time = atSeconds,
                value = point.value,
            ).since(oldest)
        }
        _metrics.value = updated
    }

    private fun connected() {
        _problem.value = null
        _failedAttempts.value = 0
    }

    private fun setSnapshot(snapshot: StatusSnapshot) {
        fields = snapshotFields(snapshot)
        _status.value = snapshot
    }

    private fun appendLog(fresh: List<LogEntryDto>) {
        if (fresh.isEmpty()) return
        // Sequence numbers restart with the controller: start the list over.
        val restarted = fresh.first().seq <= lastLogSeq
        lastLogSeq = fresh.last().seq
        val previous = if (restarted) emptyList() else _log.value
        _log.value = (fresh.asReversed() + previous).take(MAX_LOG_ENTRIES)
    }

    private fun ApiException.toProblem(): ConnectionProblem =
        if (unauthorized) {
            ConnectionProblem.Unauthorized
        } else {
            ConnectionProblem.Failed(message ?: "HTTP $status")
        }

    companion object {
        /**
         * The wait after the [attempt]th failed try in a row: short at first, so a blip (a phone
         * waking a tab) clears before anyone sees it, then every 5 s.
         */
        fun retryDelayAfter(attempt: Int): Duration = when (attempt) {
            1, 2 -> 1.seconds
            3 -> 2.seconds
            else -> 5.seconds
        }

        /** The wait before reopening a stream that ended while the controller was fine. */
        private val RETRY_AFTER_DROP = 2.seconds
        private const val MAX_LOG_ENTRIES = 1000
        private const val METRICS_RANGE_SECONDS = 3600L
        private val HIDDEN_GRACE = 30.seconds
    }
}

/** One edge's series: sample times (epoch seconds) and values, null where a measurement failed. */
data class Samples(
    val unit: String,
    val times: List<Long>,
    val values: List<Double?>,
) {
    fun plus(time: Long, value: Double?): Samples = copy(
        times = times + time,
        values = values + value,
    )

    fun since(oldest: Long): Samples {
        val first = times.indexOfFirst { it >= oldest }.takeIf { it >= 0 } ?: times.size
        return copy(
            times = times.drop(first),
            values = values.drop(first),
        )
    }

    /** The newest value, or null when there is none or the newest measurement failed. */
    val latest: Double? get() = values.lastOrNull()
}
