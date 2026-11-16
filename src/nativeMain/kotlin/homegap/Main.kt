package homegap

import dev.zacsweers.metro.createGraphFactory
import homegap.config.ConfigLoader
import homegap.config.SharedDirs
import homegap.config.SignInConfig
import homegap.config.VpnConfig
import homegap.di.ControllerGraph
import homegap.di.RunOptions
import homegap.discovery.DockerContainerSummary
import homegap.mesh.MeshAgent
import homegap.platform.Identity
import homegap.model.HomelabServices
import homegap.identity.MachineCommand
import homegap.identity.UserCommand
import homegap.model.Planner
import homegap.model.PublicResource
import homegap.node.NodeAgent
import homegap.platform.Files
import homegap.platform.env
import homegap.provision.NodeSecrets
import homegap.signin.Oidc
import homegap.signin.SignInPage
import homegap.signin.SignInServer
import homegap.provision.NodeTemplates
import homegap.stack.FileOutcome
import homegap.stack.FileResult
import homegap.stack.StackFiles
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.STDIN_FILENO
import platform.posix.fprintf
import platform.posix.isatty
import platform.posix.stderr
import kotlin.system.exitProcess

const val VERSION = "0.1.0"

private const val EXIT_FAILURE = 1
private const val EXIT_USAGE = 2
private const val DEFAULT_CONFIG = "/config/config.yml"
private const val DEFAULT_DATA_DIR = "/data"
private const val DEFAULT_AGENT_DIR = "/agent"
private const val DEFAULT_AGENT_STATE_DIR = "/agent-state"
private const val DEFAULT_SIGN_IN_PORT = 8090
private const val PROVIDER_TIMEOUT_MS = 15_000L
private const val DEFAULT_STACK_FILES = "/usr/share/homegap/deploy"
private const val DEFAULT_STACK_FOLDER = "/work"
private const val INSTALL_PAGE = "https://drewcarlson.github.io/HomeGAP/latest/getting-started/install/"

/** The answers that replace a file that the user changed. */
private val YES_ANSWERS = setOf("y", "yes")

/** The options that are commands: `homegap --version` does not start the controller. */
private val COMMAND_OPTIONS = setOf("--version", "--help")

/** The values of `HOMEGAP_DRY_RUN` that turn the dry run on. */
private val TRUE_VALUES = setOf("1", "true", "yes")

private val logger by lazy { KotlinLogging.logger("homegap") }

private const val USAGE = """homegap $VERSION — Home Global Access Platform orchestrator

Usage:
  homegap [run] [--config PATH] [--dry-run] [--once]   Run the controller (default)
  homegap mesh [--dir PATH]                            Run the mesh agent of the homelab (the mesh and the VPN peer)
  homegap node-agent [--dir PATH] [--state PATH]       Run the agent of a node (on the node)
  homegap validate [--config PATH]                     Validate the config file
  homegap plan [--config PATH] --labels FILE           Print the desired state for a label set (JSON map of container -> labels)
  homegap render-node ID [--config PATH]               Print the rendered stack for a node (secrets redacted)
  homegap machine list | add NAME [--role ROLE]... | remove NAME
                                                       Manage the devices with no user (servers that need access)
  homegap sign-in [--config PATH] [--dir PATH] [--identity PATH] [--port N]
                                                       Run the public sign-in of the homelab
  homegap user list | add EMAIL [--name NAME] [--role ROLE]... | password EMAIL | block EMAIL | unblock EMAIL | remove EMAIL
                                                       Manage the users of HomeGAP's own sign-in
  homegap init [--dir PATH] [--yes] [--refresh]        Write the files of the homelab stack to a folder (default /work)
  homegap version

Environment: HOMEGAP_CONFIG (default /config/config.yml), HOMEGAP_DRY_RUN, DOCKER_SOCKET, DOCKER_HOST,
HOMEGAP_MESH_DIR, HOMEGAP_IDENTITY_DIR, HOMEGAP_SIGN_IN_DIR, HOMEGAP_VPN_DIR, HOMEGAP_STACK_FILES."""

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
            "init" -> initStack(line)
            "sign-in" -> runSignIn(configPath, line)
            "machine" -> MachineCommand(ConfigLoader.load(configPath), line.rest).run()
            "user" -> UserCommand(ConfigLoader.load(configPath), line.rest).run()
            "mesh" -> {
                val dir = line.option("--dir") ?: SharedDirs.mesh(DEFAULT_DATA_DIR)
                runMesh(sharedDir(dir, folder = "data/mesh", other = "orchestrator"))
            }
            "node-agent" -> runBlocking {
                val state = line.option("--state") ?: DEFAULT_AGENT_STATE_DIR
                NodeAgent(line.option("--dir") ?: DEFAULT_AGENT_DIR, state).run()
            }
            "run" -> {
                val dryRun = line.flag("--dry-run") || env("HOMEGAP_DRY_RUN")?.lowercase() in TRUE_VALUES
                Identity.ensureKnown(home = DEFAULT_DATA_DIR)
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
    val plan = Planner.plan(config, containers + HomelabServices.containers(config))
    plan.labelErrors.forEach { println("ERROR ${it.container}: ${it.message}") }
    plan.labelWarnings.forEach { println("WARN  ${it.container}: ${it.message}") }
    for ((id, node) in plan.nodes) {
        println("node $id (${node.managementHost})")
        node.resources.forEach { println(publicLine(it)) }
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
    val files = NodeTemplates.staticFiles(config, node, NodeSecrets(crowdsecBouncerKey = "<bouncer-key>"))
    for ((path, content, mode) in files) {
        println("=== $path ($mode)")
        println(content)
    }
    println("=== ${NodeTemplates.DYNAMIC_ROUTES}")
    println(NodeTemplates.dynamicRoutes(plan.nodes.getValue(id)))
}

/**
 * Writes the files of the homelab stack to the folder of `--dir`. With `--refresh`, it brings only
 * the shipped files to this version, as `upgrade.sh` does.
 */
private fun initStack(line: CommandLine) {
    val folder = line.option("--dir") ?: DEFAULT_STACK_FOLDER
    if (!Files.isDirectory(folder)) {
        error(
            "there is no folder $folder. Mount your folder there, for example: " +
                $$"docker run --rm -it -v \"$PWD:/work\" ghcr.io/drewcarlson/homegap init",
        )
    }
    val files = StackFiles(
        source = env("HOMEGAP_STACK_FILES") ?: DEFAULT_STACK_FILES,
        target = folder,
        confirm = { path -> line.flag("--yes") || confirmOnTerminal(path) },
    )
    val refreshOnly = line.flag("--refresh")
    val outcomes = if (refreshOnly) files.refresh() else files.init()
    outcomes.forEach { println(outcomeLine(it)) }
    if (outcomes.any { it.result == FileResult.KEPT } && !isTerminal()) {
        println("There is no terminal for the question, so each file with your changes stays.")
        println("Compare it with its ${StackFiles.NEW_SUFFIX} file.")
    }
    if (!refreshOnly) println(NEXT_STEPS)
}

private val NEXT_STEPS = """
Next:
  1. Put your Bunny API key in .env.
  2. Set domain, bunny.zone and acme.email in config/config.yml.
  3. Put your own changes to the stack in compose.override.yml (see compose.override.example.yml).
  4. Start the stack: docker compose up -d
All steps: $INSTALL_PAGE""".trimEnd()

private fun isTerminal() = isatty(STDIN_FILENO) == 1

/** Asks on the terminal if the new version can replace [path]. Without a terminal, the answer is no. */
private fun confirmOnTerminal(path: String): Boolean {
    if (!isTerminal()) return false
    val previous = path + StackFiles.PREVIOUS_SUFFIX
    print("$path has changes that are not from HomeGAP. Replace it? Your file moves to $previous. [y/N] ")
    return readlnOrNull()?.trim()?.lowercase() in YES_ANSWERS
}

private fun outcomeLine(outcome: FileOutcome): String {
    val path = outcome.path
    return when (outcome.result) {
        FileResult.CREATED -> "created    $path"
        FileResult.UPDATED -> "updated    $path"
        FileResult.UNCHANGED -> "unchanged  $path"
        FileResult.REPLACED -> "replaced   $path (your file is ${path + StackFiles.PREVIOUS_SUFFIX})"
        FileResult.KEPT -> "kept       $path (the file of this version is ${path + StackFiles.NEW_SUFFIX})"
    }
}

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
        launch { graph.issuerWatch.run() }
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

/** The sign-in reads only the `sign_in` part of the config. With no config file, it accepts passwords only. */
private fun runSignIn(configPath: String, line: CommandLine) = runBlocking {
    val config = if (Files.exists(configPath)) ConfigLoader.load(configPath) else null
    val settings = config?.signIn ?: SignInConfig()
    val oidc = settings.oidc?.let { provider ->
        val secret = env(provider.clientSecretEnv)?.takeIf { it.isNotBlank() }
            ?: error("sign_in.oidc needs the variable ${provider.clientSecretEnv} in the sign-in container")
        val http = HttpClient(Curl) {
            // A provider with a certificate of a private CA: the file with that CA.
            engine { env("SSL_CERT_FILE")?.takeIf { it.isNotBlank() }?.let { caInfo = it } }
            install(HttpTimeout) { requestTimeoutMillis = PROVIDER_TIMEOUT_MS }
            followRedirects = false
            expectSuccess = false
        }
        Oidc(provider, secret, settings.url.orEmpty().trimEnd('/') + Oidc.CALLBACK_PATH, http)
    }
    val dir = line.option("--dir") ?: SharedDirs.signIn(DEFAULT_DATA_DIR)
    val identity = line.option("--identity") ?: SharedDirs.identity(DEFAULT_DATA_DIR)
    val port = line.option("--port")?.toIntOrNull() ?: DEFAULT_SIGN_IN_PORT
    val options = SignInPage.Options(settings.passwords, settings.oidc?.name)
    // The devices reach the VPN at the name of the sign-in, which goes to a node.
    val endpoint = config?.vpn?.endpoint ?: settings.url?.removePrefix("https://")?.substringBefore('/')
    val tunnel = endpoint?.let { SharedDirs.vpn(DEFAULT_DATA_DIR) to it }
    val setup = SignInServer.Setup(options, oidc, config?.vpn ?: VpnConfig(), tunnel)
    SignInServer(dir, identity, setup).run(host = "0.0.0.0", port = port)
}

private fun runMesh(dir: String) = runBlocking {
    MeshAgent(dir, SharedDirs.vpn(DEFAULT_DATA_DIR)).run()
}

/** Returns [path], and says so on stderr if it is not a mount that the [other] container can see. */
private fun sharedDir(path: String, folder: String, other: String): String {
    if (SharedDirs.notAVolume(path)) {
        eprintln("warning: " + SharedDirs.notAVolumeHint(path, folder, other))
    }
    return path
}
