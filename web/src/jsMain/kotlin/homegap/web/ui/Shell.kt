package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import homegap.presentation.activity.ActivityScreenModel
import homegap.presentation.app.AppModel
import homegap.presentation.app.HeaderModel
import homegap.presentation.app.NavItem
import homegap.presentation.app.PassStatusModel
import homegap.presentation.addnode.AddNodeScreenModel
import homegap.presentation.config.ConfigScreenModel
import homegap.presentation.core.ScreenModel
import homegap.presentation.dns.DnsScreenModel
import homegap.presentation.nodes.NodesScreenModel
import homegap.presentation.overview.OverviewScreenModel
import homegap.presentation.routing.Route
import homegap.presentation.services.ServicesScreenModel
import homegap.presentation.speedtest.SpeedTestScreenModel
import homegap.presentation.tunnels.TunnelsScreenModel
import homegap.web.routing.HashRouter
import homegap.web.screens.*
import kotlinx.browser.document
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.dom.*
import kotlin.time.Duration.Companion.milliseconds

/** Page layout: sidebar (desktop), top bar, tab strip (mobile) and the active screen. */
@Composable
fun App(model: AppModel) {
    LaunchedEffect(model.route) { scrollActiveTabIntoView() }
    Div({ classes("shell") }) {
        Aside({ classes("sidebar") }) {
            Brand()
            NavLinks(model.nav, model.onNavigate, "side-nav")
            Div({ classes("sidebar-foot") }) {
                model.header?.let {
                    Div({ classes("mono") }) { Text(it.domain) }
                    Div { Text(it.versionLine) }
                }
            }
        }
        Div({ classes("main-col") }) {
            TopBar(model.route, model.header)
            NavLinks(model.nav, model.onNavigate, "tabs")
            Main({ classes("content") }) { Content(model) }
        }
    }
    model.toast?.let { toast ->
        Div({
            classes("toast")
            if (toast.isError) classes("toast-error")
        }) { Text(toast.text) }
    }
}

@Composable
private fun Content(model: AppModel) {
    model.connection?.let { connection ->
        Div({ classes("banner", "error") }) { Text(connection.message) }
        connection.tokenPrompt?.let { TokenBox(it) }
    }
    if (model.isLoading) {
        Div({ classes("loading") }) { Text("Loading…") }
        return
    }
    model.banners.forEach { Banner(it) }
    model.rollout?.let { RolloutBanner(it) }
    model.screen?.let { Screen(it, model.onNavigate) }
}

@Composable
private fun Screen(screen: ScreenModel, onNavigate: (Route) -> Unit) {
    when (screen) {
        is OverviewScreenModel -> OverviewScreen(screen, onNavigate)
        is NodesScreenModel -> NodesScreen(screen, onNavigate)
        is ServicesScreenModel -> ServicesScreen(screen)
        is DnsScreenModel -> DnsScreen(screen)
        is TunnelsScreenModel -> TunnelsScreen(screen)
        is SpeedTestScreenModel -> SpeedTestScreen(screen)
        is ActivityScreenModel -> ActivityScreen(screen)
        is ConfigScreenModel -> ConfigScreen(screen)
        is AddNodeScreenModel -> AddNodeScreen(screen)
    }
}

@Composable
private fun TopBar(route: Route, header: HeaderModel?) {
    Header({ classes("topbar") }) {
        Div({ classes("topbar-brand") }) { Brand() }
        Div({ classes("page-head") }) {
            H1 { Text(route.title) }
            P({ classes("page-sub") }) { Text(route.subtitle) }
        }
        header?.let { HeaderActions(it) }
    }
}

@Composable
private fun HeaderActions(header: HeaderModel) {
    Div({ classes("topbar-actions") }) {
        PassStatus(header.pass)
        ActionButton("Resync", ButtonStyle.Primary, enabled = !header.busy, onClick = header.onResync)
        if (header.paused) {
            ActionButton("Resume", tooltip = "Resume reconciliation everywhere", onClick = header.onTogglePause)
        } else {
            ActionButton("Pause", tooltip = "Stop all changes; observe only", onClick = header.onTogglePause)
        }
    }
}

@Composable
private fun PassStatus(pass: PassStatusModel) {
    Span({
        classes("pass-status")
        pass.tooltip?.let { title(it) }
    }) {
        Span({ classes("status-dot", pass.health.name) })
        Text(pass.text)
    }
}

@Composable
private fun Brand() {
    Div({ classes("brand") }) {
        Div({ classes("brand-mark") }) { Text("G") }
        Div {
            Div({ classes("brand-name") }) { Text("HomeGAP") }
            Div({ classes("brand-sub") }) { Text("Global Access Platform") }
        }
    }
}

/** Route links; rendered twice (sidebar and mobile strip) with different [className]s. */
@Composable
private fun NavLinks(items: List<NavItem>, onNavigate: (Route) -> Unit, className: String) {
    Nav({ classes(className) }) {
        items.forEach { item ->
            NavLink(HashRouter.href(item.route), { onNavigate(item.route) }, if (item.selected) listOf("active") else emptyList()) {
                Span { Text(item.route.title) }
                if (item.needsAttention) Span({ classes("dot"); title("Needs attention") })
            }
        }
    }
}

/** Keeps the active tab visible in the horizontally scrolling mobile tab strip. */
private suspend fun scrollActiveTabIntoView() {
    delay(30.milliseconds) // after the new active class is rendered
    document.querySelector(".tabs a.active")
        ?.asDynamic()
        ?.scrollIntoView(js("({ inline: 'center', block: 'nearest' })"))
}
