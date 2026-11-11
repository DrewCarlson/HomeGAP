package homegap.presentation.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.presentation.ui.ConfirmModel

/**
 * While changes are paused, an action that changes something asks first: apply just this change
 * (one pass with the pause lifted), or resume everything.
 */
@SingleIn(AppScope::class)
@Inject
class PausedGuard(
    private val session: ControllerSession,
    private val api: ControllerApi,
    private val actions: ActionRunner,
    private val confirmations: Confirmations,
) {
    /** Runs [action] at once, or after asking when changes are paused. [what] names it ("Re-provision sg1"). */
    fun run(what: String, action: () -> Unit) {
        if (session.status.value?.paused != true) {
            action()
            return
        }
        confirmations.ask(
            model = ConfirmModel(
                title = "Changes are paused",
                text = "$what changes something, and HomeGAP is only observing. Apply just this change now, " +
                    "or resume all changes?",
                confirmLabel = "Apply just this",
                secondaryLabel = "Resume everything",
            ),
            onSecondary = {
                actions.run("Resume") { api.pause(PauseScope.Global, null, false) }
                action()
            },
        ) {
            action()
            actions.run("Apply once") { api.applyOnce() }
        }
    }
}
