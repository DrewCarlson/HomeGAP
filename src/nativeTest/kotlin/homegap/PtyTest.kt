package homegap

import homegap.platform.Pty
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PtyTest {
    private fun Pty.readUntil(text: String): String {
        val out = StringBuilder()
        val buffer = ByteArray(4096)
        while (text !in out) {
            val count = read(buffer)
            if (count < 0) break
            out.append(buffer.decodeToString(0, count))
        }
        return out.toString()
    }

    /** Waits until the child ends by itself. A close before that ends the child with a signal. */
    private fun Pty.awaitExit(): Int? {
        repeat(EXIT_POLLS) {
            exitCode()?.let { return it }
            usleep(EXIT_POLL_MICROS)
        }
        return exitCode()
    }

    @Test
    fun theChildIsOnATerminalOfTheGivenSize() {
        val pty = Pty.spawn(listOf("/bin/sh", "-c", "stty size; echo done"), rows = 31, columns = 97)
        val out = pty.readUntil("done")
        val exitCode = pty.awaitExit()
        pty.close()
        assertTrue("31 97" in out, out)
        assertEquals(0, exitCode)
    }

    @Test
    fun inputReachesTheChildAndAResizeChangesTheSize() {
        val pty = Pty.spawn(
            listOf("/bin/sh"),
            rows = 24,
            columns = 80,
            env = mapOf("PS1" to "", "HG_TEST" to "from-env"),
        )
        pty.write($$"echo $HG_TEST\n".encodeToByteArray())
        assertTrue("from-env" in pty.readUntil("from-env\r\n"))
        pty.resize(rows = 40, columns = 120)
        pty.write("stty size\n".encodeToByteArray())
        assertTrue("40 120" in pty.readUntil("40 120"))
        pty.close()
        assertTrue(pty.exitCode() != null)
    }

    @Test
    fun aClosedTerminalEndsARead() {
        val pty = Pty.spawn(listOf("/bin/sh", "-c", "exit 3"), rows = 24, columns = 80)
        val buffer = ByteArray(64)
        while (pty.read(buffer) >= 0) { /* the child prints nothing */ }
        pty.close()
        assertEquals(3, pty.exitCode())
    }

    private companion object {
        /** The child gets 5 seconds to end. */
        const val EXIT_POLLS = 100
        const val EXIT_POLL_MICROS = 50_000u
    }
}
