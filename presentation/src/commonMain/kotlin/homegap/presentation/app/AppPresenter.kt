package homegap.presentation.app

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.Health
import homegap.api.StatusSnapshot
import homegap.presentation.activity.ActivityScreenPresenter
import homegap.presentation.activity.ActivityScreenProps
import homegap.presentation.config.ConfigScreenPresenter
import homegap.presentation.addnode.AddNodeScreenPresenter
import homegap.presentation.addnode.AddNodeScreenProps
import homegap.presentation.config.ConfigScreenProps
import homegap.presentation.core.Presenter
import homegap.presentation.core.ScreenModel
import homegap.presentation.core.rememberEventTrigger
import homegap.presentation.core.rememberNow
import homegap.presentation.data.*
import homegap.presentation.dns.DnsScreenPresenter
import homegap.presentation.dns.DnsScreenProps
import homegap.presentation.format.ago
import homegap.presentation.format.plural
import homegap.presentation.nodes.NodesScreenPresenter
import homegap.presentation.nodes.NodesScreenProps
import homegap.presentation.overview.OverviewScreenPresenter
import homegap.presentation.overview.OverviewScreenProps
import homegap.presentation.routing.Route
import homegap.presentation.routing.Router
import homegap.presentation.services.ServicesScreenPresenter
import homegap.presentation.services.ServicesScreenProps
import homegap.presentation.speedtest.SpeedTestScreenPresenter
import homegap.presentation.speedtest.SpeedTestScreenProps
import homegap.presentation.tunnels.TunnelsScreenPresenter
import homegap.presentation.tunnels.TunnelsScreenProps
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock

/** [route] comes from the platform router (the URL fragment), which [router] also updates. */
data class AppProps(val route: Route, val router: Router)

/**
 * Root presenter: follows the controller while composed, builds the page shell and delegates the
 * active [Route] to its screen presenter.
 */
@SingleIn(AppScope::class)
@Inject
class AppPresenter(
    private val session: ControllerSession,
    private val actions: ActionRunner,
    private val api: ControllerApi,
    private val tokens: TokenStore,
    private val clock: Clock,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val overview: OverviewScreenPresenter,
    private val nodes: NodesScreenPresenter,
    private val services: ServicesScreenPresenter,
    private val dns: DnsScreenPresenter,
    private val tunnels: TunnelsScreenPresenter,
    private val speedTest: SpeedTestScreenPresenter,
    private val activity: ActivityScreenPresenter,
    private val config: ConfigScreenPresenter,
    private val addNode: AddNodeScreenPresenter,
) : Presenter<AppProps, AppModel> {
    @Composable
    override fun model(props: AppProps): AppModel {
        LaunchedEffect(Unit) { session.follow() }
        val status by session.status.collectAsState()
        val problem by session.problem.collectAsState()
        val toast by actions.toast.collectAsState()
        val busy by actions.busy.collectAsState()
        val now by clock.rememberNow()
        val router by rememberUpdatedState(props.router)

        val snapshot = status
        val attention = remember(snapshot) { snapshot?.tabsNeedingAttention().orEmpty() }
        return AppModel(
            route = props.route,
            nav = Route.entries.filter { it.inNav }.map {
                NavItem(it, selected = it == props.route.navSection, needsAttention = it in attention)
            },
            header = snapshot?.let { header(it, busy, now) },
            connection = problem?.let { connection(it) },
            banners = remember(snapshot) { snapshot?.banners().orEmpty() },
            rollout = snapshot?.let { rollout(it, now) },
            screen = snapshot?.let { screen(props.route, it, now, props.router) },
            toast = toast,
            onNavigate = remember { { route: Route -> router.navigate(route) } },
        )
    }

    @Composable
    private fun screen(route: Route, status: StatusSnapshot, now: Long, router: Router): ScreenModel = when (route) {
        Route.Overview -> overview.model(OverviewScreenProps(status, now))
        Route.Nodes -> nodes.model(NodesScreenProps(status, now))
        Route.Services -> services.model(ServicesScreenProps(status))
        Route.Dns -> dns.model(DnsScreenProps(status))
        Route.Tunnels -> tunnels.model(TunnelsScreenProps(status))
        Route.SpeedTest -> speedTest.model(SpeedTestScreenProps(now))
        Route.Activity -> activity.model(ActivityScreenProps)
        Route.Config -> config.model(ConfigScreenProps)
        Route.AddNode -> {
            val currentRouter by rememberUpdatedState(router)
            addNode.model(
                AddNodeScreenProps(
                    existingNodeIds = remember(status.nodes) { status.nodes.map { it.id }.toSet() },
                    onFinished = remember { { currentRouter.navigate(Route.Nodes) } },
                )
            )
        }
    }

    @Composable
    private fun header(status: StatusSnapshot, busy: Boolean, now: Long): HeaderModel {
        val paused by rememberUpdatedState(status.paused)
        return HeaderModel(
            domain = status.domain,
            versionLine = "${status.project} · v${status.version}",
            pass = passStatus(status, now),
            paused = status.paused,
            busy = busy,
            onResync = remember { { actions.run("Resync") { api.resync() } } },
            onTogglePause = remember {
                { actions.run(if (paused) "Resume" else "Pause") { api.pause(PauseScope.Global, null, !paused) } }
            },
        )
    }

    @Composable
    private fun connection(problem: ConnectionProblem): ConnectionModel {
        var draft by remember { mutableStateOf(tokens.token.orEmpty()) }
        val save = appScope.rememberEventTrigger {
            tokens.token = draft.ifBlank { null }
            session.refresh()
        }
        val prompt = TokenPromptModel(
            value = draft,
            onChange = remember { { draft = it } },
            onSave = save::trigger,
        )
        return when (problem) {
            ConnectionProblem.Unauthorized -> ConnectionModel("Enter the dashboard token to continue.", prompt)
            is ConnectionProblem.Failed -> ConnectionModel(problem.message, prompt)
        }
    }

    @Composable
    private fun rollout(status: StatusSnapshot, now: Long): RolloutModel? {
        val failed = status.nodes.mapNotNull { node -> node.update?.takeIf { it.status == "failed" }?.let { node.id to it } }
        if (failed.isEmpty()) return null
        val waiting = status.nodes.filter { it.updateWaiting != null && it.update == null }.map { it.id }
        val summary = buildString {
            append("Update rollout held. ")
            failed.forEach { (id, update) ->
                val outcome = if (update.rolledBack) "and was rolled back; it is running its previous stack." else "and the rollback failed."
                append("$id: the update failed ${ago(update.at, now)} $outcome ")
            }
            append(if (waiting.isEmpty()) "No other node is waiting." else "Waiting: ${waiting.joinToString()}.")
        }
        return RolloutModel(
            summary = summary,
            detail = failed.firstNotNullOfOrNull { it.second.error }?.take(400),
            failedNodes = failed.map { it.first },
            onRetry = remember { { id: String -> actions.run("Retry update") { api.retryUpdate(id) } } },
            onSkip = remember { { id: String -> actions.run("Skip update") { api.skipUpdate(id) } } },
        )
    }

    private fun passStatus(status: StatusSnapshot, now: Long): PassStatusModel {
        val last = status.lastReconcile
        val health = when {
            status.reconciling || last == null -> Health.UNKNOWN
            last.errors > 0 -> Health.DEGRADED
            else -> Health.OK
        }
        val text = when {
            status.reconciling -> "Reconciling…"
            last == null -> "Waiting for first pass"
            last.errors > 0 -> "Synced ${ago(last.finishedAt, now)} · ${plural(last.errors, "error")}"
            else -> "Synced ${ago(last.finishedAt, now)}"
        }
        val tooltip = last?.let {
            "Last pass ${ago(it.finishedAt, now)} · trigger ${it.trigger} · ${plural(it.changes, "change")} · ${plural(it.errors, "error")}"
        }
        return PassStatusModel(health, text, tooltip)
    }
}

/** Routes whose content currently needs attention (a dot in the navigation). */
internal fun StatusSnapshot.tabsNeedingAttention(): Set<Route> = buildSet {
    if (nodes.any { it.health == Health.DOWN || it.lastProvisionOk == false }) add(Route.Nodes)
    val serviceDown = services.any { service -> service.perNode.any { it.health == Health.DOWN } }
    if (labelErrors.isNotEmpty() || serviceDown) add(Route.Services)
    if (dns.any { !it.inSync || it.monitorStatus == "offline" }) add(Route.Dns)
    if (tunnels.any { !it.up }) add(Route.Tunnels)
}

internal fun StatusSnapshot.banners(): List<BannerModel> = buildList {
    if (dryRun) add(BannerModel(BannerKind.Warn, "Dry-run mode: discovery and planning only. Nothing is written to nodes, Pangolin or DNS."))
    if (paused) add(BannerModel(BannerKind.Warn, "Reconciliation is paused globally. Manual changes on nodes, Pangolin and Bunny are left alone."))
}
