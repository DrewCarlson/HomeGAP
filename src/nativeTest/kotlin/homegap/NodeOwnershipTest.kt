package homegap

import homegap.clients.SshSession
import homegap.platform.Subprocess
import homegap.provision.ExitNodes
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
    fun theClusterMembersDataStaysWithItsContainers() {
        val own = SshSession.ownInstallDir("/opt/homegap")
        assertEquals(
            "hg_own '/opt/homegap' '/opt/homegap/cluster/etcd' '/opt/homegap/cluster/pgdata' " +
                "'/opt/homegap/cluster/valkey' '/opt/homegap/cluster/sentinel'",
            own,
        )
    }

    @Test
    fun scriptsFindTheDockerBinaryNotTheFunction() {
        val script = ExitNodes.deployScript(
            installDir = "/opt/homegap",
            image = "img",
            newtId = "id",
            newtSecret = "s",
        )
        assertTrue("type -P docker" in script && "command -v docker" !in script)
        val own = script.indexOf("hg_own")
        assertTrue(own in 0 until script.indexOf("docker compose up"), "files change hands before Compose reads them")
    }
}
