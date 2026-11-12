package homegap.presentation

import homegap.presentation.data.AlwaysVisible
import homegap.api.AccessDeviceDto
import homegap.api.AccessOverviewDto
import homegap.api.AccessRoleDto
import homegap.api.AccessUserDto
import homegap.api.AttentionItemDto
import homegap.api.Health
import homegap.api.LogEntryDto
import homegap.api.LogLevel
import homegap.api.ServerEvent
import homegap.api.NodeUpdateDto
import homegap.api.SpeedTestOverview
import homegap.presentation.access.AccessScreenModel
import homegap.presentation.access.AccessScreenPresenter
import homegap.presentation.access.AccessScreenProps
import homegap.presentation.activity.ActivityScreenModel
import homegap.presentation.activity.ActivityScreenPresenter
import homegap.presentation.activity.ActivityScreenProps
import homegap.presentation.app.AppPresenter
import homegap.presentation.app.AppProps
import homegap.presentation.config.ConfigScreenPresenter
import homegap.presentation.config.ConfigScreenProps
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.ControllerSession
import homegap.presentation.dns.DnsScreenPresenter
import homegap.presentation.addnode.*
import homegap.presentation.nodes.NodesScreenModel
import homegap.presentation.nodes.NodesScreenPresenter
import homegap.presentation.nodes.NodesScreenProps
import homegap.presentation.overview.OverviewScreenModel
import homegap.presentation.overview.OverviewScreenPresenter
import homegap.presentation.overview.OverviewScreenProps
import homegap.presentation.routing.Location
import homegap.presentation.services.ServiceActions
import homegap.presentation.nodes.NodeActions
import homegap.presentation.data.Confirmations
import homegap.presentation.routing.Route
import homegap.presentation.routing.Router
import homegap.presentation.services.ServicesScreenPresenter
import homegap.presentation.speedtest.SpeedTestScreenModel
import homegap.presentation.speedtest.SpeedTestScreenPresenter
import homegap.presentation.speedtest.SpeedTestScreenProps
import homegap.presentation.tunnels.TunnelsScreenPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PresenterTest {
    private class Graph(scope: CoroutineScope, val api: FakeControllerApi = FakeControllerApi()) {
        val session = ControllerSession(api, AlwaysVisible)
        val actions = ActionRunner(session, api, scope)
        val tokens = FakeTokenStore()
        val preferences = FakePreferences()
        val confirmations = Confirmations()
        val pausedGuard = homegap.presentation.data.PausedGuard(session, api, actions, confirmations)
        val relocation = homegap.presentation.nodes.NodeRelocation()
        val nodeActions = NodeActions(api, actions, confirmations, pausedGuard, relocation)
        val serviceActions = ServiceActions(api, actions, confirmations)
        val nodes = NodesScreenPresenter(nodeActions, preferences)
        val speedTest = SpeedTestScreenPresenter(api, session, actions, FakeTimeFormatter, scope)
        val activity = ActivityScreenPresenter(session, api, FakeTimeFormatter)
        val addNode = AddNodeScreenPresenter(api, session, AddNodeWizardStore(preferences), actions, scope)
        val access = AccessScreenPresenter(api, session, actions, confirmations, FakeTimeFormatter, scope)
        val overview = OverviewScreenPresenter(preferences, nodeActions, serviceActions, FakeTimeFormatter)
        val app = AppPresenter(
            session = session,
            actions = actions,
            confirmations = confirmations,
            pausedGuard = pausedGuard,
            api = api,
            tokens = tokens,
            clock = FixedClock,
            appScope = scope,
            overview = overview,
            nodes = nodes,
            services = ServicesScreenPresenter(serviceActions),
            dns = DnsScreenPresenter(api, actions),
            tunnels = TunnelsScreenPresenter(api, actions, confirmations),
            speedTest = speedTest,
            access = access,
            activity = activity,
            config = ConfigScreenPresenter(api),
            diagnostics = homegap.presentation.diagnostics.DiagnosticsScreenPresenter(api),
            locations = homegap.presentation.locations.LocationsScreenPresenter(api, actions, confirmations),
            addNode = addNode,
            nodeLocation = homegap.presentation.nodes.NodeLocationPresenter(relocation, api, actions),
        )
    }

    private val TestScope.graph get() = Graph(backgroundScope)

    private class RecordingRouter : Router {
        val routes = mutableListOf<Route>()
        override fun open(location: Location) {
            routes += location.route
        }
    }

    @Test
    fun overviewCountsHealthPerArea() = runTest {
        val g = graph
        val status = snapshot(nodes = listOf(node("a1"), node("b1", Health.DOWN)))
        val model = present(g.overview, OverviewScreenProps(status, NOW)).value
        val nodes = model.gauges.first { it.label == "Edges ok" }
        assertEquals("1 / 2", nodes.value)
        assertEquals(Health.DEGRADED, nodes.health)
        assertEquals(Health.UNKNOWN, model.gauges.first { it.label == "DNS in sync" }.health, "nothing to count")
        assertEquals(listOf("a1", "b1"), model.edges.map { it.id })
        assertEquals("1 of 2 edges healthy.", model.head.headline)
    }

    @Test
    fun overviewSelectsAnEdgeIntoTheInspector() = runTest {
        val g = graph
        val status = snapshot(nodes = listOf(node("a1"), node("b1")))
        val model = present(g.overview, OverviewScreenProps(status, NOW, selection = "b1")).value
        assertEquals("b1", model.inspector?.title)
        assertNull(present(g.overview, OverviewScreenProps(status, NOW, selection = "nowhere")).value.inspector)
    }

    @Test
    fun changeLocationOpensFromTheOverviewInspectorLikeOnNodes() = runTest {
        val g = graph
        g.session.refresh()
        val models = present(g.app, AppProps(Route.Overview, RecordingRouter(), selection = "a1"))
        val model = models.first { it.screen != null }
        val inspector = assertNotNull(model.screen?.inspector)
        val nodesInspector = present(g.nodes, NodesScreenProps(g.session.status.value!!, NOW, selection = "a1")).value.inspector
        assertEquals(nodesInspector?.actions?.map { it.label }, inspector.actions.map { it.label })
        assertNull(model.nodeLocation)

        inspector.actions.single { it.label == "Change location" }.onClick!!()
        val opened = models.first { it.nodeLocation != null }.nodeLocation!!
        assertEquals("a1", opened.nodeId)
        opened.onCancel()
        assertNull(models.first { it.nodeLocation == null }.nodeLocation)
    }

    @Test
    fun appWaitsForTheFirstSnapshotThenRendersTheRoutedScreen() = runTest {
        val g = graph
        val router = RecordingRouter()
        val models = present(g.app, AppProps(Route.Nodes, router))
        val loading = models.value
        assertTrue(loading.isLoading)
        assertNull(loading.header)

        g.session.refresh()
        val ready = models.first { it.screen != null }
        assertIs<NodesScreenModel>(ready.screen)
        assertEquals(Route.Nodes, ready.nav.single { it.selected }.route)
        assertEquals("Synced 12s ago", ready.header?.pass?.text)

        ready.onNavigate(Route.Dns)
        assertEquals(listOf(Route.Dns), router.routes)
    }

    @Test
    fun appFlagsTabsAndHoldsRollouts() = runTest {
        val g = graph
        val failed = node("a1", Health.DOWN, update = NodeUpdateDto("failed", "boom", NOW - 60_000, rolledBack = true))
        g.api.statusValue = snapshot(nodes = listOf(failed, node("b1", waiting = "rollout held: a1 failed")), paused = true).copy(
            attention = listOf(AttentionItemDto("node:a1:down", Health.DOWN, "a1 is down", since = NOW, route = "nodes", selection = "a1")),
        )
        g.session.refresh()
        val model = present(g.app, AppProps(Route.Overview, RecordingRouter())).first { it.screen != null }

        assertEquals(setOf(Route.Nodes), model.nav.filter { it.needsAttention }.map { it.route }.toSet())
        assertIs<OverviewScreenModel>(model.screen)
        assertEquals(1, model.banners.size, "global pause banner")
        val rollout = assertNotNull(model.rollout)
        assertEquals(listOf("a1"), rollout.failedNodes)
        assertTrue("rolled back" in rollout.summary && "Waiting: b1." in rollout.summary, rollout.summary)

        rollout.onSkip("a1")
        assertEquals("Skip the update on a1?", g.confirmations.pending.value?.model?.title)
        g.confirmations.resolve(confirmed = true)
        model.header!!.onTogglePause()
        testScheduler.runCurrent()
        assertEquals(listOf("skip a1", "pause global null false"), g.api.calls)
    }

    @Test
    fun wizardWalksFromFreshServerToSetupJob() = runTest {
        val g = graph
        var finished = false
        val models = present(g.addNode, AddNodeScreenProps(existingNodeIds = setOf("a1")) { finished = true })
        fun send(event: WizardEvent) = models.value.onEvent(event)
        suspend fun advanceTo(step: WizardStep) = models.first { it.step == step }

        // Server: invalid input blocks and explains.
        send(WizardEvent.Edit(WizardField.NodeId, "a1"))
        send(WizardEvent.Next)
        val blocked = models.first { it.errors.isNotEmpty() }
        assertTrue(blocked.errors.any { "already exists" in it } && blocked.errors.any { "public IP" in it }, blocked.errors.toString())
        send(WizardEvent.Edit(WizardField.NodeId, "eu1"))
        send(WizardEvent.Edit(WizardField.Host, "203.0.113.30"))
        send(WizardEvent.Edit(WizardField.Region, "EU"))
        send(WizardEvent.Next)
        advanceTo(WizardStep.Login)

        // Login: continuing needs a successful test of exactly these credentials.
        send(WizardEvent.Edit(WizardField.Password, "hunter2"))
        send(WizardEvent.Next)
        assertTrue(models.first { it.errors.isNotEmpty() }.errors.any { "Test the connection" in it })
        send(WizardEvent.TestConnection)
        val tested = models.first { it.connection != null }
        assertEquals("Connected to ubuntu 24.04 (x86_64)", tested.connection?.headline)
        send(WizardEvent.Edit(WizardField.Password, "changed"))
        assertNull(models.first { it[WizardField.Password] == "changed" }.connection, "editing the login invalidates the test")
        send(WizardEvent.TestConnection)
        models.first { it.connection != null }
        send(WizardEvent.Next)
        advanceTo(WizardStep.Access)

        // Access: a key is required; root login can only be disabled for a non-root user.
        send(WizardEvent.Next)
        assertTrue(models.first { it.errors.isNotEmpty() }.errors.any { "SSH key" in it })
        send(WizardEvent.CreateKey)
        assertEquals("SHA256:key", models.first { it.key != null }.key?.fingerprint)
        send(WizardEvent.Next)
        advanceTo(WizardStep.Firewall)

        // Firewall: restricting SSH starts from the address the server saw; an uncovered list warns.
        send(WizardEvent.Toggle(WizardToggle.RestrictSsh, true))
        assertEquals("198.51.100.7", models.first { it.has(WizardToggle.RestrictSsh) }[WizardField.AllowFrom])
        send(WizardEvent.Edit(WizardField.AllowFrom, "10.0.0.0/8"))
        assertTrue(models.first { it[WizardField.AllowFrom] == "10.0.0.0/8" }.notices.any { "not covered" in it.text })
        send(WizardEvent.Edit(WizardField.AllowFrom, "198.51.100.0/24"))
        assertTrue(models.first { it[WizardField.AllowFrom] == "198.51.100.0/24" }.notices.none { "not covered" in it.text })
        send(WizardEvent.Next)
        advanceTo(WizardStep.Review)

        // Run: the request carries everything, and the job is followed (from the event stream) until it finishes.
        send(WizardEvent.Run)
        models.first { it.job?.running == true }
        g.session.onEvent(
            ServerEvent.Job(
                homegap.api.SetupJobDto(
                    id = "job1",
                    nodeId = "eu1",
                    state = "succeeded",
                    startedAt = NOW,
                    steps = listOf(homegap.api.SetupStepDto("connect", "Connect as root", "ok")),
                )
            )
        )
        val done = models.first { it.job?.succeeded == true }
        val request = assertNotNull(g.api.lastSetup)
        assertEquals("homegap", request.createUser)
        assertEquals("homegap", request.node.sshUser)
        assertEquals("/data/ssh/eu1_ed25519", request.keyFile)
        assertEquals("changed", request.login.password)
        assertEquals(listOf("198.51.100.0/24"), request.sshAllowFrom)
        assertTrue(request.disableRootLogin && request.disablePasswordLogin && request.configureFirewall)
        assertEquals("", done[WizardField.Password], "the password is dropped once setup succeeded")

        send(WizardEvent.Finish)
        assertTrue(finished)
        assertEquals(WizardStep.Server, models.first { it.job == null }.step, "finishing resets the wizard")
    }

    @Test
    fun wizardRefusesToDisableRootLoginForRoot() = runTest {
        val draft = AddNodeDraft(toggles = setOf(WizardToggle.DisableRootLogin), values = mapOf(WizardField.LoginUser to "root"))
        assertTrue(AddNodeValidation.errors(draft, WizardStep.Access, emptySet()).any { "Root login" in it })
        assertTrue(AddNodeValidation.covers(listOf("10.1.0.0/16"), "10.1.2.3"))
        assertFalse(AddNodeValidation.covers(listOf("10.1.0.0/16"), "10.2.0.1"))
    }

    @Test
    fun activityFiltersTheStreamedLogAndStartsOverAfterARestart() = runTest {
        val g = graph
        fun log(vararg entries: LogEntryDto) =
            g.session.onEvent(ServerEvent.Log(entries.toList()))
        log(LogEntryDto(1, "dns", LogLevel.INFO, "a", seq = 1), LogEntryDto(2, "nodes", LogLevel.CHANGE, "b", seq = 2))

        val models = present(g.activity, ActivityScreenProps())
        assertEquals(listOf("b", "a"), models.value.entries.map { it.message }, "newest first")
        models.value.onDomain("dns")
        val filtered: ActivityScreenModel = models.first { it.domain == "dns" }
        assertEquals(listOf("a"), filtered.entries.map { it.message })

        log(LogEntryDto(3, "dns", LogLevel.INFO, "after restart", seq = 1))
        assertEquals(listOf("after restart"), g.session.log.value.map { it.message })
    }

    @Test
    fun diagnosticsFetchesTheChosenNodesLogsAndBundle() = runTest {
        val g = graph
        val status = snapshot(nodes = listOf(node("a1"), node("b1")))
        val models = present(homegap.presentation.diagnostics.DiagnosticsScreenPresenter(g.api), homegap.presentation.diagnostics.DiagnosticsScreenProps(status))
        assertEquals("a1", models.value.node, "the first node until one is chosen")
        models.value.onSelectNode("b1")
        models.first { it.node == "b1" }.onSelectService("haproxy")
        models.first { it.service == "haproxy" }.onSelectSince("6h")
        models.first { it.since == "6h" }.onShowLogs()
        val logs = models.first { it.output != null }
        assertEquals("haproxy logs", logs.output?.title)
        assertTrue("logs b1 haproxy 6h 2000" in g.api.calls)
        logs.onRunDiagnostics()
        models.first { it.output?.title == "Diagnostics" }
        assertTrue("diagnostics b1" in g.api.calls)
    }

    @Test
    fun speedTestRunsWithTheChosenStreamCount() = runTest {
        val g = graph
        val disabled = present(g.speedTest, SpeedTestScreenProps(NOW)).first { it != SpeedTestScreenModel.Loading }
        assertEquals(SpeedTestScreenModel.Disabled, disabled)

        val enabled = graph
        enabled.api.speedtestValue = SpeedTestOverview(enabled = true, nodes = listOf("a1"), streams = 4)
        val models = present(enabled.speedTest, SpeedTestScreenProps(NOW))
        val ready = models.first { it is SpeedTestScreenModel.Ready } as SpeedTestScreenModel.Ready
        assertEquals(4, ready.streams)
        assertNull(ready.nodeTests.single().metrics)
        ready.onSelectStreams(8)
        val eight = models.first { (it as? SpeedTestScreenModel.Ready)?.streams == 8 } as SpeedTestScreenModel.Ready
        eight.onRunTest("a1")
        enabled.actions.toast.first { it != null }
        assertEquals(listOf("test a1 8"), enabled.api.calls)
        assertEquals("a1: 250 Mb/s down, 40.0 Mb/s up, 170.0 ms ping", enabled.actions.toast.value?.text)
    }

    @Test
    fun speedTestRunsEveryNodeInTurnAndShowsTheRunningTest() = runTest {
        val g = graph
        g.api.speedtestValue = SpeedTestOverview(enabled = true, nodes = listOf("a1", "b1"), streams = 4)
        val models = present(g.speedTest, SpeedTestScreenProps(NOW))
        val ready = models.first { it is SpeedTestScreenModel.Ready } as SpeedTestScreenModel.Ready
        assertEquals("Test all nodes", ready.allButtonText)
        assertNull(ready.live)
        // A test running anywhere shows on the gauge, pushed by the event stream.
        g.session.onEvent(ServerEvent.SpeedTestLive(homegap.api.SpeedTestLiveDto("a1", "download", pingMs = 170.0, mbps = 180.0, mbpsSamples = listOf(90.0, 180.0))))
        assertEquals("180", (models.first { (it as? SpeedTestScreenModel.Ready)?.live != null } as SpeedTestScreenModel.Ready).live?.value)
        ready.onRunAll()
        g.actions.toast.first { it != null }
        assertEquals(listOf("speed run 4"), g.api.calls, "the orchestrator runs the nodes one at a time")
    }

    @Test
    fun theLiveGaugeShowsTheCurrentPhase() {
        assertEquals(250.0, homegap.presentation.speedtest.gaugeScale(180.0))
        assertEquals(10.0, homegap.presentation.speedtest.gaugeScale(0.0))
        assertEquals(10000.0, homegap.presentation.speedtest.gaugeScale(1e9))
        val starting = homegap.presentation.speedtest.liveTestModel("a1", null, 1 to 2)
        assertEquals("Testing a1 (1 of 2)", starting.title)
        assertEquals("Starting…", starting.phase)
        assertEquals(0.0, starting.fraction)
        val ping = homegap.presentation.speedtest.liveTestModel("a1", homegap.api.SpeedTestLiveDto("a1", "ping", pingSamples = listOf(20.0, 40.0), pingMs = 30.0, jitterMs = 20.0), null)
        assertEquals("40.0", ping.value)
        assertEquals("ms", ping.unit)
        assertEquals("50 ms", ping.scaleMax)
        assertEquals(listOf(0.5, 1.0), ping.bars)
        val down = homegap.presentation.speedtest.liveTestModel("a1", homegap.api.SpeedTestLiveDto("a1", "download", pingMs = 30.0, mbps = 180.0, mbpsSamples = listOf(90.0, 180.0)), null)
        assertEquals("Download", down.phase)
        assertEquals("180", down.value)
        assertEquals(180.0 / 250.0, down.fraction)
        assertEquals(listOf("30.0 ms", "—", "180 Mb/s", "—"), down.metrics.map { it.value })
        assertEquals(homegap.presentation.ui.ChartTone.Cyan, down.tone)
    }

    @Test
    fun accessCreatesUsersWithAPasswordOrAnInviteLink() = runTest {
        val unavailable = present(graph.access, AccessScreenProps()).first { it != AccessScreenModel.Loading }
        assertEquals("no cluster", (unavailable as AccessScreenModel.Unavailable).message)

        val g = graph
        g.api.accessValue = AccessOverviewDto(
            available = true, clientAddress = "https://pangolin.x", via = "na1",
            roles = listOf(AccessRoleDto(1, "Admin", admin = true), AccessRoleDto(2, "Member")),
            users = listOf(AccessUserDto("u1", "admin@x", "admin@x", roleIds = listOf(1), roles = listOf("Admin"), owner = true)),
            devices = listOf(AccessDeviceDto(7, "phone", "user", user = "p@x", online = true, viaNode = "as1")),
        )
        val models = present(g.access, AccessScreenProps())
        val ready = models.first { it is AccessScreenModel.Ready } as AccessScreenModel.Ready
        assertEquals("https://pangolin.x", ready.clientAddress)
        assertFalse(ready.users.single().editable, "the server admin stays")
        assertEquals("via as1", ready.devices.single().via)
        // New users default to the least-privileged role, and need a valid email and password.
        assertEquals(listOf("Member"), ready.newUser.roles.filter { it.selected }.map { it.name })
        assertFalse(ready.newUser.canSubmit)

        ready.newUser.onEmail("p@x.org")
        ready.newUser.onPassword("short")
        val short = models.first { (it as? AccessScreenModel.Ready)?.newUser?.password == "short" } as AccessScreenModel.Ready
        assertEquals("The password needs at least 8 characters", short.newUser.error)
        short.newUser.onPassword("long enough")
        val valid = models.first { (it as? AccessScreenModel.Ready)?.newUser?.canSubmit == true } as AccessScreenModel.Ready
        valid.newUser.onSubmit()
        models.first { (it as? AccessScreenModel.Ready)?.newUser?.email == "" }
        assertEquals("create p@x.org [2] password", g.api.calls.last())

        // Without a password, the invite link is shown to hand over.
        val cleared = models.first { (it as? AccessScreenModel.Ready)?.newUser?.email == "" } as AccessScreenModel.Ready
        cleared.newUser.onSetPassword(false)
        cleared.newUser.onEmail("q@x.org")
        val invite = models.first { (it as? AccessScreenModel.Ready)?.newUser?.let { f -> !f.setPassword && f.canSubmit } == true } as AccessScreenModel.Ready
        assertEquals("Create invite link", invite.newUser.submitText)
        invite.newUser.onSubmit()
        val linked = models.first { (it as? AccessScreenModel.Ready)?.inviteLink != null } as AccessScreenModel.Ready
        assertEquals("https://pangolin.x/invite?token=a-b", linked.inviteLink)
        assertEquals("create q@x.org [2] invite", g.api.calls.last())

        linked.onDeviceAction(7, "block")
        assertTrue(g.api.calls.none { "device" in it }, "blocking asks first")
        assertEquals("Block phone?", g.confirmations.pending.value?.model?.title)
        g.confirmations.resolve(confirmed = true)
        g.actions.toast.first { it?.text == "ok: device 7 block" }
    }

    @Test
    fun configHidesUnchangedItemsUntilAskedAndShowsMissingSecrets() = runTest {
        val models = present(ConfigScreenPresenter(graph.api), ConfigScreenProps)
        val loaded = models.first { !it.loading }
        assertEquals(listOf("y"), loaded.diff.map { it.target })
        assertEquals("missing", loaded.secrets.single().present.text)
        loaded.onToggleOnlyChanges(false)
        assertEquals(2, models.first { !it.onlyChanges }.diff.size)
    }
}
