package homegap

import homegap.config.ConfigLoader
import homegap.dashboard.DashboardServer
import homegap.discovery.DockerClient
import homegap.model.Planner
import homegap.platform.env
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.reconcile.Controller
import homegap.tunnels.TunnelSupervisor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.curl.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

const val VERSION = "0.1.0"

private val logger by lazy { KotlinLogging.logger("homegap") }

private const val USAGE = """homegap $VERSION — Home Global Access Platform orchestrator

Usage:
  homegap [run] [--config PATH] [--dry-run] [--once]   Run the controller (default)
  homegap tunnels [--dir PATH] [--newt PATH]           Run the Newt tunnel supervisor
  homegap validate [--config PATH]                     Validate the config file
  homegap plan [--config PATH] [--labels FILE]         Print the desired state for a label set (JSON map of container -> labels)
  homegap render-node ID [--config PATH]               Print the rendered stack for a node (secrets redacted)
  homegap version

Environment: HOMEGAP_CONFIG (default /config/config.yml), HOMEGAP_DRY_RUN, DOCKER_SOCKET."""

fun main(args: Array<String>) {
    KotlinLoggingConfiguration.logStartupMessage = false
    val cmd = args.firstOrNull()?.takeIf { !it.startsWith("--") } ?: "run"
    val rest = if (args.firstOrNull() == cmd) args.drop(1) else args.toList()
    fun opt(name: String): String? = rest.indexOf(name).takeIf { it >= 0 }?.let { rest.getOrNull(it + 1) }
    fun flag(name: String) = name in rest
    val configPath = opt("--config") ?: env("HOMEGAP_CONFIG") ?: "/config/config.yml"

    try {
        when (cmd) {
            "version", "--version" -> println(VERSION)
            "help", "--help", "-h" -> println(USAGE)
            "validate" -> {
                val c = ConfigLoader.load(configPath)
                println("OK: ${c.nodes.size} node(s), domain ${c.domain}, zone ${c.bunny.zone}")
            }
            "plan" -> {
                val c = ConfigLoader.load(configPath)
                val labelsFile = opt("--labels") ?: error("--labels FILE required")
                val raw = Json.decodeFromString<Map<String, Map<String, String>>>(homegap.platform.Files.readText(labelsFile))
                val containers = raw.map { (name, labels) -> homegap.discovery.DockerContainerSummary(id = name, names = listOf("/$name"), labels = labels) }
                val plan = Planner.plan(c, containers)
                plan.labelErrors.forEach { println("ERROR ${it.container}: ${it.message}") }
                plan.labelWarnings.forEach { println("WARN  ${it.container}: ${it.message}") }
                for ((id, n) in plan.nodes) {
                    println("node $id (${n.managementHost})")
                    n.publicResources.forEach { println("  public  ${it.niceId}: ${it.mode.wire} ${it.fullDomain ?: ":${it.proxyPort}"} -> ${it.target.host}:${it.target.port}${it.target.method?.let { m -> " ($m)" } ?: ""}") }
                    n.privateResources.forEach { println("  private ${it.niceId}: ${it.alias} -> ${it.destination} tcp=${it.tcpPorts} udp=${it.udpPorts}") }
                    n.sniRoutes.forEach { println("  sni     ${it.name}: ${NodeTemplates.sniRule(it)}") }
                    n.rawPorts.forEach { println("  port    ${it.entryPoint}") }
                }
                plan.dns.forEach { println("dns ${it.type} ${it.fqdn} -> ${it.value} [${it.nodeId}] ${it.purpose}${if (it.geo) " geo" else ""} monitor=${it.monitor}") }
            }
            "render-node" -> {
                val c = ConfigLoader.load(configPath)
                val id = rest.firstOrNull { !it.startsWith("--") && it != opt("--config") } ?: error("node id required")
                val node = c.node(id) ?: error("unknown node $id")
                val secrets = NodeSecrets("<setup-token>", "<server-secret>", NodeTemplates.adminEmail(c), "<password>", "<bouncer-key>")
                val plan = Planner.plan(c, emptyList())
                for (f in NodeTemplates.staticFiles(c, node, secrets, plan.nodes.getValue(id).rawPorts)) {
                    println("=== ${f.path} (${f.mode})")
                    println(f.content)
                }
                println("=== ${NodeTemplates.DYNAMIC_ROUTES}")
                println(NodeTemplates.dynamicRoutes(c, plan.nodes.getValue(id), emptyMap()))
            }
            "tunnels" -> runTunnels(opt("--dir") ?: env("HOMEGAP_TUNNELS_DIR") ?: "/data/tunnels", opt("--newt") ?: env("HOMEGAP_NEWT") ?: "/usr/local/bin/newt")
            "run" -> runController(configPath, flag("--dry-run") || env("HOMEGAP_DRY_RUN")?.lowercase() in setOf("1", "true", "yes"), flag("--once"))
            else -> {
                println(USAGE)
                exitProcess(2)
            }
        }
    } catch (e: Exception) {
        eprintln("homegap: ${e.message}")
        exitProcess(1)
    }
}

private fun eprintln(msg: String) {
    platform.posix.fprintf(platform.posix.stderr, "%s\n", msg)
}

private fun json() = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

private fun runController(configPath: String, dryRun: Boolean, once: Boolean) = runBlocking {
    val config = ConfigLoader.load(configPath)
    logger.info { "homegap $VERSION starting: domain=${config.domain} nodes=${config.nodes.map { it.id }} dryRun=${dryRun || config.reconcile.dryRun}" }

    val localHttp = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        install(HttpTimeout) { connectTimeoutMillis = 10_000 }
        expectSuccess = false
    }
    val externalHttp = HttpClient(Curl) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
        expectSuccess = false
    }
    val docker = DockerClient(localHttp, json(), env("DOCKER_SOCKET") ?: "/var/run/docker.sock")
    val controller = Controller(configPath, config, dryRun, docker, localHttp, externalHttp)

    if (!once) {
        DashboardServer(controller, configPath).start(config.dashboard.host, config.dashboard.port)
        logger.info { "dashboard listening on ${config.dashboard.bind}" }
    }
    controller.run(this, once)
}

private fun runTunnels(dir: String, newt: String) = runBlocking {
    val http = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 3_000; connectTimeoutMillis = 1_000 }
        expectSuccess = false
    }
    TunnelSupervisor(dir, newt, http).run()
}
