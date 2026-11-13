package homegap

import homegap.platform.Subprocess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SubprocessStressTest {
    /**
     * Short children run one after another while the garbage collector runs. When the runtime frees
     * a finished process, it must never close a descriptor that a later process uses. Before version
     * 1.0, ksubprocess closed the pipe ends of a child twice on macOS, and the second close could hit
     * a live pipe.
     */
    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
    @Test
    fun collectingFinishedProcessesNeverTouchesLiveOnes() = runBlocking {
        val collector = launch(Dispatchers.Default) { while (isActive) kotlin.native.runtime.GC.collect() }
        try {
            repeat(300) { round ->
                val result = Subprocess.run(listOf("sh", "-c", "printf %s $round; printf e >&2"), stdin = "x\n")
                assertEquals("$round", result.stdout)
                assertEquals("e", result.stderr)
            }
        } finally {
            collector.cancelAndJoin()
        }
    }
}
