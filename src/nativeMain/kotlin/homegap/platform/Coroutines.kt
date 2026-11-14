package homegap.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration

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

/**
 * Runs [block] until it succeeds, [attempts] times at most, with [pause] between two attempts.
 * [onRetry] gets the error of each attempt that has one more attempt after it. Returns the result
 * of the last attempt.
 */
suspend fun <T> retrying(
    attempts: Int,
    pause: Duration,
    onRetry: (Throwable) -> Unit = {},
    block: suspend () -> T,
): Result<T> {
    var result = runCatchingCancellable { block() }
    repeat(attempts - 1) {
        val failure = result.exceptionOrNull() ?: return result
        onRetry(failure)
        delay(pause)
        result = runCatchingCancellable { block() }
    }
    return result
}
