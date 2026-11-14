package homegap

import homegap.platform.retrying
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RetryingTest {
    @Test
    fun anActionThatFailsAtFirstRunsAgainAfterThePause() = runTest {
        var calls = 0
        val retried = mutableListOf<String?>()
        val result = retrying(attempts = 5, pause = 30.seconds, onRetry = { retried += it.message }) {
            calls++
            check(calls == 3) { "attempt $calls" }
            "done"
        }

        assertEquals("done", result.getOrNull())
        assertEquals(3, calls)
        assertEquals(listOf<String?>("attempt 1", "attempt 2"), retried)
        assertEquals(60_000, currentTime, "one pause before each new attempt")
    }

    @Test
    fun theLastErrorIsTheResultAfterTheLastAttempt() = runTest {
        var calls = 0
        val retried = mutableListOf<String?>()
        val result = retrying(attempts = 3, pause = 30.seconds, onRetry = { retried += it.message }) {
            calls++
            error("attempt $calls")
        }

        assertTrue(result.isFailure)
        assertEquals("attempt 3", result.exceptionOrNull()?.message)
        assertEquals(listOf<String?>("attempt 1", "attempt 2"), retried, "no retry report for the last attempt")
        assertEquals(60_000, currentTime, "no pause after the last attempt")
    }
}
