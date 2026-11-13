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
        val window = since ?: DEFAULT_SINCE
        val script = Scripts.logs(controller.config.pangolin.installDir, service, window, tail ?: DEFAULT_TAIL)
        val result = ssh.session(node).runScript(script, LOGS_TIMEOUT_SECONDS)
        val text = (result.stdout + result.stderr).ifBlank { NO_OUTPUT }
        return NodeTextDto(nodeId, "$service logs (since $window)", text, nowMillis())
    }

    suspend fun bundle(nodeId: String): NodeTextDto {
        val node = requireNotNull(controller.config.node(nodeId)) { "unknown node $nodeId" }
        val script = Scripts.bundle(controller.config.pangolin.installDir)
        val result = ssh.session(node).runScript(script, BUNDLE_TIMEOUT_SECONDS)
        return NodeTextDto(nodeId, "Diagnostics", (result.stdout + result.stderr).ifBlank { NO_OUTPUT }, nowMillis())
    }

    /** The scripts for the node. They are pure functions, so tests can check them. */
    object Scripts {
        private val SINCE = Regex("^[0-9]{1,4}[smh]$")
        private val STACK = setOf("pangolin", "gerbil", "traefik")
        private val CLUSTER = setOf("haproxy", "db", "valkey", "sentinel", "etcd", MeshProbe.SERVICE)
        private const val MAX_TAIL = 5000
        private const val COMPOSE_PS = "timeout 20 docker compose ps -a"
        private const val COMPOSE_LOGS = "timeout 20 docker compose logs --no-color --timestamps --tail"

        /** The directory of the compose project of [service] under [installDir]. */
        fun projectDir(installDir: String, service: String): String = when (service) {
            in STACK -> installDir
            in CLUSTER -> "$installDir/cluster"
            "newt" -> "$installDir/${ExitNodes.DIR}"
            else -> {
                val known = NodeLogServices.ALL.joinToString { it.first }
                throw IllegalArgumentException("unknown service $service (one of $known)")
            }
        }

        fun logs(installDir: String, service: String, since: String, tail: Int): String {
            require(SINCE.matches(since)) { "since must look like 30m, 2h or 90s" }
            require(tail in 1..MAX_TAIL) { "tail must be 1-5000" }
            val dir = shQuote(projectDir(installDir, service))
            return listOf(
                "cd $dir 2>/dev/null || { echo 'not on this node'; exit 0; }",
                "docker compose logs --no-color --timestamps --since $since --tail $tail $service 2>&1 || true",
            ).joinToString("\n")
        }

        fun bundle(installDir: String): String = buildString {
            val stackDir = shQuote(installDir)
            val clusterDir = shQuote("$installDir/cluster")
            val exitDir = shQuote("$installDir/${ExitNodes.DIR}")
            fun section(title: String, command: String) {
                appendLine("echo; echo '=== $title'")
                appendLine("( $command ) 2>&1 | head -n 400 || true")
            }
            appendLine("set +e +o pipefail")
            appendLine("echo \"Collected $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(hostname)\"")
            section(
                "System",
                $$"uname -srm; uptime; . /etc/os-release && echo \"$PRETTY_NAME\"; free -m; df -h / $$stackDir",
            )
            section("Pangolin stack", "cd $stackDir && $COMPOSE_PS")
            section("Cluster member", "cd $clusterDir 2>/dev/null && $COMPOSE_PS || echo 'no cluster member'")
            section("Exit connector", "cd $exitDir 2>/dev/null && $COMPOSE_PS || echo 'no exit connector'")
            section("Cluster proxy (HAProxy backends)", ClusterProxy.STATS_CMD)
            section(
                "Mesh interface",
                "wg show ${Mesh.INTERFACE} 2>/dev/null | grep -v 'private key' || echo 'no mesh interface'",
            )
            section("Mesh probe", MeshProbe.READ_CMD)
            section("Addresses and routes", "ip -br -4 addr; echo; ip -4 route")
            section(
                "Docker networks",
                "docker network ls; " +
                    "docker network inspect ${ClusterTemplates.NETWORK} --format '$NETWORK_FORMAT' 2>/dev/null",
            )
            section("Listening ports", "ss -tulpn")
            section("Firewall (ufw)", "ufw status verbose 2>/dev/null || echo 'ufw not installed'")
            section("NAT rules", "iptables -t nat -S 2>/dev/null")
            section("Forwarding rules", "iptables -S FORWARD 2>/dev/null; iptables -S DOCKER-USER 2>/dev/null")
            section("Connection tracking", "echo \"entries: $CONNTRACK_COUNT of $CONNTRACK_MAX\"")
            section("Kernel messages", "dmesg -T 2>/dev/null | tail -n 30")
            section("Pangolin log (last 60 lines)", "cd $stackDir && $COMPOSE_LOGS 60 pangolin")
            section("Gerbil log (last 40 lines)", "cd $stackDir && $COMPOSE_LOGS 40 gerbil")
            section("Cluster proxy log (last 40 lines)", "cd $clusterDir 2>/dev/null && $COMPOSE_LOGS 40 haproxy")
            appendLine("exit 0")
        }

        /** The Go template for `docker network inspect`: the name, the bridge and the subnets. */
        private val NETWORK_FORMAT = listOf(
            "{{.Name}}",
            "{{index .Options \"com.docker.network.bridge.name\"}}",
            "{{range .IPAM.Config}}{{.Subnet}}{{end}}",
        ).joinToString(" ")

        private const val CONNTRACK_COUNT = "$(cat /proc/sys/net/netfilter/nf_conntrack_count 2>/dev/null)"
        private const val CONNTRACK_MAX = "$(cat /proc/sys/net/netfilter/nf_conntrack_max 2>/dev/null)"
    }

    private companion object {
        const val DEFAULT_SINCE = "30m"
        const val DEFAULT_TAIL = 300
        const val LOGS_TIMEOUT_SECONDS = 60
        const val BUNDLE_TIMEOUT_SECONDS = 180
        const val NO_OUTPUT = "(no output)"
    }
}
