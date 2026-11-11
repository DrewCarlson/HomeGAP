package homegap

import dev.zacsweers.metro.createGraphFactory
import homegap.api.ActionResponse
import homegap.config.ConfigLoader
import homegap.di.ControllerGraph
import homegap.di.RunOptions
import homegap.di.TunnelOptions
import homegap.di.TunnelsGraph
import homegap.model.Planner
import homegap.platform.env
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

const val VERSION = "0.1.0"

private val logger by lazy { KotlinLogging.logger("homegap") }

private const val USAGE = """homegap $VERSION — Home Global Access Platform orchestrator

Usage:
  homegap [run] [--config PATH] [--dry-run] [--once]   Run the controller (default)
  homegap tunnels [--dir PATH] [--newt PATH]           Run the Newt tunnel supervisor
  homegap mesh [--dir PATH]                            Run the homelab's mesh agent (cluster mode)
  homegap validate [--config PATH]                     Validate the config file
  homegap plan [--config PATH] [--labels FILE]         Print the desired state for a label set (JSON map of container -> labels)
  homegap render-node ID [--config PATH]               Print the rendered stack for a node (secrets redacted)
  homegap maintenance start [--minutes N] | end       Move the cluster primaries off the homelab (and back) around an upgrade
  homegap version

Environment: HOMEGAP_CONFIG (default /config/config.yml), HOMEGAP_DRY_RUN, DOCKER_SOCKET, DOCKER_HOST,
HOMEGAP_CLUSTER_CONFIG_DIR, HOMEGAP_TUNNELS_DIR, HOMEGAP_MESH_DIR, HOMEGAP_MESH_HOST."""

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
                    n.publicResources.forEach { println("  public  ${it.niceId}: ${it.mode.wire} ${it.fullDomain ?: ":${it.proxyPort}"} -> ${it.target.host}:${it.target.port}${it.target.method?.let { m -> " ($m)" }.orEmpty()}") }
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
                for (f in NodeTemplates.staticFiles(c, node, secrets, plan.nodes.getValue(id).rawPorts, c.nodes.indexOf(node))) {
                    println("=== ${f.path} (${f.mode})")
                    println(f.content)
                }
                println("=== ${NodeTemplates.DYNAMIC_ROUTES}")
                println(NodeTemplates.dynamicRoutes(plan.nodes.getValue(id), emptyMap()))
            }
            "maintenance" -> maintenance(configPath, rest.firstOrNull { it == "start" || it == "end" } ?: error("maintenance start|end"), opt("--minutes")?.toIntOrNull() ?: 30)
            "mesh" -> runBlocking { homegap.mesh.MeshAgent(opt("--dir") ?: env("HOMEGAP_MESH_DIR") ?: "/data/mesh").run() }
            "tunnels" -> runTunnels(opt("--dir") ?: env("HOMEGAP_TUNNELS_DIR") ?: "/data/tunnels", opt("--newt") ?: env("HOMEGAP_NEWT") ?: "/usr/local/bin/newt")
            "run" -> runController(configPath, flag("--dry-run") || env("HOMEGAP_DRY_RUN")?.lowercase() in setOf("1", "true", "yes"), flag("--once"))
            else -> {
                println(USAGE)
                exitProcess(2)
            }
        }
    } catch (e: Exception) {
        // A failed child coroutine surfaces as the parent's cancellation: report what caused it.
        val root = generateSequence<Throwable>(e) { it.cause?.takeIf { c -> c !== it } }.last()
        eprintln("homegap: ${e.message}")
        if (root !== e) eprintln("homegap: caused by ${root::class.simpleName}: ${root.message}")
        if (env("HOMEGAP_DEBUG") != null) eprintln(root.stackTraceToString())
        exitProcess(1)
    }
}

private fun eprintln(msg: String) {
    platform.posix.fprintf(platform.posix.stderr, "%s\n", msg)
}

private fun runController(configPath: String, dryRun: Boolean, once: Boolean) = runBlocking {
    val graph = createGraphFactory<ControllerGraph.Factory>().create(RunOptions(configPath, dryRun, once))
    val config = graph.config
    logger.info { "homegap $VERSION starting: domain=${config.domain} nodes=${config.nodes.map { it.id }} dryRun=${dryRun || config.reconcile.dryRun}" }
    val controller = graph.controller
    if (!once) {
        graph.dashboard.start(config.dashboard.host, config.dashboard.port)
        logger.info { "dashboard listening on ${config.dashboard.bind}" }
        graph.dashboardAuth.generatedFile?.let {
            logger.warn { "no dashboard token is available from the config (dashboard.auth_token_env): the dashboard uses the generated token in $it" }
        }
        if (config.publicOriginEnabled) {
            graph.speedTestServer.start(config.speedtest.host, config.speedtest.port)
            val what = listOfNotNull("speed test".takeIf { config.speedtest.enabled }, "status page".takeIf { config.statusPage.enabled })
            logger.info { "public origin (${what.joinToString(" and ")}) listening on ${config.speedtest.bind}" }
        }
    }
    controller.run(this, once)
}

private fun runTunnels(dir: String, newt: String) = runBlocking {
    createGraphFactory<TunnelsGraph.Factory>().create(TunnelOptions(dir, newt)).supervisor.run()
}

/** Asks the running orchestrator (same container, its dashboard API) to begin or end maintenance. */
private fun maintenance(configPath: String, action: String, minutes: Int) = runBlocking {
    val config = ConfigLoader.load(configPath)
    val token = homegap.dashboard.DashboardAuth.current(config)
    val host = config.dashboard.host.let { if (it == "0.0.0.0" || it.isEmpty()) "127.0.0.1" else it }
    io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 5 * 60_000L }
    }.use { http ->
        val r = http.post("http://$host:${config.dashboard.port}/api/cluster/maintenance/$action?minutes=$minutes") {
            token?.let { header(io.ktor.http.HttpHeaders.Authorization, "Bearer $it") }
        }
        val body = r.bodyAsText()
        val message = runCatching { Json.decodeFromString<ActionResponse>(body).message }.getOrDefault(body)
        if (!r.status.isSuccess()) {
            eprintln("homegap maintenance $action: $message")
            exitProcess(1)
        }
        println(message)
    }
}
