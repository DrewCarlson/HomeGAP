package homegap.di

import dev.zacsweers.metro.*
import homegap.clients.SshPool
import homegap.config.AppConfig
import homegap.config.ConfigLoader
import homegap.config.authorityDir
import homegap.config.identityDir
import homegap.config.signInDir
import homegap.dashboard.DashboardAuth
import homegap.dashboard.DashboardServer
import homegap.dashboard.DashboardSso
import homegap.identity.IdentityStore
import homegap.identity.IssuerWatch
import homegap.identity.ProviderUsersWatch
import homegap.discovery.DockerClient
import homegap.platform.env
import homegap.reconcile.Controller
import homegap.reconcile.ReconcileLog
import homegap.speedtest.SpeedTestServer
import homegap.speedtest.SpeedTestStore
import homegap.speedtest.SpeedTestTokens
import homegap.state.StateStore
import homegap.status.PublicStatus
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.curl.*
import io.ktor.client.plugins.*
import kotlinx.serialization.json.Json

/** Command-line inputs of `homegap run`. */
data class RunOptions(val configPath: String, val dryRun: Boolean, val once: Boolean)

/** Plain HTTP over loopback and unix sockets (Docker). */
@Qualifier annotation class LocalHttp

/** TLS-capable client for external APIs (Bunny). */
@Qualifier annotation class ExternalHttp


/**
 * `homegap run`: the controller, its dashboard and the speed test origin. Only the graph that
 * Metro generates calls the `@Provides` functions, so the IDE sees them as unused.
 */
@Suppress("unused")
@DependencyGraph(AppScope::class)
interface ControllerGraph {
    val config: AppConfig
    val controller: Controller
    val dashboard: DashboardServer
    val dashboardAuth: DashboardAuth
    val speedTestServer: SpeedTestServer
    val issuerWatch: IssuerWatch
    val providerUsersWatch: ProviderUsersWatch

    @Provides @SingleIn(AppScope::class)
    fun initialConfig(options: RunOptions): AppConfig = ConfigLoader.load(options.configPath)

    @Provides @SingleIn(AppScope::class) @LocalHttp
    fun localHttp(): HttpClient = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            // The Docker event stream sets its own, with no limit.
            requestTimeoutMillis = 120_000
        }
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
            client = http,
            json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            },
            socketPath = env("DOCKER_SOCKET") ?: "/var/run/docker.sock",
            host = env("DOCKER_HOST"),
        )

    @Provides @SingleIn(AppScope::class)
    fun state(config: AppConfig): StateStore = StateStore("${config.dataDir}/state.json")

    @Provides @SingleIn(AppScope::class)
    fun identity(config: AppConfig): IdentityStore = IdentityStore(config.authorityDir, config.identityDir)

    @Provides @SingleIn(AppScope::class)
    fun issuerWatch(config: AppConfig, identity: IdentityStore, log: ReconcileLog): IssuerWatch =
        IssuerWatch(config.signInDir, identity, log)

    @Provides @SingleIn(AppScope::class)
    fun providerUsersWatch(controller: Controller, identity: IdentityStore, log: ReconcileLog): ProviderUsersWatch =
        ProviderUsersWatch(controller.config.signInDir, identity, { controller.config }, log)

    @Provides @SingleIn(AppScope::class)
    fun ssh(config: AppConfig): SshPool = SshPool("/tmp/homegap-ssh", "${config.dataDir}/known_hosts")

    @Provides @SingleIn(AppScope::class)
    fun reconcileLog(config: AppConfig): ReconcileLog = ReconcileLog(path = "${config.dataDir}/activity.json")

    @Provides @SingleIn(AppScope::class)
    fun speedTokens(): SpeedTestTokens = SpeedTestTokens()

    @Provides @SingleIn(AppScope::class)
    fun speedStore(config: AppConfig): SpeedTestStore = SpeedTestStore("${config.dataDir}/speedtests.json")

    @Provides @SingleIn(AppScope::class)
    fun dashboardAuth(config: AppConfig, controller: Controller): DashboardAuth =
        DashboardAuth.resolve(
            config = config,
            frontDoor = { controller.frontDoorAddresses },
            sso = {
                val current = controller.config
                val host = current.dashboard.publishedHost(current.domain)
                current.dashboard.sso?.takeIf { host != null }?.let { DashboardSso(host!!, it) }
            },
        )

    @Provides @SingleIn(AppScope::class)
    fun speedTestServer(controller: Controller, tokens: SpeedTestTokens, store: SpeedTestStore): SpeedTestServer {
        val publicStatus = PublicStatus(
            config = { controller.config },
            nodes = { controller.status.value?.nodes.orEmpty() },
            privateRange = { controller.privateSourceRange },
        )
        return SpeedTestServer({ controller.config }, tokens, store, publicStatus)
    }

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides options: RunOptions): ControllerGraph
    }
}
