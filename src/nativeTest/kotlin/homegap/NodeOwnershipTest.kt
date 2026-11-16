package homegap

import homegap.clients.SshSession
import homegap.platform.Subprocess
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeOwnershipTest {
    private fun bashSyntax(script: String) = runBlocking {
        val result = Subprocess.run(listOf("bash", "-n"), stdin = "set -euo pipefail\n$script\n")
        assertEquals(0, result.exitCode, result.stderr)
    }

    @Test
    fun aLoginUserOwnsTheFilesAndRunsDocker() {
        val preamble = SshSession.ownerPreamble("usdallas")
        bashSyntax(preamble)
        assertTrue("HG_OWNER='usdallas'" in preamble)
        assertTrue("-user 0 -exec chown -h" in preamble, "only root-owned files change hands")
        assertTrue($$"runuser -u \"$HG_OWNER\"" in preamble, "docker runs as the login user")
    }

    @Test
    fun rootLoginsChangeNothing() {
        val preamble = SshSession.ownerPreamble("root")
        bashSyntax(preamble)
        assertFalse("docker()" in preamble)
    }

    @Test
    fun theInstallDirChangesHands() {
        assertEquals("hg_own '/opt/homegap'", SshSession.ownInstallDir("/opt/homegap"))
    }
}
