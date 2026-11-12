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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * A short-lived message. [actionLabel] and [action] add a button such as Undo, and such a toast
 * also has [dismiss], which removes it unused.
 */
data class Toast(
    val text: String,
    val isError: Boolean,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    val dismiss: (() -> Unit)? = null,
)

/**
 * Runs controller actions on the app scope and reports their outcome as a [Toast]. [busy] is
 * true while any action is in flight, so the UI can hold off conflicting ones.
 */
@SingleIn(AppScope::class)
@Inject
class ActionRunner(
    private val session: ControllerSession,
    private val api: ControllerApi,
    @AppCoroutineScope private val scope: CoroutineScope,
) {
    private val _toasts = MutableStateFlow<List<Toast>>(emptyList())

    /**
     * The messages to show, oldest first. A new message replaces the plain one before it, but a
     * toast with an action stays until it is used, dismissed, or its time runs out.
     */
    val toasts: StateFlow<List<Toast>> = _toasts.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Runs [action], reports its message (or failure) and refreshes the status; then [onSuccess]. */
    fun run(label: String, onSuccess: () -> Unit = {}, action: suspend () -> ActionResponse) {
        scope.launch {
            _busy.value = true
            try {
                runCatchingCancellable { action() }
                    .onSuccess { response ->
                        val undo = response.undo
                        if (undo != null) {
                            notify(
                                message = response.message,
                                actionLabel = "Undo",
                                action = { run("Undo") { api.undo(undo) } },
                                duration = UNDO_TOAST_DURATION,
                            )
                        } else {
                            notify(response.message)
                        }
                        session.refresh()
                        onSuccess()
                    }
                    .onFailure { notify("$label failed: ${it.message}", isError = true) }
            } finally {
                _busy.value = false
            }
        }
    }

    fun notify(
        message: String,
        isError: Boolean = false,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
        duration: kotlin.time.Duration = TOAST_DURATION,
    ) {
        lateinit var shown: Toast
        val remove = { _toasts.update { toasts -> toasts.filterNot { it === shown } } }
        shown = Toast(
            text = message,
            isError = isError,
            actionLabel = actionLabel,
            action = action?.let {
                {
                    remove()
                    it()
                }
            },
            dismiss = remove.takeIf { action != null },
        )
        _toasts.update { toasts -> toasts.filter { it.action != null } + shown }
        scope.launch {
            delay(duration)
            remove()
        }
    }

    companion object {
        private val TOAST_DURATION = 5.seconds
        private val UNDO_TOAST_DURATION = 30.seconds
    }
}
