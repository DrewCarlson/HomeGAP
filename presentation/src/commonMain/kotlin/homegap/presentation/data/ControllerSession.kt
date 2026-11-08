package homegap.presentation.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.LogEntryDto
import homegap.api.LogResponse
import homegap.api.StatusSnapshot
import homegap.presentation.core.runCatchingCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
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
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow<StatusSnapshot?>(null)
    val status: StateFlow<StatusSnapshot?> = _status.asStateFlow()

    private val _problem = MutableStateFlow<ConnectionProblem?>(null)
    val problem: StateFlow<ConnectionProblem?> = _problem.asStateFlow()

    /** Recent log entries, newest first. */
    private val _log = MutableStateFlow<List<LogEntryDto>>(emptyList())
    val log: StateFlow<List<LogEntryDto>> = _log.asStateFlow()

    private var lastLogSeq = 0L

    /**
     * Follows the event stream until cancelled. When it cannot be opened or drops (network,
     * controller restart), polls once to stay current and reconnects shortly after.
     */
    suspend fun follow() {
        while (true) {
            try {
                api.events(lastLogSeq, ::onEvent)
            } catch (e: ApiException) {
                _problem.value = e.toProblem()
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                // Dropped connection: keep showing the last state and retry below.
            }
            refresh()
            delay(if (_problem.value != null) 5.seconds else 2.seconds)
        }
    }

    suspend fun refresh() {
        runCatchingCancellable { api.status() }
            .onSuccess { _status.value = it; _problem.value = null }
            .onFailure { _problem.value = (it as? ApiException)?.toProblem() ?: ConnectionProblem.Failed("Controller unreachable: ${it.message}") }
    }

    internal fun onEvent(event: ServerEvent) {
        when (event.name) {
            "status" -> {
                _status.value = json.decodeFromString(StatusSnapshot.serializer(), event.data)
                _problem.value = null
            }
            "log" -> appendLog(json.decodeFromString(LogResponse.serializer(), event.data).entries)
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
        if (unauthorized) ConnectionProblem.Unauthorized else ConnectionProblem.Failed(message ?: "HTTP $status")

    private companion object {
        const val MAX_LOG_ENTRIES = 1000
    }
}
