package homegap

import homegap.platform.Files
import homegap.platform.Subprocess
import homegap.platform.runCatchingCancellable
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubprocessTest {
    /** Open descriptors after a full GC (the macOS backend frees NSPipe handles on collection). */
    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
    private fun openDescriptors(): Int {
        kotlin.native.runtime.GC.collect()
        return Files.list("/dev/fd").size
    }

    @Test
    fun capturesOutputAndExitCode() = runBlocking {
        val result = Subprocess.run(listOf("sh", "-c", $$"read x; echo out:$x; echo err >&2; exit 3"), stdin = "hi\n")
        assertEquals(3, result.exitCode)
        assertEquals("out:hi\n", result.stdout)
        assertEquals("err\n", result.stderr)
    }

    /** Every run releases its pipes. */
    @Test
    fun runsDoNotLeakDescriptors() = runBlocking {
        Subprocess.run(listOf("true")) // the first run opens the lazy descriptors
        val before = openDescriptors()
        repeat(50) { Subprocess.run(listOf("sh", "-c", "echo x; echo y >&2")) }
        val after = openDescriptors()
        assertTrue(after - before < 5, "descriptors grew from $before to $after over 50 runs")
    }

    @Test
    fun aMissingExecutableThrowsInsteadOfCrashing() = runBlocking {
        assertTrue(runCatchingCancellable { Subprocess.run(listOf("homegap-no-such-binary")) }.isFailure)
        assertTrue(runCatchingCancellable { Subprocess.run(listOf("/nonexistent/bin/tool")) }.isFailure)
    }

    @Test
    fun startedChildrenWriteBothStreamsToTheirLog() = runBlocking {
        val log = tmpPath("child-log")
        val child = Subprocess.start(listOf("sh", "-c", "echo out; echo err >&2; exit 4"), log)
        assertEquals(4, child.awaitExit())
        assertEquals(setOf("out", "err"), Files.readText(log).lines().filter { it.isNotBlank() }.toSet())
    }
}
