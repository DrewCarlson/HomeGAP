package homegap

import homegap.platform.Pty
import homegap.routes.TerminalSocket
import homegap.terminal.OutputBuffer
import homegap.terminal.TerminalSession
import homegap.terminal.TerminalSessions
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTest {
    private fun bytes(text: String) = text.encodeToByteArray()

    @Test
    fun theBufferKeepsTheLastBytesWithTheirPositions() {
        val buffer = OutputBuffer(capacity = 8)
        buffer.append(bytes("abcde"), 5)
        assertEquals(0L to "abcde", buffer.read(0, 100).let { it.first to it.second.decodeToString() })
        buffer.append(bytes("fghijk"), 6)
        assertEquals(11, buffer.end)
        assertEquals(3, buffer.start)
        // A position that the buffer lost moves to the oldest byte that it holds.
        assertEquals(3L to "defghijk", buffer.read(0, 100).let { it.first to it.second.decodeToString() })
        assertEquals(9L to "jk", buffer.read(9, 100).let { it.first to it.second.decodeToString() })
        assertEquals(3L to "de", buffer.read(3, 2).let { it.first to it.second.decodeToString() })
        assertEquals(0, buffer.read(11, 100).second.size)
    }

    @Test
    fun onlyThePageOfTheDashboardOpensASocket() {
        assertTrue(TerminalSocket.sameOrigin("http://localhost:8088", "localhost:8088"))
        assertTrue(TerminalSocket.sameOrigin("https://homegap.example.net", "homegap.example.net"))
        assertFalse(TerminalSocket.sameOrigin("https://evil.example", "homegap.example.net"))
        assertFalse(TerminalSocket.sameOrigin("https://homegap.example.net.evil.example", "homegap.example.net"))
        assertFalse(TerminalSocket.sameOrigin(null, "homegap.example.net"))
    }

    @Test
    fun theShellStartsInTheInstallDirectory() {
        assertEquals("cd '/opt/home gap' 2>/dev/null; exec \"\${SHELL:-/bin/bash}\" -l", TerminalSessions.shellCommand("/opt/home gap"))
    }

    @Test
    fun aSessionKeepsItsOutputForTheNextConnection() = runBlocking {
        val pty = Pty.spawn(listOf("/bin/sh"), rows = 24, columns = 80, env = mapOf("PS1" to ""))
        val session = TerminalSession("s1", "na1", pty, this)
        suspend fun outputHas(text: String) = withTimeout(10_000) {
            while (text !in session.output(0, 64 * 1024).second.decodeToString()) delay(20)
        }
        val first = Any()
        assertEquals(0, session.attach(first))
        session.input(bytes("echo one-\$((1+1))\n"))
        outputHas("one-2")
        session.detach(first)
        assertFalse(session.attached)

        // A second connection takes the place of the first, and reads the same output.
        val second = Any()
        session.attach(second)
        assertTrue(session.shows(second))
        assertFalse(session.shows(first))
        assertTrue("one-2" in session.output(0, 64 * 1024).second.decodeToString())

        session.input(bytes("exit 5\n"))
        withTimeout(10_000) { while (session.exitCode == null) delay(20) }
        assertEquals(5, session.exitCode)
        session.close()
    }
}
