package homegap

import homegap.clients.PatroniClient
import homegap.clients.ValkeyCli
import homegap.config.ClusterConfig
import homegap.config.MeshConfig
import homegap.config.NodeConfig
import homegap.config.PangolinNodeDefaults
import homegap.model.NodeDesired
import homegap.model.Planner
import homegap.platform.runCatchingCancellable
import homegap.provision.ClusterLink
import homegap.provision.ClusterSecrets
import homegap.provision.ClusterTemplates
import homegap.provision.ClusterTemplates.Member
import homegap.provision.FirewallRules
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.reconcile.DepartedSentinels
import homegap.reconcile.ResourceReconciler
import homegap.reconcile.SwitchBack
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The addresses on the mesh are plain HTTP, and they are the values under test.
@Suppress("HttpUrlsUsage")
class ClusterTest {
    private val secrets = ClusterSecrets(
        postgres = "pgpass",
        replication = "replpass",
        valkey = "valkeypass",
        rest = "restpass",
        pangolin = "pangolinpass",
        rewind = "rewindpass",
        etcd = "etcdpass",
        sentinel = "sentinelpass",
    )
    private val homelab = Member("homelab", "10.77.0.1")
    private val na1 = Member("na1", "10.77.0.10")
    private val eu1 = Member("eu1", "10.77.0.11")
    private val na1Link = ClusterLink("10.77.0.10", secrets)
    private val clusterNodeSecrets = NodeSecrets(
        setupToken = "t".repeat(32),
        serverSecret = "server",
        adminEmail = "admin@test.example.net",
        adminPassword = "pw",
        crowdsecBouncerKey = "bk",
        cluster = true,
    )

    @Test
    fun etcdStartsTheVoteOnceAndJoinsAfterwards() {
        val first = ClusterTemplates.etcdEnv(homelab, listOf(homelab), "new")
        assertTrue("ETCD_INITIAL_CLUSTER=homelab=http://10.77.0.1:2380" in first)
        assertTrue("ETCD_INITIAL_CLUSTER_STATE=new" in first)
        assertTrue(
            "ETCD_LISTEN_CLIENT_URLS=http://10.77.0.1:2379,http://127.0.0.1:2379" in first,
            "mesh and loopback only",
        )

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
        assertTrue("host pangolin pangolin 10.77.0.0/24 scram-sha-256" in yml)
        // The superuser connects only from inside the member, never over the mesh.
        assertTrue("host all postgres 127.0.0.1/32 scram-sha-256" in yml)
        assertFalse("host all all" in yml)
        assertTrue("rewind: { username: rewinder, password: \"rewindpass\" }" in yml)
        assertTrue("post_bootstrap: /usr/local/bin/hg-member post-bootstrap" in yml)
        assertTrue("CREATE DATABASE pangolin OWNER pangolin;" in ClusterTemplates.rolesSql(secrets))
        assertTrue("password: \"restpass\"" in yml && "use_pg_rewind: true" in yml)
    }

    @Test
    fun valkeyAndSentinelPreferTheHomelab() {
        val env = ClusterTemplates.valkeyEnv(
            na1,
            secrets,
            priority = 100,
            initialMaster = homelab.meshIp,
            sentinels = listOf("10.77.0.1", "10.77.0.10"),
        )
        assertTrue("HG_INITIAL_MASTER=10.77.0.1" in env)
        assertTrue("HG_SENTINELS='10.77.0.1 10.77.0.10'" in env)
        val sentinel = ClusterTemplates.sentinel(na1, homelab.meshIp, secrets)
        assertTrue("sentinel monitor homegap 10.77.0.1 6379 2" in sentinel)
        assertTrue("sentinel announce-ip 10.77.0.10" in sentinel)
        assertTrue("requirepass sentinelpass" in sentinel, "Sentinel has its own password, not the Valkey password")
        val patroni = ClusterTemplates.patroni(na1, MeshConfig(), secrets)
        assertTrue("username: root" in patroni && "password: \"etcdpass\"" in patroni, patroni)
        val valkey = ClusterTemplates.valkeyEnv(
            na1,
            secrets,
            priority = 100,
            initialMaster = homelab.meshIp,
            sentinels = listOf(homelab.meshIp),
        )
        assertTrue("HG_SENTINEL_PASSWORD=sentinelpass" in valkey)
    }

    @Test
    fun haproxyListsEveryMemberForBothPrimaries() {
        val haproxy = ClusterTemplates.haproxy(listOf(homelab, na1, eu1), secrets)
        assertTrue("server na1 10.77.0.10:5432 check port 8008" in haproxy)
        assertTrue("server eu1 10.77.0.11:6379 check" in haproxy)
        assertTrue("option httpchk OPTIONS /primary" in haproxy)
        assertTrue("expect string role:master" in haproxy)
        assertTrue("on-marked-down shutdown-sessions" in haproxy, "connections to a demoted member are cut")
        // A 250 ms link needs about a second for the Valkey check and must survive a lost SYN.
        assertTrue(
            "timeout check 5s" in haproxy && "inter 4s fastinter 1s fall 3" in haproxy,
            "checks tolerate distant members",
        )
        assertTrue("send info\\ replication\\r\\nQUIT\\r\\n" in haproxy, "a replica fails its check at once")
        // HAProxy reloads in place: the new workers start from the view that the old workers have of each server.
        assertTrue("stats socket /tmp/haproxy.sock mode 600 level admin" in haproxy)
        assertTrue("server-state-file /tmp/haproxy.state" in haproxy && "load-server-state-from-file global" in haproxy)
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
        fun member(name: String, role: String, state: String, lag: Long? = 0) =
            PatroniClient.Member(name, role, state, lagBytes = lag, timeline = 2)
        val hold = 60_000L
        val away = listOf(
            member("na1", "leader", "running", null),
            member("homelab", "replica", "streaming", 0),
            member("eu1", "replica", "streaming", 0),
        )

        val first = SwitchBack.evaluate(away, "homelab", caughtUpSince = null, now = 1_000, holdMs = hold)
        assertEquals(1_000, first.caughtUpSince)
        assertNull(first.switchFrom)
        assertTrue("switching back in 1 min" in first.note!!)

        val due = SwitchBack.evaluate(away, "homelab", caughtUpSince = 1_000, now = 61_000, holdMs = hold)
        assertEquals("na1", due.switchFrom)

        val behind = away.map { if (it.name == "homelab") it.copy(lagBytes = 4096) else it }
        val lagging = SwitchBack.evaluate(behind, "homelab", caughtUpSince = 1_000, now = 61_000, holdMs = hold)
        assertNull(lagging.caughtUpSince, "lag restarts the hold")
        assertNull(lagging.switchFrom)

        val atHome = listOf(member("homelab", "leader", "running", null), member("na1", "replica", "streaming"))
        val home = SwitchBack.evaluate(atHome, "homelab", caughtUpSince = 1_000, now = 61_000, holdMs = hold)
        assertNull(home.switchFrom)
        assertNull(home.note)
    }

    @Test
    fun nodeFirewallAdmitsTheMesh() {
        val node = NodeConfig(id = "eu1", host = "203.0.113.30", sshKeyFile = "/k")
        val specs = FirewallRules.specs(node, meshPort = 51821)
        assertTrue("allow in on hg-mesh to any port 5432 proto tcp" in specs)
        assertTrue("allow in on hg-mesh to any port 3004 proto tcp" in specs)
        assertTrue("allow in on hg-mesh" !in specs, "the mesh reaches only the cluster ports")
        // The containers of the node reach the database, Valkey and the Patroni check, and no control port.
        assertEquals(
            listOf(
                "allow in on hg-cluster to any port 5432 proto tcp",
                "allow in on hg-cluster to any port 6379 proto tcp",
                "allow in on hg-cluster to any port 8008 proto tcp",
            ),
            specs.filter { "hg-cluster" in it },
        )
        assertEquals(
            "DOCKER-USER -o hg-mesh -p tcp -m multiport --dports 2380,26379 -j DROP",
            FirewallRules.containerGuard,
        )
        assertTrue("com.docker.network.bridge.name: hg-cluster" in ClusterTemplates.nodeCompose("x", emptyMap()))
    }

    @Test
    fun aClusterRunsThePostgresqlBuildOfPangolin() {
        assertEquals(
            "docker.io/fosrl/pangolin:ee-postgresql-1.26.0",
            PangolinNodeDefaults().pangolinImage(cluster = true),
        )
        assertEquals("docker.io/fosrl/pangolin:ee-1.26.0", PangolinNodeDefaults().pangolinImage(cluster = false))
        val pinned = PangolinNodeDefaults(pangolinImage = "registry.example:5000/pangolin:ee-postgresql-1.27.0")
        assertEquals("registry.example:5000/pangolin:ee-postgresql-1.27.0", pinned.pangolinImage(cluster = true))
    }

    @Test
    fun clusteredNodesRunTheMaintenancePageFromTheClusterImage() {
        val config = testConfig().copy(cluster = ClusterConfig(image = "example/cluster:7"))
        val files = NodeTemplates.staticFiles(
            config,
            config.node("na1")!!,
            clusterNodeSecrets,
            rawPorts = emptyList(),
            slot = 0,
            cluster = na1Link,
        )
        val page = files.single { it.path == "compose.yml" }
            .content
            .substringAfter("  maintenance:")
            .substringBefore("\n  traefik:")
        assertTrue("image: \"example/cluster:7\"" in page, "no extra image on a clustered node")
        assertFalse("python:3.13-alpine" in page)
        assertTrue(
            "entrypoint:" in page && "\"/maintenance/server.py\"" in page,
            "replaces the cluster image's hg-member entrypoint",
        )
    }

    @Test
    fun clusterStackSharesTheDatabaseAndKeepsGerbilOffThePublicInternet() {
        val config = testConfig()
        val node = config.node("na1")!!
        val files = NodeTemplates.staticFiles(
            config,
            node,
            clusterNodeSecrets,
            rawPorts = emptyList(),
            slot = 0,
            cluster = na1Link,
        ).associateBy { it.path }

        val compose = files.getValue("compose.yml").content
        assertTrue("ee-postgresql-" in compose)
        assertTrue(
            "\"--reachableAt=http://10.77.0.10:3004\"" in compose,
            "other nodes reach this Gerbil over the mesh",
        )
        assertTrue("\"10.77.0.10:3004:3004\"" in compose, "Gerbil's API is published on the mesh address only")
        assertFalse("\"3004:3004\"" in compose.replace("\"10.77.0.10:3004:3004\"", ""))
        assertTrue("name: \"homegap-cluster\"" in compose && "external: true" in compose)
        assertTrue("\"./config/traefik/dynamic:/var/dynamic\"" in compose)

        val pangolin = files.getValue("config/config.yml").content
        assertTrue(
            "postgresql://pangolin:pangolinpass@cluster-proxy:5433/pangolin" in pangolin,
            "writes go to the primary",
        )
        assertTrue(
            "postgresql://pangolin:pangolinpass@10.77.0.10:5432/pangolin" in pangolin,
            "reads stay on the node",
        )
        assertTrue("file_mode: true" in pangolin && "router_config.yml" in pangolin)
        assertTrue("disable_local_sites: true" in pangolin)

        val private = files.getValue("config/privateConfig.yml")
        assertEquals("0600", private.mode)
        assertTrue("region: \"na1\"" in private.content || "region: na1" in private.content)
        assertTrue("valkeypass" in private.content && "6380" in private.content)

        val traefik = files.getValue("config/traefik/traefik_config.yml").content
        assertFalse("traefik-config" in traefik, "Traefik reads Pangolin's file, never its HTTP endpoint")

        val desired = Planner.plan(config, listOf(TRAEFIK)).nodes.getValue("na1")
        val routes = NodeTemplates.dynamicRoutes(desired, mapOf(Planner.FRONTDOOR_TCP to 7), cluster = true)
        assertTrue("-service@file" in routes && "@http\"" !in routes)
    }

    @Test
    fun standaloneStackIsUnchanged() {
        val config = testConfig()
        val files = NodeTemplates.staticFiles(
            config,
            config.node("na1")!!,
            NodeSecrets("t", "s", "a@b", "p", "k"),
            rawPorts = emptyList(),
            slot = 0,
        ).associateBy { it.path }
        assertNull(files["config/privateConfig.yml"])
        assertTrue("traefik-config" in files.getValue("config/traefik/traefik_config.yml").content)
        assertFalse("postgres" in files.getValue("config/config.yml").content)
        assertTrue("\"--reachableAt=http://gerbil:3004\"" in files.getValue("compose.yml").content)
    }

    private val clusterConfig = testConfig().copy(cluster = ClusterConfig())
    private val services = listOf(
        TRAEFIK,
        container(
            "git",
            "homegap.expose" to "git.test.example.net",
            "homegap.protocol" to "https",
            "homegap.tls" to "passthrough",
            "homegap.regions" to "as1",
        ),
        container(
            "game",
            "homegap.expose" to "game.test.example.net",
            "homegap.port" to "41000",
            "homegap.protocol" to "tcp",
            "homegap.regions" to "na1",
        ),
        container(
            "ssh",
            "homegap.expose" to "ssh.test.example.net",
            "homegap.port" to "22",
            "homegap.protocol" to "tcp",
            "homegap.access" to "private",
        ),
    )

    @Test
    fun clusterNodesAgreeOnSharedResourcesAndKeepTheirOwn() {
        val plan = Planner.plan(clusterConfig, services)
        val na1 = plan.nodes.getValue("na1")
        val as1 = plan.nodes.getValue("as1")
        // A passthrough port is the same on every node, whatever else each node serves.
        fun frontDoorPorts(node: NodeDesired) =
            node.publicResources.filter { it.niceId == Planner.FRONTDOOR_TCP }.map { it.proxyPort }
        assertEquals(frontDoorPorts(na1), frontDoorPorts(as1))
        val shared = (na1.publicResources + as1.publicResources)
            .filter { (it.proxyPort ?: 0) > 0 }
            .groupBy { it.niceId }
        val onePortEach = shared.values.all { resources -> resources.map { it.proxyPort }.distinct().size == 1 }
        assertTrue(onePortEach, "each resource has one port on every node")
        val byPort = shared.mapValues { it.value.first().proxyPort }
        assertEquals(byPort.size, byPort.values.distinct().size, "no two resources share a port")

        // Health checks are per node.
        assertEquals("hg-health-na1", na1.healthResourceNiceId)
        assertEquals("hg-health-as1", as1.healthResourceNiceId)
        assertTrue(as1.publicResources.none { it.niceId == "hg-health-na1" })

        // Every node serves the client address and never passes it through to the homelab.
        assertEquals("pangolin.test.example.net", na1.clientHost)
        val frontDoor = na1.sniRoutes.single { it.resourceNiceId == Planner.FRONTDOOR_TCP }
        assertTrue(frontDoor.excludeHosts.contains("pangolin.test.example.net"))
        val client = plan.dns.filter { it.purpose == "client" }
        assertEquals(setOf("na1", "as1"), client.map { it.nodeId }.toSet())
        assertTrue(client.all { it.geo })

        val routes = NodeTemplates.dynamicRoutes(na1, emptyMap(), cluster = true)
        assertTrue("Host(`pangolin.test.example.net`) && PathPrefix(`/api/v1`)" in routes)
    }

    @Test
    fun aSharedResourceTargetsEverySiteThatServesIt() {
        val plan = Planner.plan(clusterConfig, services)
        val sites = mapOf("na1" to "homelab-na1", "as1" to "homelab-as1")
        val publicByNode = plan.nodes.mapValues { it.value.publicResources }
        val public = ResourceReconciler.clusterResources(publicByNode, sites) { it.niceId }
            .mapKeys { it.key.niceId }
        assertEquals(listOf("homelab-na1", "homelab-as1"), public.getValue(Planner.FRONTDOOR_TCP))
        assertEquals(listOf("homelab-as1"), public.getValue("hg-git-test-example-net"), "regions limit the targets")
        assertEquals(listOf("homelab-na1"), public.getValue("hg-health-na1"))

        val privateByNode = plan.nodes.mapValues { it.value.privateResources }
        val private = ResourceReconciler.clusterResources(privateByNode, sites) { it.niceId }
        assertEquals(listOf("homelab-na1", "homelab-as1"), private.values.single())

        // A node that has no site yet adds no target.
        val oneSite = mapOf("na1" to "homelab-na1")
        val partial = ResourceReconciler.clusterResources(publicByNode, oneSite) { it.niceId }
        assertTrue(partial.values.all { it == listOf("homelab-na1") })
    }

    @Test
    fun clusterPangolinLinksToTheClientAddress() {
        val node = clusterConfig.node("na1")!!
        val nodeSecrets = NodeSecrets("t", "s", "a@b", "p", "k", cluster = true)
        val yml = NodeTemplates.pangolinConfig(clusterConfig, node, nodeSecrets, slot = 0, cluster = na1Link)
        assertTrue("dashboard_url: \"https://pangolin.test.example.net\"" in yml)
        assertTrue("https://pangolin-na1.test.example.net" in yml, "the management host stays an allowed origin")
    }

    @Test
    fun sentinelOutputIsReadAsOneMapPerInstance() {
        val down = "name\nabc\nip\n10.77.0.12\nport\n26379\nflags\ns_down,sentinel\n"
        val up = "name\ndef\nip\n10.77.0.10\nport\n26379\nflags\nsentinel\n"
        val fields = ValkeyCli.fields(down + up)
        assertEquals(listOf("10.77.0.12", "10.77.0.10"), fields.map { it["ip"] })
        assertEquals("s_down,sentinel", fields[0]["flags"])
        assertTrue(ValkeyCli.fields("").isEmpty(), "no replicas or Sentinels")
    }

    @Test
    fun aSentinelThatKnowsADepartedMemberIsReset() {
        // fr1 left the cluster: its mesh peer is gone, but the Sentinels never forget it.
        val members = listOf(homelab, na1, eu1)
        fun view(vararg ips: String) = ValkeyCli.SentinelView(setOf("master"), ips.toSet())
        val clean = mapOf(
            "homelab" to view("10.77.0.10", "10.77.0.11"),
            "na1" to view("10.77.0.1", "10.77.0.11"),
            "eu1" to null,
        )
        assertNull(DepartedSentinels.stale(members, clean), "nothing outside the cluster, or unreachable")
        val stale = clean + ("na1" to view("10.77.0.1", "10.77.0.11", "10.77.0.12"))
        assertEquals(na1 to setOf("10.77.0.12"), DepartedSentinels.stale(members, stale))
    }

    @Test
    fun aLearnerIsPromotedThroughTheLeaderOfEtcd() = kotlinx.coroutines.test.runTest {
        // With authentication on, a member that is not the leader forwards the call without the user.
        val asked = mutableListOf<String>()
        fun etcd(host: String, answer: String?) = homegap.clients.EtcdClient(
            io.ktor.client.HttpClient(
                io.ktor.client.engine.mock.MockEngine { request ->
                    asked += "$host ${request.url.encodedPath}"
                    val json = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
                    if (answer == null) {
                        respond("{}", io.ktor.http.HttpStatusCode.OK, json)
                    } else {
                        respond("""{"message":"$answer"}""", io.ktor.http.HttpStatusCode.InternalServerError, json)
                    }
                },
            ),
            "http://$host:2379",
        )
        val forwarded = "auth: user name is empty"
        etcd("10.77.0.1", forwarded).promoteThroughAny(
            "7",
            listOf(etcd("10.77.0.10", null), etcd("10.77.0.12", forwarded)),
        )
        assertEquals(
            listOf("10.77.0.1 /v3/cluster/member/promote", "10.77.0.10 /v3/cluster/member/promote"),
            asked,
            "the call stops at the member that accepts it",
        )

        // No member accepts it. The reason is the answer of the leader, not of a member that forwarded the call.
        val behind = "etcdserver: can only promote a learner member which is in sync with leader"
        val refusal = runCatchingCancellable {
            etcd("10.77.0.1", forwarded).promoteThroughAny(
                "7",
                listOf(etcd("10.77.0.10", behind), etcd("10.77.0.12", forwarded)),
            )
        }.exceptionOrNull()
        assertTrue("in sync with leader" in refusal?.message.orEmpty(), refusal?.message)
    }
}
