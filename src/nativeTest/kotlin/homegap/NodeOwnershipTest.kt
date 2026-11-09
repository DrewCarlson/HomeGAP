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
        val r = Subprocess.run(listOf("bash", "-n"), stdin = "set -euo pipefail\n$script\n")
        assertEquals(0, r.exitCode, r.stderr)
    }

    @Test
    fun aLoginUserOwnsTheFilesAndRunsDocker() {
        val pre = SshSession.ownerPreamble("usdallas")
        bashSyntax(pre)
        assertTrue("HG_OWNER='usdallas'" in pre)
        assertTrue("-user 0 -exec chown -h" in pre, "only root-owned files change hands")
        assertTrue("runuser -u \"\$HG_OWNER\"" in pre, "docker runs as the login user")
    }

    @Test
    fun rootLoginsChangeNothing() {
        val pre = SshSession.ownerPreamble("root")
        bashSyntax(pre)
        assertFalse("docker()" in pre)
    }

    @Test
    fun theClusterMembersDataStaysWithItsContainers() {
        val own = SshSession.ownInstallDir("/opt/homegap")
        assertEquals(
            "hg_own '/opt/homegap' '/opt/homegap/cluster/etcd' '/opt/homegap/cluster/pgdata' '/opt/homegap/cluster/valkey' '/opt/homegap/cluster/sentinel'",
            own,
        )
    }

    @Test
    fun scriptsFindTheDockerBinaryNotTheFunction() {
        val script = ExitNodes.deployScript("/opt/homegap", "img", "id", "s")
        assertTrue("type -P docker" in script && "command -v docker" !in script)
        val own = script.indexOf("hg_own")
        assertTrue(own in 0 until script.indexOf("docker compose up"), "files change hands before Compose reads them")
    }
}
