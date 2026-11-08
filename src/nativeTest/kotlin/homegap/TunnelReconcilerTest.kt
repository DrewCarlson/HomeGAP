package homegap

import homegap.clients.PangolinClient
import homegap.clients.PangolinSite
import homegap.clients.SshPool
import homegap.config.ClusterConfig
import homegap.config.TunnelsConfig
import homegap.platform.Files
import homegap.reconcile.*
import homegap.state.StateStore
import homegap.tunnels.TunnelFiles
import homegap.tunnels.TunnelSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TunnelReconcilerTest {
    /** One site, reported online (a stale flag in a shared database looks just like this). */
    private class FakePangolin : PangolinClient(HttpClient(MockEngine { respondOk() }), "http://x", "k") {
        val rekeyed = mutableListOf<Int>()
        override suspend fun listSites(orgId: String) =
            listOf(PangolinSite(siteId = 1, niceId = "homelab-na1", online = true, subnet = "100.89.128.4/30"))
        override suspend fun regenerateNewtSecret(siteId: Int, secret: String): String {
            rekeyed += siteId
            return "fresh-newt"
        }
    }

    @Test
    fun aNodeThatSwitchedPangolinGetsFreshCredentialsInsteadOfTheOldSpec() = runTest {
        val dir = tmpPath("tunnels")
        val config = testConfig().copy(cluster = ClusterConfig(), tunnels = TunnelsConfig(stateDir = dir))
        val state = StateStore(tmpPath("state"))
        // What the job leaves after moving na1 onto the cluster: no credentials, and the flag.
        state.updateNode("na1") { it.copy(tunnelSlot = 0, facts = it.facts + (NodeReconciler.NEW_SITE_FACT to "true")) }
        // The homelab still has the standalone connector's spec.
        Files.mkdirs(dir)
        Files.writeAtomic(
            TunnelFiles.specPath(dir, "na1"),
            TunnelFiles.json.encodeToString(TunnelSpec.serializer(), TunnelSpec("na1", "https://x", "old-newt", "old-secret", 2000)),
        )
        val ctx = ReconcileContext(config, state, ReconcileLog(), SshPool(tmpPath("ssh"), tmpPath("kh")), HttpClient(MockEngine { respondOk() }), HttpClient(MockEngine { respondOk() }), false, DiffCollector())
        val api = FakePangolin()

        TunnelReconciler(ctx).reconcile(ReadyNode(config.node("na1")!!, api), 0)

        assertEquals(listOf(1), api.rekeyed, "re-keyed, not adopted")
        assertEquals("fresh-newt", state.node("na1").newtId)
        assertNull(state.node("na1").facts[NodeReconciler.NEW_SITE_FACT], "cleared once fresh credentials are issued")
        assertEquals("fresh-newt", TunnelFiles.readSpecs(dir).single().newtId)
    }
}
