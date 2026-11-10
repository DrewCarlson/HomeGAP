package homegap.presentation.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.LogEntryDto
import homegap.api.ServerEvent
import homegap.api.StatusSnapshot
import homegap.presentation.core.runCatchingCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
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
class ControllerSession(private val api: ControllerApi) {
    private val _status = MutableStateFlow<StatusSnapshot?>(null)
    val status: StateFlow<StatusSnapshot?> = _status.asStateFlow()

    private val _problem = MutableStateFlow<ConnectionProblem?>(null)
    val problem: StateFlow<ConnectionProblem?> = _problem.asStateFlow()

    /** Recent log entries, newest first. */
    private val _log = MutableStateFlow<List<LogEntryDto>>(emptyList())
    val log: StateFlow<List<LogEntryDto>> = _log.asStateFlow()

    private var lastLogSeq = 0L

    /**
     * Follows the event stream until canceled. When it cannot be opened or drops (network,
     * controller restart), polls once to stay current and reconnects shortly after.
     */
    suspend fun follow() {
        while (true) {
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
            delay(if (_problem.value != null) 5.seconds else 2.seconds)
        }
    }

    suspend fun refresh() {
        runCatchingCancellable { api.status() }
            .onSuccess {
                _status.value = it
                _problem.value = null
            }
            .onFailure {
                _problem.value = (it as? ApiException)?.toProblem()
                    ?: ConnectionProblem.Failed("Controller unreachable: ${it.message}")
            }
    }

    internal fun onEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.Status -> {
                _status.value = event.snapshot
                _problem.value = null
            }

            is ServerEvent.Log -> appendLog(event.entries)
        }
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

    private companion object {
        const val MAX_LOG_ENTRIES = 1000
    }
}
