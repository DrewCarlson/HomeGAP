package homegap.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.NodeLogServices
import homegap.api.NodeTextDto
import homegap.clients.SshPool
import homegap.clients.SshSession.Companion.shQuote
import homegap.platform.nowMillis
import homegap.provision.ClusterProxy
import homegap.provision.ClusterTemplates
import homegap.provision.ExitNodes
import homegap.provision.Mesh
import homegap.provision.MeshProbe
import homegap.reconcile.Controller

/**
 * Read-only views of a node for debugging: a container's logs, and a bundle of the node's state
 * (containers, mesh, firewall, NAT, ports, the cluster proxy and recent logs). Nothing here
 * changes the node, and nothing prints a secret.
 */
@Inject
@SingleIn(AppScope::class)
class NodeDiagnostics(private val controller: Controller, private val ssh: SshPool) {
    suspend fun logs(nodeId: String, service: String, since: String?, tail: Int?): NodeTextDto {
        val node = requireNotNull(controller.config.node(nodeId)) { "unknown node $nodeId" }
        val script = Scripts.logs(controller.config.pangolin.installDir, service, since ?: "30m", tail ?: 300)
        val r = ssh.session(node).runScript(script, 60)
        val text = (r.stdout + r.stderr).ifBlank { "(no output)" }
        return NodeTextDto(nodeId, "$service logs (since ${since ?: "30m"})", text, nowMillis())
    }

    suspend fun bundle(nodeId: String): NodeTextDto {
        val node = requireNotNull(controller.config.node(nodeId)) { "unknown node $nodeId" }
        val r = ssh.session(node).runScript(Scripts.bundle(controller.config.pangolin.installDir), 180)
        return NodeTextDto(nodeId, "Diagnostics", (r.stdout + r.stderr).ifBlank { "(no output)" }, nowMillis())
    }

    /** The node scripts, kept pure so tests can check them. */
    object Scripts {
        private val SINCE = Regex("^[0-9]{1,4}[smh]$")
        private val STACK = setOf("pangolin", "gerbil", "traefik")
        private val CLUSTER = setOf("haproxy", "db", "valkey", "sentinel", "etcd", MeshProbe.SERVICE)

        /** Where [service]'s compose project lives under [installDir]. */
        fun projectDir(installDir: String, service: String): String = when (service) {
            in STACK -> installDir
            in CLUSTER -> "$installDir/cluster"
            "newt" -> "$installDir/${ExitNodes.DIR}"
            else -> throw IllegalArgumentException("unknown service $service (one of ${NodeLogServices.ALL.joinToString { it.first }})")
        }

        fun logs(installDir: String, service: String, since: String, tail: Int): String {
            require(SINCE.matches(since)) { "since must look like 30m, 2h or 90s" }
            require(tail in 1..5000) { "tail must be 1-5000" }
            val dir = shQuote(projectDir(installDir, service))
            return "cd $dir 2>/dev/null || { echo 'not on this node'; exit 0; }\n" +
                "docker compose logs --no-color --timestamps --since $since --tail $tail $service 2>&1 || true"
        }

        fun bundle(installDir: String): String = buildString {
            val d = shQuote(installDir)
            val c = shQuote("$installDir/cluster")
            val e = shQuote("$installDir/${ExitNodes.DIR}")
            fun section(title: String, cmd: String) {
                appendLine("echo; echo '=== $title'")
                appendLine("( $cmd ) 2>&1 | head -n 400 || true")
            }
            appendLine("set +e +o pipefail")
            appendLine("echo \"Collected $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(hostname)\"")
            section("System", "uname -srm; uptime; . /etc/os-release && echo \"\$PRETTY_NAME\"; free -m; df -h / $d")
            section("Pangolin stack", "cd $d && timeout 20 docker compose ps -a")
            section("Cluster member", "cd $c 2>/dev/null && timeout 20 docker compose ps -a || echo 'no cluster member'")
            section("Exit connector", "cd $e 2>/dev/null && timeout 20 docker compose ps -a || echo 'no exit connector'")
            section("Cluster proxy (HAProxy backends)", ClusterProxy.STATS_CMD)
            section("Mesh interface", "wg show ${Mesh.INTERFACE} 2>/dev/null | grep -v 'private key' || echo 'no mesh interface'")
            section("Mesh probe", MeshProbe.READ_CMD)
            section("Addresses and routes", "ip -br -4 addr; echo; ip -4 route")
            section("Docker networks", "docker network ls; docker network inspect ${ClusterTemplates.NETWORK} --format '{{.Name}} {{index .Options \"com.docker.network.bridge.name\"}} {{range .IPAM.Config}}{{.Subnet}}{{end}}' 2>/dev/null")
            section("Listening ports", "ss -tulpn")
            section("Firewall (ufw)", "ufw status verbose 2>/dev/null || echo 'ufw not installed'")
            section("NAT rules", "iptables -t nat -S 2>/dev/null")
            section("Forwarding rules", "iptables -S FORWARD 2>/dev/null; iptables -S DOCKER-USER 2>/dev/null")
            section("Connection tracking", "echo \"entries: $(cat /proc/sys/net/netfilter/nf_conntrack_count 2>/dev/null) of $(cat /proc/sys/net/netfilter/nf_conntrack_max 2>/dev/null)\"")
            section("Kernel messages", "dmesg -T 2>/dev/null | tail -n 30")
            section("Pangolin log (last 60 lines)", "cd $d && timeout 20 docker compose logs --no-color --timestamps --tail 60 pangolin")
            section("Gerbil log (last 40 lines)", "cd $d && timeout 20 docker compose logs --no-color --timestamps --tail 40 gerbil")
            section("Cluster proxy log (last 40 lines)", "cd $c 2>/dev/null && timeout 20 docker compose logs --no-color --timestamps --tail 40 haproxy")
            appendLine("exit 0")
        }
    }
}
