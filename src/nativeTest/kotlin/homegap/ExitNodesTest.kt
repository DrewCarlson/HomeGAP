package homegap

import homegap.clients.SshPool
import homegap.config.ConfigLoader
import homegap.provision.ExitNodes
import homegap.reconcile.DiffCollector
import homegap.reconcile.ReconcileContext
import homegap.reconcile.ReconcileLog
import homegap.reconcile.ResourceReconciler
import homegap.state.StateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class ExitNodesTest {
    private fun exitConfig(exits: String) = ConfigLoader.parse("$TEST_CONFIG\ncluster:\n  exit_nodes:\n$exits")

    @Test
    fun oneExitPerNodeWithItsSiteAndOneForTheHomelabOnEveryHomelabSite() {
        val config = exitConfig("    nodes: true\n    homelab: true\n    roles: [Member, Admin]\n")
        val resources = ExitNodes.resources(
            config,
            exitSites = mapOf("na1" to "exit-na1"),
            homelabSites = mapOf("na1" to "homelab-na1", "as1" to "homelab-as1"),
        )
        val byId = resources.keys.associateBy { it.niceId }
        assertEquals(setOf("hg-exit-na1", "hg-exit-homelab"), byId.keys, "as1 has no exit site yet")
        assertEquals(listOf("exit-na1"), resources.getValue(byId.getValue("hg-exit-na1")))
        assertEquals(
            listOf("homelab-na1", "homelab-as1"),
            resources.getValue(byId.getValue("hg-exit-homelab")),
            "in config order",
        )
        assertEquals("Exit via na1", byId.getValue("hg-exit-na1").name)
        assertTrue(resources.keys.all { it.mode == "exit-node" })
        // HomeGAP still wants the exit of a node, and never deletes it, while the site of the node is missing.
        assertEquals(setOf("hg-exit-na1", "hg-exit-as1", "hg-exit-homelab"), ExitNodes.wanted(config))
    }

    @Test
    fun offUnlessAsked() {
        val off = ExitNodes.resources(
            exitConfig("    nodes: false\n"),
            exitSites = mapOf("na1" to "exit-na1"),
            homelabSites = mapOf("na1" to "homelab-na1"),
        )
        assertTrue(off.isEmpty())
        assertTrue(ExitNodes.wanted(testConfig()).isEmpty())
        val onlyHomelab = exitConfig("    homelab: true\n")
        assertEquals(setOf("hg-exit-homelab"), ExitNodes.wanted(onlyHomelab))
    }

    @Test
    fun blueprintHasNoDestinationAndNeverListsAdmin() {
        val config = exitConfig("    nodes: true\n    roles: [Member, Admin]\n")
        val context = ReconcileContext(
            config = config,
            state = StateStore(tmpPath("state")),
            log = ReconcileLog(),
            ssh = SshPool(tmpPath("ssh"), tmpPath("kh")),
            localHttp = HttpClient(MockEngine { respondOk() }),
            dryRun = false,
            diff = DiffCollector(),
        )
        val (resource, sites) = ExitNodes.resources(config, mapOf("na1" to "exit-na1"), emptyMap()).entries.single()
        val blueprint = ResourceReconciler(context).buildBlueprint(emptyMap(), mapOf(resource to sites))
        val exit = blueprint.getValue("private-resources").jsonObject.getValue("hg-exit-na1").jsonObject
        assertEquals("exit-node", exit.getValue("mode").jsonPrimitive.content)
        assertEquals(listOf("exit-na1"), exit.getValue("sites").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Member"), exit.getValue("roles").jsonArray.map { it.jsonPrimitive.content })
        assertNull(exit["destination"])
        assertNull(exit["alias"])
        assertNull(exit["tcp-ports"])
    }

    @Test
    fun connectorRunsOnTheHostNetworkAgainstLoopback() {
        val compose = ExitNodes.compose("docker.io/fosrl/newt:1.20.0")
        assertTrue("network_mode: \"host\"" in compose, compose)
        assertTrue("docker.io/fosrl/newt:1.20.0" in compose)
        assertEquals("PANGOLIN_ENDPOINT=http://127.0.0.1:3000\nNEWT_ID=id\nNEWT_SECRET=s\n", ExitNodes.env("id", "s"))
        val script = ExitNodes.deployScript("/opt/homegap", image = "img", newtId = "id", newtSecret = "s")
        assertTrue("/opt/homegap/exit/compose.yml" in script && "/opt/homegap/exit/.env" in script)
        assertFalse(
            "NEWT_SECRET=s" in script,
            "the secret travels base64-encoded, never in the clear on the command line",
        )
        assertTrue("rm -rf '/opt/homegap/exit'" in ExitNodes.teardownScript("/opt/homegap"))
    }

    @Test
    fun theWireGuardEndpointIsPinnedToThePublishedAddresses() {
        // Pangolin gives the connector the management host as its WireGuard endpoint.
        val pins = listOf(
            "pangolin-na1.test.example.net" to "203.0.113.10",
            "pangolin-na1.test.example.net" to "2001:db8::10",
        )
        val compose = ExitNodes.compose("img", pins)
        assertTrue("extra_hosts" in compose, compose)
        assertTrue("pangolin-na1.test.example.net=203.0.113.10" in compose, compose)
        assertTrue("pangolin-na1.test.example.net=2001:db8::10" in compose, compose)
        assertFalse("pangolin-na1" in ExitNodes.compose("img"), "no pins, no entry")
        // Newt's version check goes nowhere.
        assertTrue("api.fossorial.io=127.0.0.1" in ExitNodes.compose("img"))
    }

    @Test
    fun noHomelabExitWhileNoConfiguredNodeHasAHomelabSite() {
        // The only homelab site is of a node that left the config.
        val config = exitConfig("    homelab: true\n")
        assertTrue(ExitNodes.resources(config, emptyMap(), mapOf("gone1" to "homelab-gone1")).isEmpty())
    }
}
