package homegap.presentation

import homegap.api.Health
import homegap.api.LogEntryDto
import homegap.api.LogLevel
import homegap.api.LogResponse
import homegap.api.NodeUpdateDto
import homegap.api.SpeedTestOverview
import homegap.presentation.activity.ActivityScreenModel
import homegap.presentation.activity.ActivityScreenPresenter
import homegap.presentation.activity.ActivityScreenProps
import homegap.presentation.app.AppPresenter
import homegap.presentation.app.AppProps
import homegap.presentation.config.ConfigScreenPresenter
import homegap.presentation.config.ConfigScreenProps
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.ControllerSession
import homegap.presentation.data.ServerEvent
import homegap.presentation.dns.DnsScreenPresenter
import homegap.presentation.nodes.NodeField
import homegap.presentation.nodes.NodesScreenModel
import homegap.presentation.nodes.NodesScreenPresenter
import homegap.presentation.nodes.NodesScreenProps
import homegap.presentation.overview.OverviewScreenModel
import homegap.presentation.overview.OverviewScreenPresenter
import homegap.presentation.overview.OverviewScreenProps
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
import kotlinx.serialization.json.Json
import kotlin.test.*

class PresenterTest {
    private class Graph(scope: CoroutineScope, val api: FakeControllerApi = FakeControllerApi()) {
        val session = ControllerSession(api)
        val actions = ActionRunner(session, scope)
        val tokens = FakeTokenStore()
        val nodes = NodesScreenPresenter(api, actions)
        val speedTest = SpeedTestScreenPresenter(api, actions, FakeTimeFormatter, scope)
        val activity = ActivityScreenPresenter(session, FakeTimeFormatter)
        val app = AppPresenter(
            session, actions, api, tokens, FixedClock, scope,
            OverviewScreenPresenter(), nodes, ServicesScreenPresenter(api, actions), DnsScreenPresenter(),
            TunnelsScreenPresenter(), speedTest, activity, ConfigScreenPresenter(api),
        )
    }

    private val TestScope.graph get() = Graph(backgroundScope)

    private class RecordingRouter : Router {
        val routes = mutableListOf<Route>()
        override fun navigate(route: Route) { routes += route }
    }

    @Test
    fun overviewCountsHealthPerArea() = runTest {
        val status = snapshot(nodes = listOf(node("a1"), node("b1", Health.DOWN)))
        val model = present(OverviewScreenPresenter(), OverviewScreenProps(status, NOW)).value
        val nodes = model.stats.first { it.label == "Nodes healthy" }
        assertEquals("1 / 2", nodes.value)
        assertEquals(Health.DEGRADED, nodes.health)
        assertEquals(Health.UNKNOWN, model.stats.first { it.label == "DNS in sync" }.health, "nothing to count")
        assertEquals(listOf("a1", "b1"), model.nodes.map { it.id })
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
        g.api.statusValue = snapshot(nodes = listOf(failed, node("b1", waiting = "rollout held: a1 failed")), paused = true)
        g.session.refresh()
        val model = present(g.app, AppProps(Route.Overview, RecordingRouter())).first { it.screen != null }

        assertEquals(setOf(Route.Nodes), model.nav.filter { it.needsAttention }.map { it.route }.toSet())
        assertIs<OverviewScreenModel>(model.screen)
        assertEquals(1, model.banners.size, "global pause banner")
        val rollout = assertNotNull(model.rollout)
        assertEquals(listOf("a1"), rollout.failedNodes)
        assertTrue("rolled back" in rollout.summary && "Waiting: b1." in rollout.summary, rollout.summary)

        rollout.onSkip("a1")
        model.header!!.onTogglePause()
        testScheduler.runCurrent()
        assertEquals(listOf("skip a1", "pause global null false"), g.api.calls)
    }

    @Test
    fun nodeFormSubmitsOnlyWhenComplete() = runTest {
        val g = graph
        val models = present(g.nodes, NodesScreenProps(snapshot(), NOW))
        val form = models.value.addNode
        assertFalse(form.canSubmit)
        form.onChange(NodeField.Id, "eu1")
        form.onChange(NodeField.Host, " 203.0.113.30 ")
        form.onChange(NodeField.KeyFile, "/k")
        val complete = models.first { it.addNode.canSubmit }.addNode
        complete.onSubmit()
        testScheduler.runCurrent()
        assertEquals(listOf("add eu1 203.0.113.30 /k"), g.api.calls)
    }

    @Test
    fun activityFiltersTheStreamedLogAndStartsOverAfterARestart() = runTest {
        val g = graph
        fun log(vararg entries: LogEntryDto) =
            g.session.onEvent(ServerEvent("log", Json.encodeToString(LogResponse.serializer(), LogResponse(entries.toList()))))
        log(LogEntryDto(1, "dns", LogLevel.INFO, "a", seq = 1), LogEntryDto(2, "nodes", LogLevel.CHANGE, "b", seq = 2))

        val models = present(g.activity, ActivityScreenProps)
        assertEquals(listOf("b", "a"), models.value.entries.map { it.message }, "newest first")
        models.value.onSelectDomain("dns")
        val filtered: ActivityScreenModel = models.first { it.selectedDomain == "dns" }
        assertEquals(listOf("a"), filtered.entries.map { it.message })

        log(LogEntryDto(3, "dns", LogLevel.INFO, "after restart", seq = 1))
        assertEquals(listOf("after restart"), g.session.log.value.map { it.message })
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
        assertEquals("a1: 250 Mbps down, 40.0 Mbps up, 170.0 ms ping", enabled.actions.toast.value?.text)
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
