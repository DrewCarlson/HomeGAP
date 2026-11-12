package homegap.presentation

import homegap.presentation.data.endAfterSilence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class EventStreamSilenceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aStalledStreamEndsAfterTheSilenceLimit() = runTest {
        val stalled = flow {
            emit("status")
            delay(50.seconds)
            emit("keepalive")
            awaitCancellation()
        }
        assertEquals(listOf("status", "keepalive"), stalled.endAfterSilence(60.seconds).toList())
        assertEquals(110.seconds.inWholeMilliseconds, currentTime, "the limit counts from the last arrival")
    }

    @Test
    fun aFailingStreamStillFails() = runTest {
        val failing = flow<String> { throw IllegalStateException("closed") }
        assertFailsWith<IllegalStateException> { failing.endAfterSilence(60.seconds).toList() }
    }
}
