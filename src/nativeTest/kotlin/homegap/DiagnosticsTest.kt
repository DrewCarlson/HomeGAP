package homegap

import homegap.api.NodeLogServices
import homegap.diagnostics.NodeDiagnostics
import homegap.platform.Subprocess
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DiagnosticsTest {
    private fun bashSyntax(script: String) = runBlocking {
        val result = Subprocess.run(listOf("bash", "-n"), stdin = script)
        assertEquals(0, result.exitCode, result.stderr)
    }

    @Test
    fun logsComeFromTheNodeStackAndRejectOddInput() {
        val scripts = NodeDiagnostics.Scripts
        for ((service, _) in NodeLogServices.ALL) {
            assertTrue("cd '/opt/homegap' " in scripts.logs("/opt/homegap", service, "1h", 200), service)
        }
        assertTrue("--since 30m --tail 300 traefik" in scripts.logs("/opt/homegap", "traefik", "30m", 300))
        assertFailsWith<IllegalArgumentException> { scripts.logs("/opt/homegap", "traefik; reboot", "30m", 10) }
        assertFailsWith<IllegalArgumentException> { scripts.logs("/opt/homegap", "pangolin", "30m", 10) }
        assertFailsWith<IllegalArgumentException> { scripts.logs("/opt/homegap", "traefik", "1h; reboot", 10) }
        assertFailsWith<IllegalArgumentException> { scripts.logs("/opt/homegap", "traefik", "1h", 0) }
    }

    @Test
    fun theBundleIsValidShellAndNeverPrintsAKey() {
        val bundle = NodeDiagnostics.Scripts.bundle("/opt/homegap")
        bashSyntax(bundle)
        val wireGuard = bundle.lines().filter { "wg show" in it }
        assertEquals(2, wireGuard.size, "the mesh and the exit of the node")
        assertTrue(wireGuard.all { "grep -v 'private key'" in it }, wireGuard.toString())
        assertFalse("docker compose config" in bundle, "would print the stack's secrets")
    }
}
