package homegap.reconcile

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.concurrent.Volatile

/**
 * The event loop of the [Controller]. The controller keeps its state in plain maps and lists, and
 * a reconcile pass changes them. The HTTP server has its own threads, so each dashboard request
 * runs on this loop: a request and a pass then never touch the state at the same time.
 */
@SingleIn(AppScope::class)
@Inject
class ControllerLoop {
    /** Null until the controller runs, and in tests without a controller. */
    @Volatile var dispatcher: CoroutineDispatcher? = null
}
