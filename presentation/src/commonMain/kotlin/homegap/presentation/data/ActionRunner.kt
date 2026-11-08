package homegap.presentation.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.ActionResponse
import homegap.presentation.core.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** A short-lived message shown at the bottom of the screen. */
data class Toast(val text: String, val isError: Boolean)

/**
 * Runs controller actions on the app scope and reports their outcome as a [Toast]. [busy] is
 * true while any action is in flight, so the UI can hold off conflicting ones.
 */
@SingleIn(AppScope::class)
@Inject
class ActionRunner(
    private val session: ControllerSession,
    @AppCoroutineScope private val scope: CoroutineScope,
) {
    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Runs [action], reports its message (or failure) and refreshes the status. */
    fun run(label: String, action: suspend () -> ActionResponse) {
        scope.launch {
            _busy.value = true
            try {
                runCatchingCancellable { action() }
                    .onSuccess { notify(it.message); session.refresh() }
                    .onFailure { notify("$label failed: ${it.message}", isError = true) }
            } finally {
                _busy.value = false
            }
        }
    }

    fun notify(message: String, isError: Boolean = false) {
        val shown = Toast(message, isError)
        _toast.value = shown
        scope.launch {
            delay(TOAST_DURATION)
            if (_toast.value == shown) _toast.value = null
        }
    }

    private companion object {
        val TOAST_DURATION = 5.seconds
    }
}
