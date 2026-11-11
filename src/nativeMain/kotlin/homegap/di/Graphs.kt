package homegap.di

import dev.zacsweers.metro.*
import homegap.config.AppConfig
import homegap.config.ConfigLoader
import homegap.dashboard.DashboardServer
import homegap.dashboard.ShareLinks
import homegap.discovery.DockerClient
import homegap.platform.env
import homegap.reconcile.Controller
import homegap.reconcile.ReconcileLog
import homegap.clients.SshPool
import homegap.speedtest.SpeedTestServer
import homegap.status.PublicStatus
import homegap.speedtest.SpeedTestStore
import homegap.speedtest.SpeedTestTokens
import homegap.state.StateStore
import homegap.tunnels.TunnelSupervisor
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.curl.*
import io.ktor.client.plugins.*
import kotlinx.serialization.json.Json

/** Command-line inputs of `homegap run`. */
data class RunOptions(val configPath: String, val dryRun: Boolean, val once: Boolean)

/** Command-line inputs of `homegap tunnels`. */
data class TunnelOptions(
    val dir: String,
    val newtBinary: String,
    /** Where the endpoint pins go ([homegap.tunnels.HostsPins]); the container's own hosts file. */
    val hostsFile: String = "/etc/hosts",
)

/** Plain HTTP over loopback and unix sockets (Docker, SSH-forwarded Pangolin APIs). */
@Qualifier annotation class LocalHttp

/** TLS-capable client for external APIs (Bunny, the primary Pangolin). */
@Qualifier annotation class ExternalHttp

/** Scope of the tunnel supervisor process. */
abstract class TunnelScope private constructor()

/** `homegap run`: the controller, its dashboard and the speed test origin. */
@DependencyGraph(AppScope::class)
interface ControllerGraph {
    val config: AppConfig
    val controller: Controller
    val dashboard: DashboardServer
    val speedTestServer: SpeedTestServer

    @Provides @SingleIn(AppScope::class)
    fun initialConfig(options: RunOptions): AppConfig = ConfigLoader.load(options.configPath)

    @Provides @SingleIn(AppScope::class) @LocalHttp
    fun localHttp(): HttpClient = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        install(HttpTimeout) { connectTimeoutMillis = 10_000 }
        expectSuccess = false
    }

    @Provides @SingleIn(AppScope::class) @ExternalHttp
    fun externalHttp(): HttpClient = HttpClient(Curl) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    @Provides @SingleIn(AppScope::class)
    fun docker(@LocalHttp http: HttpClient): DockerClient =
        DockerClient(
            http,
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            },
            socketPath = env("DOCKER_SOCKET") ?: "/var/run/docker.sock"
        )

    @Provides @SingleIn(AppScope::class)
    fun state(config: AppConfig): StateStore = StateStore("${config.dataDir}/state.json")

    @Provides @SingleIn(AppScope::class)
    fun ssh(config: AppConfig): SshPool = SshPool("/tmp/homegap-ssh", "${config.dataDir}/known_hosts")

    @Provides @SingleIn(AppScope::class)
    fun reconcileLog(config: AppConfig): ReconcileLog = ReconcileLog(path = "${config.dataDir}/activity.json")

    @Provides @SingleIn(AppScope::class)
    fun speedTokens(): SpeedTestTokens = SpeedTestTokens()

    @Provides @SingleIn(AppScope::class)
    fun speedStore(config: AppConfig): SpeedTestStore = SpeedTestStore("${config.dataDir}/speedtests.json")

    @Provides @SingleIn(AppScope::class)
    fun shareLinks(config: AppConfig): ShareLinks = ShareLinks("${config.dataDir}/share-links.json")

    @Provides @SingleIn(AppScope::class)
    fun speedTestServer(controller: Controller, tokens: SpeedTestTokens, store: SpeedTestStore): SpeedTestServer =
        SpeedTestServer({ controller.config }, tokens, store, PublicStatus({ controller.config }, { controller.snapshot().nodes }))

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides options: RunOptions): ControllerGraph
    }
}

/** `homegap tunnels`: the Newt connector supervisor. */
@DependencyGraph(TunnelScope::class)
interface TunnelsGraph {
    val supervisor: TunnelSupervisor

    @Provides @SingleIn(TunnelScope::class)
    fun http(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 3_000
            connectTimeoutMillis = 1_000
        }
        expectSuccess = false
    }

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides options: TunnelOptions): TunnelsGraph
    }
}
