package homegap

import dev.zacsweers.metro.createGraphFactory
import homegap.api.ActionResponse
import homegap.config.AppConfig
import homegap.config.ConfigLoader
import homegap.config.SharedDirs
import homegap.dashboard.DashboardAuth
import homegap.di.ControllerGraph
import homegap.di.RunOptions
import homegap.di.TunnelOptions
import homegap.di.TunnelsGraph
import homegap.discovery.DockerContainerSummary
import homegap.mesh.MeshAgent
import homegap.model.Planner
import homegap.model.PublicResource
import homegap.platform.Files
import homegap.platform.env
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.fprintf
import platform.posix.stderr
import kotlin.system.exitProcess

const val VERSION = "0.1.0"

private const val EXIT_FAILURE = 1
private const val EXIT_USAGE = 2
private const val DEFAULT_CONFIG = "/config/config.yml"
private const val DEFAULT_NEWT = "/usr/local/bin/newt"
private const val DEFAULT_DATA_DIR = "/data"
private const val DEFAULT_MAINTENANCE_MINUTES = 30

/**
 * The orchestrator waits up to 10 minutes for a node that updates its own member, and then it
 * moves the primaries. The request must not end before that.
 */
private const val MAINTENANCE_TIMEOUT_MS = 15 * 60_000L

/** The options that are commands: `homegap --version` does not start the controller. */
private val COMMAND_OPTIONS = setOf("--version", "--help")

/** The values of `HOMEGAP_DRY_RUN` that turn the dry run on. */
private val TRUE_VALUES = setOf("1", "true", "yes")

private val logger by lazy { KotlinLogging.logger("homegap") }

private const val USAGE = """homegap $VERSION — Home Global Access Platform orchestrator

Usage:
  homegap [run] [--config PATH] [--dry-run] [--once]   Run the controller (default)
  homegap tunnels [--dir PATH] [--newt PATH]           Run the Newt tunnel supervisor
  homegap mesh [--dir PATH]                            Run the homelab's mesh agent (cluster mode)
  homegap validate [--config PATH]                     Validate the config file
  homegap plan [--config PATH] --labels FILE           Print the desired state for a label set (JSON map of container -> labels)
  homegap render-node ID [--config PATH]               Print the rendered stack for a node (secrets redacted)
  homegap maintenance start [--minutes N] | end       Move the cluster primaries off the homelab (and back) around an upgrade
  homegap version

Environment: HOMEGAP_CONFIG (default /config/config.yml), HOMEGAP_DRY_RUN, DOCKER_SOCKET, DOCKER_HOST,
HOMEGAP_CLUSTER_CONFIG_DIR, HOMEGAP_TUNNELS_DIR, HOMEGAP_MESH_DIR, HOMEGAP_MESH_HOST."""

/** The command and the arguments after it. With no command, the command is `run`. */
private class CommandLine(args: Array<String>) {
    val command = args.firstOrNull()?.takeIf { !it.startsWith("--") || it in COMMAND_OPTIONS } ?: "run"
    val rest = if (args.firstOrNull() == command) args.drop(1) else args.toList()

    /** The argument after [name], or null. */
    fun option(name: String): String? = rest.indexOf(name).takeIf { it >= 0 }?.let { rest.getOrNull(it + 1) }

    fun flag(name: String) = name in rest
}

fun main(args: Array<String>) {
    KotlinLoggingConfiguration.logStartupMessage = false
    val line = CommandLine(args)
    val configPath = line.option("--config") ?: env("HOMEGAP_CONFIG") ?: DEFAULT_CONFIG

    try {
        when (line.command) {
            "version", "--version" -> println(VERSION)
            "help", "--help", "-h" -> println(USAGE)
            "validate" -> validate(configPath)
            "plan" -> printPlan(configPath, line)
            "render-node" -> renderNode(configPath, line)
            "maintenance" -> {
                val action = line.rest.firstOrNull { it == "start" || it == "end" } ?: error("maintenance start|end")
                val minutes = line.option("--minutes")?.toIntOrNull() ?: DEFAULT_MAINTENANCE_MINUTES
                maintenance(configPath, action, minutes)
            }
            "mesh" -> {
                val dir = line.option("--dir") ?: SharedDirs.mesh(DEFAULT_DATA_DIR)
                runMesh(sharedDir(dir, volume = "homegap-mesh", other = "orchestrator"))
            }
            "tunnels" -> {
                val dir = line.option("--dir") ?: SharedDirs.tunnels(DEFAULT_DATA_DIR)
                val shared = sharedDir(dir, volume = "homegap-tunnels", other = "orchestrator")
                runTunnels(shared, newt = line.option("--newt") ?: env("HOMEGAP_NEWT") ?: DEFAULT_NEWT)
            }
            "run" -> {
                val dryRun = line.flag("--dry-run") || env("HOMEGAP_DRY_RUN")?.lowercase() in TRUE_VALUES
                runController(configPath, dryRun = dryRun, once = line.flag("--once"))
            }
            else -> {
                println(USAGE)
                exitProcess(EXIT_USAGE)
            }
        }
    } catch (e: Exception) {
        // A failed child coroutine surfaces as the parent's cancellation: report what caused it.
        val root = generateSequence<Throwable>(e) { it.cause?.takeIf { cause -> cause !== it } }.last()
        eprintln("homegap: ${e.message}")
        if (root !== e) eprintln("homegap: caused by ${root::class.simpleName}: ${root.message}")
        if (env("HOMEGAP_DEBUG") != null) eprintln(root.stackTraceToString())
        exitProcess(EXIT_FAILURE)
    }
}

private fun eprintln(message: String) {
    fprintf(stderr, "%s\n", message)
}

private fun validate(configPath: String) {
    val config = ConfigLoader.load(configPath)
    println("OK: ${config.nodes.size} node(s), domain ${config.domain}, zone ${config.bunny.zone}")
}

/** Prints the desired state for the labels in the `--labels` file. */
private fun printPlan(configPath: String, line: CommandLine) {
    val config = ConfigLoader.load(configPath)
    val labelsFile = line.option("--labels") ?: error("--labels FILE required")
    val labelsByContainer = Json.decodeFromString<Map<String, Map<String, String>>>(Files.readText(labelsFile))
    val containers = labelsByContainer.map { (name, labels) ->
        DockerContainerSummary(id = name, names = listOf("/$name"), labels = labels)
    }
    val plan = Planner.plan(config, containers)
    plan.labelErrors.forEach { println("ERROR ${it.container}: ${it.message}") }
    plan.labelWarnings.forEach { println("WARN  ${it.container}: ${it.message}") }
    for ((id, node) in plan.nodes) {
        println("node $id (${node.managementHost})")
        node.publicResources.forEach { println(publicLine(it)) }
        for (resource in node.privateResources) {
            val ports = "tcp=${resource.tcpPorts} udp=${resource.udpPorts}"
            println("  private ${resource.niceId}: ${resource.alias} -> ${resource.destination} $ports")
        }
        node.sniRoutes.forEach { println("  sni     ${it.name}: ${NodeTemplates.sniRule(it)}") }
        node.rawPorts.forEach { println("  port    ${it.entryPoint}") }
    }
    for (record in plan.dns) {
        val route = "${record.fqdn} -> ${record.value} [${record.nodeId}]"
        val geo = if (record.geo) " geo" else ""
        println("dns ${record.type} $route ${record.purpose}$geo monitor=${record.monitor}")
    }
}

private fun publicLine(resource: PublicResource): String {
    val address = resource.fullDomain ?: ":${resource.proxyPort}"
    val target = resource.target
    val method = target.method?.let { " ($it)" }.orEmpty()
    return "  public  ${resource.niceId}: ${resource.mode.wire} $address -> ${target.host}:${target.port}$method"
}

/** Prints the files of the stack of one node, with placeholders for the secrets. */
private fun renderNode(configPath: String, line: CommandLine) {
    val config = ConfigLoader.load(configPath)
    val id = line.rest.firstOrNull { !it.startsWith("--") && it != line.option("--config") }
        ?: error("node id required")
    val node = config.node(id) ?: error("unknown node $id")
    val plan = Planner.plan(config, emptyList())
    val files = NodeTemplates.staticFiles(
        config = config,
        node = node,
        secrets = placeholderSecrets(config),
        rawPorts = plan.nodes.getValue(id).rawPorts,
        slot = config.nodes.indexOf(node),
    )
    for ((path, content, mode) in files) {
        println("=== $path ($mode)")
        println(content)
    }
    println("=== ${NodeTemplates.DYNAMIC_ROUTES}")
    println(NodeTemplates.dynamicRoutes(plan.nodes.getValue(id), emptyMap()))
}

private fun placeholderSecrets(config: AppConfig) = NodeSecrets(
    setupToken = "<setup-token>",
    serverSecret = "<server-secret>",
    adminEmail = NodeTemplates.adminEmail(config),
    adminPassword = "<password>",
    crowdsecBouncerKey = "<bouncer-key>",
)

private fun runController(configPath: String, dryRun: Boolean, once: Boolean) = runBlocking {
    val options = RunOptions(configPath = configPath, dryRun = dryRun, once = once)
    val graph = createGraphFactory<ControllerGraph.Factory>().create(options)
    val config = graph.config
    logger.info {
        val nodeIds = config.nodes.map { it.id }
        val effectiveDryRun = dryRun || config.reconcile.dryRun
        "homegap $VERSION starting: domain=${config.domain} nodes=$nodeIds dryRun=$effectiveDryRun"
    }
    val controller = graph.controller
    if (!once) {
        graph.dashboard.start(config.dashboard.host, config.dashboard.port)
        logger.info { "dashboard listening on ${config.dashboard.bind}" }
        graph.dashboardAuth.generatedFile?.let { file ->
            logger.warn {
                "no dashboard token is available from the config (dashboard.auth_token_env): " +
                    "the dashboard uses the generated token in $file"
            }
        }
        if (config.publicOriginEnabled) {
            graph.speedTestServer.start(config.speedtest.host, config.speedtest.port)
            val origins = listOfNotNull(
                "speed test".takeIf { config.speedtest.enabled },
                "status page".takeIf { config.statusPage.enabled },
            )
            logger.info { "public origin (${origins.joinToString(" and ")}) listening on ${config.speedtest.bind}" }
        }
    }
    controller.run(this, once)
}

private fun runMesh(dir: String) = runBlocking {
    MeshAgent(dir).run()
}

private fun runTunnels(dir: String, newt: String) = runBlocking {
    createGraphFactory<TunnelsGraph.Factory>().create(TunnelOptions(dir, newt)).supervisor.run()
}

/**
 * Asks the running orchestrator (same container, its dashboard API) to start or end maintenance.
 * The request uses plain HTTP, because it does not leave the container.
 */
@Suppress("HttpUrlsUsage")
private fun maintenance(configPath: String, action: String, minutes: Int) = runBlocking {
    val config = ConfigLoader.load(configPath)
    val token = DashboardAuth.current(config)
    val host = config.dashboard.host.let { if (it == "0.0.0.0" || it.isEmpty()) "127.0.0.1" else it }
    val url = "http://$host:${config.dashboard.port}/api/cluster/maintenance/$action?minutes=$minutes"
    HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = MAINTENANCE_TIMEOUT_MS }
    }.use { http ->
        val response = http.post(url) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        val body = response.bodyAsText()
        val message = runCatching { Json.decodeFromString<ActionResponse>(body).message }.getOrDefault(body)
        if (!response.status.isSuccess()) {
            eprintln("homegap maintenance $action: $message")
            exitProcess(EXIT_FAILURE)
        }
        println(message)
    }
}

/** Returns [path], and says so on stderr if it is not a volume that the [other] container can see. */
private fun sharedDir(path: String, volume: String, other: String): String {
    if (SharedDirs.notAVolume(path)) {
        eprintln("warning: " + SharedDirs.notAVolumeHint(path, volume, other))
    }
    return path
}
