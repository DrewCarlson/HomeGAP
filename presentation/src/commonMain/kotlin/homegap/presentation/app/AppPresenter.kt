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
import homegap.presentation.diagnostics.DiagnosticsScreenPresenter
import homegap.presentation.diagnostics.DiagnosticsScreenProps
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
import homegap.presentation.routing.Location
import homegap.presentation.routing.Router
import homegap.presentation.search.SearchHit
import homegap.presentation.search.SearchKind
import homegap.presentation.search.preferLiteral
import homegap.presentation.search.search
import homegap.presentation.search.searchIndex
import homegap.presentation.search.suggestions
import homegap.presentation.search.toHit
import homegap.presentation.core.runCatchingCancellable
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import homegap.presentation.ui.ConfirmModel
import homegap.presentation.ui.anyDown
import homegap.presentation.ui.location
import homegap.presentation.services.ServicesScreenPresenter
import homegap.presentation.services.ServicesScreenProps
import homegap.presentation.access.AccessScreenPresenter
import homegap.presentation.access.AccessScreenProps
import homegap.presentation.speedtest.SpeedTestScreenPresenter
import homegap.presentation.speedtest.SpeedTestScreenProps
import homegap.presentation.tunnels.TunnelsScreenPresenter
import homegap.presentation.tunnels.TunnelsScreenProps
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock

/**
 * [route] and [selection] come from the platform router (the URL fragment), which [router] also
 * updates. [selection] is the item the Inspector shows, if any.
 */
data class AppProps(
    val route: Route,
    val router: Router,
    val selection: String? = null,
)

/**
 * Root presenter: follows the controller while composed, builds the page shell and delegates the
 * active [Route] to its screen presenter.
 */
@SingleIn(AppScope::class)
@Inject
class AppPresenter(
    private val session: ControllerSession,
    private val actions: ActionRunner,
    private val confirmations: Confirmations,
    private val pausedGuard: PausedGuard,
    private val api: ControllerApi,
    private val tokens: TokenStore,
    private val clock: Clock,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val overview: OverviewScreenPresenter,
    private val nodes: NodesScreenPresenter,
    private val services: ServicesScreenPresenter,
    private val dns: DnsScreenPresenter,
    private val certificates: homegap.presentation.certificates.CertificatesScreenPresenter,
    private val tunnels: TunnelsScreenPresenter,
    private val speedTest: SpeedTestScreenPresenter,
    private val access: AccessScreenPresenter,
    private val activity: ActivityScreenPresenter,
    private val config: ConfigScreenPresenter,
    private val diagnostics: DiagnosticsScreenPresenter,
    private val locations: homegap.presentation.locations.LocationsScreenPresenter,
    /** A new wizard each time it opens: its state never outlives it. */
    private val addNode: dev.zacsweers.metro.Provider<AddNodeScreenPresenter>,
    private val nodeLocation: homegap.presentation.nodes.NodeLocationPresenter,
    private val terminal: homegap.presentation.terminal.TerminalPresenter,
    private val terminals: homegap.presentation.terminal.TerminalSessions,
) : Presenter<AppProps, AppModel> {
    private companion object {
        /** How long the controller must stay out of reach before the dashboard says so. */
        val LOST_GRACE = 5.seconds

        const val NO_HIT = -1
    }

    @Composable
    override fun model(props: AppProps): AppModel {
        LaunchedEffect(Unit) { session.follow() }
        val status by session.status.collectAsState()
        val streamRefused by session.streamRefused.collectAsState()
        val problem by session.problem.collectAsState()
        val toasts by actions.toasts.collectAsState()
        val log by session.log.collectAsState()
        val metrics by session.metrics.collectAsState()
        val pendingConfirm by confirmations.pending.collectAsState()
        val busy by actions.busy.collectAsState()
        val now by clock.rememberNow()
        val router by rememberUpdatedState(props.router)

        val snapshot = status
        val attention = remember(snapshot) { snapshot?.attentionCounts().orEmpty() }
        return AppModel(
            route = props.route,
            nav = Route.NAV.map { route ->
                NavItem(
                    route = route,
                    selected = route == props.route.navSection,
                    attention = attention[route],
                )
            },
            header = snapshot?.let { header(it, busy, now) },
            nodeLocation = nodeLocation.model(status = snapshot),
            connection = problem?.let {
                connection(
                    problem = it,
                    lastUpdate = snapshot?.generatedAt,
                )
            },
            banners = remember(snapshot, streamRefused) { listOfNotNull(streamRefused?.let(::noLiveUpdates)) + snapshot?.banners().orEmpty() },
            rollout = snapshot?.let { rollout(it, now) },
            screen = snapshot?.let {
                screen(
                    // Add node and Locations open over their section's page.
                    props = props.copy(
                        route = if (props.route.isOverlay) props.route.navSection else props.route,
                        selection = props.selection.takeIf { !props.route.isOverlay },
                    ),
                    status = it,
                    now = now,
                    metrics = metrics,
                    log = log,
                )
            },
            overlay = snapshot?.let { overlay(props, it) },
            toasts = toasts,
            search = search(
                snapshot = snapshot,
                log = log,
                router = router,
            ),
            terminal = terminal.model(homegap.presentation.terminal.TerminalProps(connected = snapshot != null)),
            confirm = pendingConfirm?.let { confirmDialog(it) },
            onNavigate = remember { { route: Route -> router.navigate(route) } },
            onOpen = remember { { location: Location -> router.open(location) } },
        )
    }

    @Composable
    private fun confirmDialog(pending: Confirmations.Pending): ConfirmDialogModel {
        var typed by remember(pending) { mutableStateOf("") }
        return ConfirmDialogModel(
            model = pending.model,
            typed = typed,
            onType = { typed = it },
            onConfirm = { confirmations.resolve(confirmed = true) },
            onCancel = { confirmations.resolve(confirmed = false) },
            onSecondary = { confirmations.resolveSecondary() },
        )
    }

    @Composable
    private fun search(
        snapshot: StatusSnapshot?,
        log: List<homegap.api.LogEntryDto>,
        router: Router,
    ): SearchModel {
        var isOpen by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf<SearchKind?>(null) }
        // With no query nothing is highlighted, so Enter alone runs no suggested action.
        var highlighted by remember { mutableStateOf(NO_HIT) }
        val paused = snapshot?.paused == true
        val terminalNodes = if (terminals.enabled) snapshot?.nodes?.map { it.id }.orEmpty() else emptyList()
        val actionHits = remember(paused, terminalNodes) {
            listOfNotNull(
                SearchHit(
                    kind = SearchKind.Action,
                    title = "Resync now",
                    subtitle = "Run a reconcile pass",
                    run = { actions.run("Resync") { api.resync() } },
                ),
                SearchHit(
                    kind = SearchKind.Action,
                    title = "Add node",
                    subtitle = "Open the setup wizard",
                    location = Location(Route.AddNode),
                ),
                SearchHit(
                    kind = SearchKind.Action,
                    title = if (paused) "Resume changes" else "Pause all changes",
                    subtitle = if (paused) "Apply waiting changes" else "Observe only",
                    run = { togglePause(paused) },
                ),
                SearchHit(
                    kind = SearchKind.Action,
                    title = "Order the certificate now",
                    subtitle = "Start the certificate order without the hourly retry",
                    location = Location(Route.Certificates),
                ),
                SearchHit(
                    kind = SearchKind.Action,
                    title = "Check certificates now",
                    subtitle = "Run the TLS probes of every name through every node",
                    run = { actions.run("Check now") { api.checkCertificates() } },
                ),
                SearchHit(
                    kind = SearchKind.Action,
                    title = "Test all nodes",
                    subtitle = "Speed test each edge, one at a time",
                    location = Location(Route.SpeedTest),
                ),
                SearchHit(
                    kind = SearchKind.Action,
                    title = "Sign out",
                    subtitle = "Remove the dashboard token from this browser",
                    run = ::signOut,
                ),
            ) + terminalNodes.map { id ->
                SearchHit(
                    kind = SearchKind.Action,
                    title = "Terminal on $id",
                    subtitle = "Open a shell on the node",
                    run = { terminals.open(id) },
                )
            }
        }
        val index = remember(snapshot, log, actionHits) {
            snapshot?.let {
                searchIndex(
                    snapshot = it,
                    log = log,
                    actions = actionHits,
                )
            }.orEmpty()
        }
        // Config keys, secrets and older log entries come from the server, a moment after typing stops.
        val serverHits by produceState(emptyList<SearchHit>(), query) {
            value = emptyList()
            if (query.trim().length < 2) return@produceState
            delay(SEARCH_DELAY)
            runCatchingCancellable { api.search(query.trim()) }.onSuccess { response ->
                value = response.hits.map { it.toHit() }
            }
        }
        val hits = remember(index, query, kind, snapshot, actionHits, serverHits) {
            val found = if (query.isBlank()) {
                snapshot?.let { suggestions(it, actionHits) }.orEmpty()
            } else {
                val local = search(index, query)
                val known = local.mapNotNull { it.location }.toSet()
                preferLiteral(local + serverHits.filter { it.location !in known }, query).sortedBy { it.kind.ordinal }
            }
            found.filter { kind == null || it.kind == kind }
        }
        val groups = remember(hits, query) {
            if (query.isBlank()) {
                listOf(SearchGroup("Suggested", hits))
            } else {
                hits.groupBy { it.kind }.map { (group, groupHits) -> SearchGroup(group.title, groupHits) }
            }
        }
        val close = {
            isOpen = false
            query = ""
            kind = null
            highlighted = NO_HIT
        }
        return SearchModel(
            isOpen = isOpen,
            query = query,
            kind = kind,
            kinds = SearchKind.entries,
            groups = groups,
            highlighted = if (highlighted == NO_HIT) NO_HIT else highlighted.coerceIn(0, (hits.size - 1).coerceAtLeast(0)),
            emptyText = when {
                hits.isNotEmpty() -> null
                query.isBlank() -> "Type to search${kind?.let { " in ${it.title}" }.orEmpty()}."
                else -> "Nothing matches “${query.trim()}”${kind?.let { " in ${it.title}" }.orEmpty()}."
            },
            onOpen = { isOpen = true },
            onClose = close,
            onQuery = {
                query = it
                highlighted = if (it.isBlank()) NO_HIT else 0
            },
            onKind = {
                kind = it
                highlighted = if (query.isBlank()) NO_HIT else 0
            },
            onMove = { delta -> highlighted = (highlighted + delta).coerceIn(0, (hits.size - 1).coerceAtLeast(0)) },
            onChoose = { hit ->
                close()
                hit.run?.invoke()
                hit.location?.let { router.open(it) }
            },
        )
    }

    @Composable
    private fun screen(
        props: AppProps,
        status: StatusSnapshot,
        now: Long,
        metrics: Map<String, Map<String, Samples>>,
        log: List<homegap.api.LogEntryDto>,
    ): ScreenModel = when (props.route) {
        Route.Overview -> overview.model(
            OverviewScreenProps(
                status = status,
                now = now,
                metrics = metrics,
                log = log,
                selection = props.selection,
            )
        )
        Route.Nodes -> nodes.model(
            NodesScreenProps(
                status = status,
                now = now,
                metrics = metrics,
                selection = props.selection,
            )
        )
        Route.Services -> services.model(
            ServicesScreenProps(
                status = status,
                selection = props.selection,
            )
        )
        Route.Dns -> dns.model(
            DnsScreenProps(
                status = status,
                now = now,
                selection = props.selection,
            )
        )
        Route.Certificates -> certificates.model(
            homegap.presentation.certificates.CertificatesScreenProps(
                status = status,
                now = now,
                selection = props.selection,
            )
        )
        Route.Tunnels -> tunnels.model(
            TunnelsScreenProps(
                status = status,
                now = now,
                metrics = metrics,
                selection = props.selection,
            )
        )
        Route.SpeedTest -> speedTest.model(SpeedTestScreenProps(now, status))
        Route.Access -> {
            val currentRouter by rememberUpdatedState(props.router)
            access.model(
                AccessScreenProps(
                    selection = props.selection,
                    onOpen = remember { { location: Location -> currentRouter.open(location) } },
                    now = now,
                )
            )
        }
        Route.Activity -> activity.model(
            ActivityScreenProps(
                now = now,
                selection = props.selection,
            )
        )
        Route.Config -> config.model(ConfigScreenProps)
        Route.Diagnostics -> diagnostics.model(
            DiagnosticsScreenProps(
                status = status,
                selection = props.selection,
            )
        )
        Route.Locations, Route.AddNode -> error("${props.route} opens over its section's page")
    }

    /** The page that opens over its section: the Add node wizard over Nodes, Locations over Config. */
    @Composable
    private fun overlay(props: AppProps, status: StatusSnapshot): ScreenModel? = when (props.route) {
        Route.Locations -> locations.model(homegap.presentation.locations.LocationsScreenProps)
        Route.AddNode -> {
            val currentRouter by rememberUpdatedState(props.router)
            var run by remember { mutableIntStateOf(0) }
            key(run) {
                val wizard = remember { addNode() }
                wizard.model(
                    AddNodeScreenProps(
                        existingNodeIds = remember(status.nodes) { status.nodes.map { it.id }.toSet() },
                        onStartOver = { run++ },
                        onFinished = remember { { currentRouter.navigate(Route.Nodes) } },
                    )
                )
            }
        }

        else -> null
    }

    /** Resumes at once. A pause asks first, because nothing is applied while it lasts. */
    private fun togglePause(paused: Boolean) {
        if (paused) {
            actions.run("Resume") { api.pause(PauseScope.Global, null, false) }
            return
        }
        confirmations.ask(
            ConfirmModel(
                title = "Pause all changes?",
                text = "HomeGAP only observes: health and drift keep updating, but it applies nothing to the nodes, " +
                    "DNS or the tunnels until you resume.",
                confirmLabel = "Pause changes",
            )
        ) {
            actions.run("Pause") { api.pause(PauseScope.Global, null, true) }
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
            onResync = remember { { pausedGuard.run("Resync") { actions.run("Resync") { api.resync() } } } },
            onApplyOnce = remember { { actions.run("Run one pass") { api.applyOnce() } } },
            onTogglePause = remember { { togglePause(paused) } },
            onSignOut = remember { ::signOut },
        )
    }

    private fun signOut() {
        tokens.token = null
        session.signOut()
    }

    /**
     * The token prompt for [ConnectionProblem.Unauthorized]; for a failure, the lost-connection
     * dialog once it has lasted [LOST_GRACE] (null before, so a blip after waking a tab shows
     * nothing).
     */
    @Composable
    private fun connection(problem: ConnectionProblem, lastUpdate: Long?): ConnectionModel? {
        var draft by remember { mutableStateOf(tokens.token.orEmpty()) }
        // A saved token that still gets 401 was not accepted (a token remembered from before too).
        var rejected by remember { mutableStateOf(tokens.token != null) }
        var revealed by remember { mutableStateOf(false) }
        var checking by remember { mutableStateOf(false) }
        val save = appScope.rememberEventTrigger {
            checking = true
            try {
                tokens.token = draft.ifBlank { null }
                session.refresh()
                session.reconnect()
                rejected = tokens.token != null && session.problem.value == ConnectionProblem.Unauthorized
            } finally {
                checking = false
            }
        }
        val prompt = TokenPromptModel(
            value = draft,
            rejected = rejected,
            revealed = revealed,
            checking = checking,
            onToggleReveal = { revealed = !revealed },
            onChange = remember {
                { value: String ->
                    draft = value
                    rejected = false
                }
            },
            onSave = save::trigger,
        )
        return when (problem) {
            ConnectionProblem.Unauthorized -> ConnectionModel(
                message = "Enter the dashboard token to continue.",
                tokenPrompt = prompt,
                signInRequired = true,
            )

            is ConnectionProblem.Failed -> lostConnection(
                problem = problem,
                lastUpdate = lastUpdate,
            )?.let { lost ->
                ConnectionModel(
                    message = problem.message,
                    tokenPrompt = prompt,
                    lost = lost,
                )
            }
        }
    }

    @Composable
    private fun lostConnection(problem: ConnectionProblem.Failed, lastUpdate: Long?): LostConnectionModel? {
        val now by clock.rememberNow(period = 1.seconds)
        val failingSince = remember { clock.now().toEpochMilliseconds() }
        val attempts by session.failedAttempts.collectAsState()
        // Each failed attempt starts a wait; the countdown runs from when it was seen.
        val waitStartedAt = remember(attempts) { clock.now().toEpochMilliseconds() }
        var reconnectAt by remember { mutableStateOf<Int?>(null) }
        if (now - failingSince < LOST_GRACE.inWholeMilliseconds) {
            return null
        }
        val retryAt = waitStartedAt + ControllerSession.retryDelayAfter(attempts).inWholeMilliseconds
        val secondsLeft = (retryAt - now + 999) / 1000
        val reconnecting = attempts == 0 || reconnectAt == attempts || secondsLeft <= 0
        return LostConnectionModel(
            error = problem.message,
            retry = if (reconnecting) "Trying now…" else "Trying again in $secondsLeft s",
            attempts = attempts.takeIf { it > 0 }?.let { plural(it, "attempt") + " failed" },
            lastUpdate = lastUpdate?.let { "Last update ${ago(it, now)}" },
            reconnecting = reconnecting,
            onReconnect = {
                reconnectAt = attempts
                session.reconnect()
            },
        )
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
            onSkip = remember {
                { id: String ->
                    confirmations.ask(
                        ConfirmModel(
                            title = "Skip the update on $id?",
                            text = "$id stays on its current stack and the other nodes are updated.",
                            confirmLabel = "Skip $id",
                        )
                    ) {
                        actions.run("Skip update") { api.skipUpdate(id) }
                    }
                }
            },
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

/** Per section, how many items need attention and how bad the worst is: a count in the navigation. */
internal fun StatusSnapshot.attentionCounts(): Map<Route, AttentionCount> =
    attention.groupBy { it.location.route.navSection }.mapValues { (_, items) ->
        AttentionCount(
            count = items.size,
            severity = if (anyDown(items)) Health.DOWN else Health.DEGRADED,
        )
    }

/** The banner for a controller that answers requests but refuses the event stream, with its [reason]. */
internal fun noLiveUpdates(reason: String) = BannerModel(
    kind = BannerKind.Warn,
    text = "No live updates: ${reason.trimEnd('.')}. The status still refreshes every few seconds, but the log shows " +
        "nothing new and a terminal cannot connect.",
)

internal fun StatusSnapshot.banners(): List<BannerModel> = buildList {
    if (dryRun) add(BannerModel(BannerKind.Warn, "Dry-run mode: discovery and planning only. Nothing is written to nodes, Pangolin or DNS."))
    homelabIsolation?.let { iso ->
        val minutes = { ms: Long -> (ms / 60_000).coerceAtLeast(1) }
        val end = iso.endedAt
        when {
            end == null -> add(BannerModel(BannerKind.Error,
                "The homelab can't reach any node (for ${minutes(generatedAt - iso.startedAt)} min). Check the homelab's internet connection; " +
                    "the nodes keep serving, and fail the database over to one of them if this lasts."))
            generatedAt - end < 3_600_000L -> add(BannerModel(BannerKind.Info,
                "The homelab couldn't reach any node for ${minutes(end - iso.startedAt)} min, until ${minutes(generatedAt - end)} min ago. " +
                    "Nodes reaching each other meanwhile (see the mesh probes) means it was the homelab's connection."))
        }
    }
    if (paused) {
        add(
            BannerModel(
                kind = BannerKind.Warn,
                text = "Changes are paused. HomeGAP is only observing: health and drift keep updating, but nothing is applied " +
                    "to nodes, Pangolin or DNS, and manual changes there are left alone.",
                resume = true,
            )
        )
    }
}

/** How long search waits after the last keystroke before asking the server. */
private val SEARCH_DELAY = 200.milliseconds
