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
     * Short children run one after another while the garbage collector runs alongside: freeing a
     * finished process must never close a descriptor a later process is using (ksubprocess before
     * 1.0 closed a child's pipe ends twice on macOS, and the second close could hit a live pipe).
     */
    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
    @Test
    fun collectingFinishedProcessesNeverTouchesLiveOnes() = runBlocking {
        val gc = launch(Dispatchers.Default) { while (isActive) kotlin.native.runtime.GC.collect() }
        try {
            repeat(300) { round ->
                val r = Subprocess.run(listOf("sh", "-c", "printf %s $round; printf e >&2"), stdin = "x\n")
                assertEquals("$round", r.stdout)
                assertEquals("e", r.stderr)
            }
        } finally {
            gc.cancelAndJoin()
        }
    }
}
