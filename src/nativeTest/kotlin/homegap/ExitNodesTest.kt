package homegap

import homegap.config.ConfigLoader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import homegap.provision.ExitNodes
import homegap.reconcile.DiffCollector
import homegap.reconcile.ReconcileContext
import homegap.reconcile.ReconcileLog
import homegap.reconcile.ResourceReconciler
import homegap.state.StateStore
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class ExitNodesTest {
    private fun cfg(exits: String) = ConfigLoader.parse(TEST_CONFIG + "\ncluster:\n  exit_nodes:\n$exits")

    @Test
    fun oneExitPerNodeWithItsSiteAndOneForTheHomelabOnEveryHomelabSite() {
        val c = cfg("    nodes: true\n    homelab: true\n    roles: [Member, Admin]\n")
        val res = ExitNodes.resources(c, mapOf("na1" to "exit-na1"), mapOf("na1" to "homelab-na1", "as1" to "homelab-as1"))
        val byId = res.keys.associateBy { it.niceId }
        assertEquals(setOf("hg-exit-na1", "hg-exit-homelab"), byId.keys, "as1 has no exit site yet")
        assertEquals(listOf("exit-na1"), res.getValue(byId.getValue("hg-exit-na1")))
        assertEquals(listOf("homelab-na1", "homelab-as1"), res.getValue(byId.getValue("hg-exit-homelab")), "in config order")
        assertEquals("Exit via na1", byId.getValue("hg-exit-na1").name)
        assertTrue(res.keys.all { it.mode == "exit-node" })
        // Still wanted (never deleted) while a node's site is missing.
        assertEquals(setOf("hg-exit-na1", "hg-exit-as1", "hg-exit-homelab"), ExitNodes.wanted(c))
    }

    @Test
    fun offUnlessAsked() {
        assertTrue(ExitNodes.resources(cfg("    nodes: false\n"), mapOf("na1" to "exit-na1"), mapOf("na1" to "homelab-na1")).isEmpty())
        assertTrue(ExitNodes.wanted(testConfig()).isEmpty())
        val onlyHomelab = cfg("    homelab: true\n")
        assertEquals(setOf("hg-exit-homelab"), ExitNodes.wanted(onlyHomelab))
    }

    @Test
    fun blueprintHasNoDestinationAndNeverListsAdmin() {
        val c = cfg("    nodes: true\n    roles: [Member, Admin]\n")
        val ctx = ReconcileContext(c, StateStore(tmpPath("state")), ReconcileLog(), homegap.clients.SshPool(tmpPath("ssh"), tmpPath("kh")),
            HttpClient(MockEngine { respondOk() }),
            HttpClient(MockEngine { respondOk() }), false, DiffCollector())
        val (r, sites) = ExitNodes.resources(c, mapOf("na1" to "exit-na1"), emptyMap()).entries.single()
        val bp = ResourceReconciler(ctx).buildBlueprint(emptyMap(), mapOf(r to sites))
        val o = bp.getValue("private-resources").jsonObject.getValue("hg-exit-na1").jsonObject
        assertEquals("exit-node", o.getValue("mode").jsonPrimitive.content)
        assertEquals(listOf("exit-na1"), o.getValue("sites").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Member"), o.getValue("roles").jsonArray.map { it.jsonPrimitive.content })
        assertNull(o["destination"]); assertNull(o["alias"]); assertNull(o["tcp-ports"])
    }

    @Test
    fun connectorRunsOnTheHostNetworkAgainstLoopback() {
        val compose = ExitNodes.compose("docker.io/fosrl/newt:1.20.0")
        assertTrue("network_mode: \"host\"" in compose, compose)
        assertTrue("docker.io/fosrl/newt:1.20.0" in compose)
        assertEquals("PANGOLIN_ENDPOINT=http://127.0.0.1:3000\nNEWT_ID=id\nNEWT_SECRET=s\n", ExitNodes.env("id", "s"))
        val script = ExitNodes.deployScript("/opt/homegap", "img", "id", "s")
        assertTrue("/opt/homegap/exit/compose.yml" in script && "/opt/homegap/exit/.env" in script)
        assertFalse("NEWT_SECRET=s" in script, "the secret travels base64-encoded, never in the clear on the command line")
        assertTrue("rm -rf '/opt/homegap/exit'" in ExitNodes.teardownScript("/opt/homegap"))
    }

    @Test
    fun theWireGuardEndpointIsPinnedToThePublishedAddresses() {
        // Pangolin gives the connector the management host as its WireGuard endpoint.
        val pins = listOf("pangolin-na1.test.example.net" to "203.0.113.10", "pangolin-na1.test.example.net" to "2001:db8::10")
        val compose = ExitNodes.compose("img", pins)
        assertTrue("extra_hosts" in compose, compose)
        assertTrue("pangolin-na1.test.example.net=203.0.113.10" in compose, compose)
        assertTrue("pangolin-na1.test.example.net=2001:db8::10" in compose, compose)
        assertFalse("pangolin-na1" in ExitNodes.compose("img"), "no pins, no entry")
        // Newt's version check goes nowhere.
        assertTrue("api.fossorial.io=127.0.0.1" in ExitNodes.compose("img"))
    }
}
