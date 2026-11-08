package homegap

import homegap.clients.PatroniClient
import homegap.config.MeshConfig
import homegap.config.NodeConfig
import homegap.provision.ClusterSecrets
import homegap.provision.ClusterTemplates
import homegap.provision.ClusterTemplates.Member
import homegap.provision.FirewallRules
import homegap.reconcile.SwitchBack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClusterTest {
    private val secrets = ClusterSecrets("pgpass", "replpass", "valkeypass", "restpass")
    private val homelab = Member("homelab", "10.77.0.1")
    private val na1 = Member("na1", "10.77.0.10")
    private val eu1 = Member("eu1", "10.77.0.11")

    @Test
    fun etcdStartsTheVoteOnceAndJoinsAfterwards() {
        val first = ClusterTemplates.etcdEnv(homelab, listOf(homelab), "new")
        assertTrue("ETCD_INITIAL_CLUSTER=homelab=http://10.77.0.1:2380" in first)
        assertTrue("ETCD_INITIAL_CLUSTER_STATE=new" in first)
        assertTrue("ETCD_LISTEN_CLIENT_URLS=http://10.77.0.1:2379,http://127.0.0.1:2379" in first, "mesh and loopback only")

        val joining = ClusterTemplates.etcdEnv(na1, listOf(homelab, na1), "existing")
        assertTrue("ETCD_INITIAL_CLUSTER=homelab=http://10.77.0.1:2380,na1=http://10.77.0.10:2380" in joining)
        assertTrue("ETCD_INITIAL_CLUSTER_STATE=existing" in joining)
    }

    @Test
    fun patroniUsesItsOwnEtcdAndListensOnTheMeshOnly() {
        val yml = ClusterTemplates.patroni(na1, MeshConfig(), secrets)
        assertTrue("hosts: 127.0.0.1:2379" in yml, "a member cut off from the vote loses etcd and demotes itself")
        assertTrue("listen: 10.77.0.10,127.0.0.1:5432" in yml)
        assertTrue("listen: 10.77.0.10:8008" in yml)
        assertTrue("host all all 10.77.0.0/24 scram-sha-256" in yml)
        assertTrue("password: \"restpass\"" in yml && "use_pg_rewind: true" in yml)
    }

    @Test
    fun valkeyAndSentinelPreferTheHomelab() {
        val env = ClusterTemplates.valkeyEnv(na1, secrets, 100, homelab.meshIp, listOf("10.77.0.1", "10.77.0.10"))
        assertTrue("HG_INITIAL_MASTER=10.77.0.1" in env)
        assertTrue("HG_SENTINELS='10.77.0.1 10.77.0.10'" in env)
        val sentinel = ClusterTemplates.sentinel(na1, homelab.meshIp, secrets)
        assertTrue("sentinel monitor homegap 10.77.0.1 6379 2" in sentinel)
        assertTrue("sentinel announce-ip 10.77.0.10" in sentinel)
    }

    @Test
    fun haproxyListsEveryMemberForBothPrimaries() {
        val cfg = ClusterTemplates.haproxy(listOf(homelab, na1, eu1), secrets)
        assertTrue("server na1 10.77.0.10:5432 check port 8008" in cfg)
        assertTrue("server eu1 10.77.0.11:6379 check" in cfg)
        assertTrue("option httpchk OPTIONS /primary" in cfg)
        assertTrue("expect string role:master" in cfg)
        assertTrue("on-marked-down shutdown-sessions" in cfg, "connections to a demoted member are cut")
    }

    @Test
    fun nodeComposeRecreatesOnlyWhatChanged() {
        val compose = ClusterTemplates.nodeCompose("example/cluster:1", mapOf("db" to "abc", "haproxy" to "def"))
        assertTrue("org.homegap.config: \"abc\"" in compose && "org.homegap.config: \"def\"" in compose)
        assertTrue("network_mode: host" in compose)
        assertTrue("aliases: [cluster-proxy]" in compose)
        val etcd = compose.substringAfter("  etcd:").substringBefore("  db:")
        assertFalse("org.homegap.config" in etcd, "etcd's settings only matter on first start")
    }

    @Test
    fun switchesBackOnlyAfterTheHomelabHasCaughtUpForTheHold() {
        fun m(name: String, role: String, state: String, lag: Long? = 0) = PatroniClient.Member(name, role, state, lag, 2)
        val hold = 60_000L
        val away = listOf(m("na1", "leader", "running", null), m("homelab", "replica", "streaming", 0), m("eu1", "replica", "streaming", 0))

        val first = SwitchBack.evaluate(away, "homelab", null, 1_000, hold)
        assertEquals(1_000, first.caughtUpSince)
        assertNull(first.switchFrom)
        assertTrue("switching back in 1 min" in first.note!!)

        val due = SwitchBack.evaluate(away, "homelab", 1_000, 61_000, hold)
        assertEquals("na1", due.switchFrom)

        val lagging = SwitchBack.evaluate(away.map { if (it.name == "homelab") it.copy(lagBytes = 4096) else it }, "homelab", 1_000, 61_000, hold)
        assertNull(lagging.caughtUpSince, "lag restarts the hold")
        assertNull(lagging.switchFrom)

        val home = SwitchBack.evaluate(listOf(m("homelab", "leader", "running", null), m("na1", "replica", "streaming")), "homelab", 1_000, 61_000, hold)
        assertNull(home.switchFrom)
        assertNull(home.note)
    }

    @Test
    fun nodeFirewallAdmitsTheMesh() {
        val node = NodeConfig(id = "eu1", host = "203.0.113.30", sshKeyFile = "/k")
        val specs = FirewallRules.specs(node, meshPort = 51821)
        assertTrue("allow in on hg-mesh" in specs)
        assertTrue("allow in on hg-cluster" in specs, "the node's own containers reach its cluster services")
        assertTrue("com.docker.network.bridge.name: hg-cluster" in ClusterTemplates.nodeCompose("x", emptyMap()))
    }
}
