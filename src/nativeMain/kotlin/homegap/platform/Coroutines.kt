package homegap.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Does the same as [runCatching], but a cancellation of the caller continues up. A
 * [CancellationException] from a different source, for example a timeout in [block], is a failure
 * like each other error.
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
