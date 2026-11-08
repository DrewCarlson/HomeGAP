package homegap.presentation.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but a cancellation of the calling coroutine (a presenter leaving composition)
 * still propagates. A [CancellationException] that is not ours (a nested timeout, say) is returned
 * as a failure like any other error.
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Throwable) {
        if (e is CancellationException) {
            currentCoroutineContext().ensureActive()
        }
        Result.failure(e)
    }
