package homegap.presentation.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.presentation.ui.ConfirmModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The confirm dialog: one question at a time, asked by presenters before risky actions. */
@SingleIn(AppScope::class)
@Inject
class Confirmations {
    private val _pending = MutableStateFlow<Pending?>(null)

    /** The question shown now, or null. */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /** Shows [model] and runs [onConfirm] only if the user confirms ([onSecondary] for its second button). */
    fun ask(model: ConfirmModel, onSecondary: () -> Unit = {}, onConfirm: () -> Unit) {
        _pending.value = Pending(
            model = model,
            onConfirm = onConfirm,
            onSecondary = onSecondary,
        )
    }

    /** Closes the dialog; runs the action when [confirmed]. */
    fun resolve(confirmed: Boolean) {
        val pending = _pending.value ?: return
        _pending.value = null
        if (confirmed) {
            pending.onConfirm()
        }
    }

    /** Closes the dialog with its second button. */
    fun resolveSecondary() {
        val pending = _pending.value ?: return
        _pending.value = null
        pending.onSecondary()
    }

    data class Pending(
        val model: ConfirmModel,
        val onConfirm: () -> Unit,
        val onSecondary: () -> Unit = {},
    )
}
