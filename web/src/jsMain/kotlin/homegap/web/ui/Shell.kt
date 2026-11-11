package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.api.Health
import homegap.presentation.activity.ActivityScreenModel
import homegap.presentation.access.AccessScreenModel
import homegap.presentation.addnode.AddNodeScreenModel
import homegap.presentation.app.AppModel
import homegap.presentation.app.ConnectionModel
import homegap.presentation.app.HeaderModel
import homegap.presentation.app.NavItem
import homegap.presentation.app.PassStatusModel
import homegap.presentation.app.ReadOnlyModel
import homegap.presentation.config.ConfigScreenModel
import homegap.presentation.core.ScreenModel
import homegap.presentation.diagnostics.DiagnosticsScreenModel
import homegap.presentation.dns.DnsScreenModel
import homegap.presentation.locations.LocationsScreenModel
import homegap.presentation.nodes.NodesScreenModel
import homegap.presentation.overview.OverviewScreenModel
import homegap.presentation.routing.Location
import homegap.presentation.routing.NavGroup
import homegap.presentation.routing.Route
import homegap.presentation.services.ServicesScreenModel
import homegap.presentation.sharing.ShareLinksScreenModel
import homegap.presentation.speedtest.SpeedTestScreenModel
import homegap.presentation.tunnels.TunnelsScreenModel
import homegap.web.routing.HashRouter
import homegap.web.screens.*
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoFocus
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * The page shell: the navigation (a side menu on desktops, an icon rail on tablets, a header with
 * an icon grid on phones; CSS picks the form), the page title and the active screen.
 */
@Composable
fun AppShell(model: AppModel) {
    CompositionLocalProvider(LocalReadOnly provides (model.readOnly != null)) {
        Shell(model)
    }
}

@Composable
private fun Shell(model: AppModel) {
    val viewport = rememberViewport()
    val inspector = model.screen?.inspector
    SearchShortcut(model.search)
    // Phones: once the page scrolls past this marker, the icon grid shrinks to one row (CSS).
    Div({
        classes("nav-sentinel")
        attr("aria-hidden", "true")
    })
    Div({ classes("shell") }) {
        Aside({ classes("nav") }) {
            ShrinkOnScroll()
            Div({ classes("nav-head") }) {
                Brand()
                Div({ classes("nav-head-actions") }) {
                    IconButton(
                        iconPath = Icons.SEARCH,
                        label = "Search everything",
                        onClick = model.search.onOpen,
                    )
                    model.header?.takeIf { !it.readOnly }?.let { header ->
                        PhoneActions(header)
                    }
                }
            }
            SearchField(model.search)
            NavList(
                items = model.nav,
                onNavigate = model.onNavigate,
            )
            model.header?.let { header ->
                ReconcileBox(header)
                NavFoot(header)
            }
        }
        Main({ classes("page") }) {
            val head = model.screen?.head
            if (head != null) {
                PageHead(
                    head = head,
                    onOpen = model.onOpen,
                )
            } else {
                RouteHead(model.route)
            }
            Content(
                model = model,
                viewport = viewport,
            )
        }
        if (inspector != null) {
            InspectorHost(
                inspector = inspector,
                viewport = viewport,
                onClose = { model.onOpen(Location(model.route)) },
                onOpen = model.onOpen,
            )
        }
    }
    when (val overlay = model.overlay) {
        is AddNodeScreenModel -> AddNodeWizard(
            model = overlay,
            viewport = viewport,
            onClose = { model.onNavigate(Route.Nodes) },
        )

        is LocationsScreenModel -> LocationsOverlay(
            model = overlay,
            viewport = viewport,
            onClose = { model.onNavigate(Route.Config) },
        )

        is ShareLinksScreenModel -> ShareLinksOverlay(
            model = overlay,
            viewport = viewport,
            onClose = { model.onNavigate(Route.Access) },
        )

        else -> Unit
    }
    if (model.search.isOpen) {
        SearchOverlay(
            search = model.search,
            viewport = viewport,
        )
    }
    model.confirm?.let { dialog ->
        ConfirmDialog(
            dialog = dialog,
            viewport = viewport,
        )
    }
    model.toast?.let { toast ->
        Div({
            classes("toast")
            if (toast.isError) {
                classes("toast-error")
            }
            attr("role", if (toast.isError) "alert" else "status")
        }) {
            Span { Text(toast.text) }
            val action = toast.action
            if (action != null) {
                Button({
                    classes("toast-action")
                    onClick { action() }
                }) {
                    Text(toast.actionLabel ?: "Undo")
                }
            }
        }
    }
    model.connection?.takeIf { it.signInRequired }?.let { connection ->
        SignInDialog(connection)
    }
}

@Composable
private fun Content(model: AppModel, viewport: Viewport) {
    model.connection?.takeIf { !it.signInRequired }?.let { connection ->
        Div({ classes("banner", "error") }) { Text(connection.message) }
        connection.tokenPrompt?.let { TokenBox(it) }
    }
    if (model.isLoading) {
        Div({ classes("loading") }) { Text("Loading…") }
        return
    }
    model.readOnly?.let { readOnly ->
        ReadOnlyBanner(readOnly)
    }
    model.banners.forEach { banner ->
        Banner(
            banner = banner,
            onResume = { model.header?.onTogglePause?.invoke() },
        )
    }
    model.rollout?.let { RolloutBanner(it) }
    model.screen?.let { screen ->
        val empty = screen.empty
        if (empty != null) {
            EmptyState(
                empty = empty,
                onOpen = model.onOpen,
            )
        } else {
            Screen(
                screen = screen,
                viewport = viewport,
                onNavigate = model.onNavigate,
                onOpen = model.onOpen,
            )
        }
    }
}

@Composable
private fun Screen(
    screen: ScreenModel,
    viewport: Viewport,
    onNavigate: (Route) -> Unit,
    onOpen: (Location) -> Unit,
) {
    when (screen) {
        is OverviewScreenModel -> OverviewScreen(
            model = screen,
            onOpen = onOpen,
        )
        is NodesScreenModel -> NodesScreen(
            model = screen,
            onOpen = onOpen,
        )
        is ServicesScreenModel -> ServicesScreen(
            model = screen,
            onOpen = onOpen,
        )
        is DnsScreenModel -> DnsScreen(
            model = screen,
            onOpen = onOpen,
        )
        is TunnelsScreenModel -> TunnelsScreen(
            model = screen,
            onOpen = onOpen,
        )
        is SpeedTestScreenModel -> SpeedTestScreen(screen)
        is AccessScreenModel -> AccessScreen(
            model = screen,
            viewport = viewport,
            onOpen = onOpen,
        )
        is ActivityScreenModel -> ActivityScreen(
            model = screen,
            onOpen = onOpen,
        )
        is ConfigScreenModel -> ConfigScreen(screen)
        is DiagnosticsScreenModel -> DiagnosticsScreen(screen)
        // Opened over their section by the shell, never as the screen itself.
        is LocationsScreenModel, is AddNodeScreenModel, is ShareLinksScreenModel -> Unit
    }
}

/** A share link's view: it can look, never change; an admin can sign in instead. */
@Composable
private fun ReadOnlyBanner(readOnly: ReadOnlyModel) {
    Div({ classes("banner", "info", "read-only-banner") }) {
        Span { Text(readOnly.text) }
        ActionButton(
            text = "Sign in as admin",
            small = true,
            mutates = false,
            onClick = readOnly.onSignIn,
        )
    }
}

/** The page title for screens that don't have their own head yet. */
@Composable
private fun RouteHead(route: Route) {
    Header({ classes("page-head") }) {
        Div({ classes("caption") }) {
            Text(route.navSection.title)
            if (route.navSection != route) {
                Text(" · ${route.title}")
            }
        }
        H1 { Text(route.title) }
        P({ classes("page-sub") }) { Text(route.subtitle) }
    }
}

@Composable
private fun Brand() {
    A(href = HashRouter.href(Route.DEFAULT), {
        classes("brand")
        attr("aria-label", "HomeGAP overview")
    }) {
        Img(src = "/emblem.svg", alt = "") { classes("brand-mark") }
        Span({ classes("brand-name") }) {
            Text("Home")
            Span({ classes("brand-gap") }) { Text("GAP") }
        }
    }
}

/** The ten sections in two groups, each with the count of its items needing attention. */
@Composable
private fun NavList(items: List<NavItem>, onNavigate: (Route) -> Unit) {
    Nav({
        classes("nav-list")
        attr("aria-label", "Sections")
    }) {
        NavGroup.entries.forEach { group ->
            Div({ classes("nav-group") }) {
                Div({ classes("nav-group-title") }) { Text(group.title) }
                items.filter { it.route.group == group }.forEach { item ->
                    NavEntry(
                        item = item,
                        onNavigate = onNavigate,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavEntry(item: NavItem, onNavigate: (Route) -> Unit) {
    val attention = item.attention
    NavLink(
        href = HashRouter.href(item.route),
        onNavigate = { onNavigate(item.route) },
        classNames = if (item.selected) listOf("nav-link", "active") else listOf("nav-link"),
    ) {
        Icon(item.route.iconPath)
        Span({ classes("nav-label") }) { Text(item.route.title) }
        Span({ classes("nav-label-short") }) { Text(item.route.shortTitle) }
        if (attention != null) {
            val severity = if (attention.severity == Health.DOWN) "down" else "warn"
            Span({
                classes("nav-count", severity)
                attr("aria-label", "${attention.count} need attention")
            }) {
                Text(attention.count.toString())
            }
        }
    }
}

/** The reconcile loop: the last pass, Pause or Resume, and Resync. Amber while paused. */
@Composable
private fun ReconcileBox(header: HeaderModel) {
    Div({
        classes("reconcile")
        if (header.paused) {
            classes("paused")
        }
    }) {
        PassStatus(
            pass = header.pass,
            paused = header.paused,
        )
        if (header.readOnly) {
            return@Div
        }
        Div({ classes("reconcile-actions") }) {
            PauseButton(header)
            if (header.paused) {
                ActionButton(
                    text = "Run one pass",
                    enabled = !header.busy,
                    tooltip = "Apply the waiting changes once; changes stay paused",
                    onClick = header.onApplyOnce,
                )
            } else {
                ActionButton(
                    text = "Resync",
                    style = ButtonStyle.Primary,
                    enabled = !header.busy,
                    tooltip = "Run a reconcile pass now",
                    onClick = header.onResync,
                )
            }
        }
    }
}

@Composable
private fun PauseButton(header: HeaderModel) {
    if (header.paused) {
        ActionButton(
            text = "Resume",
            tooltip = "Resume reconciliation everywhere",
            onClick = header.onTogglePause,
        )
    } else {
        ActionButton(
            text = "Pause",
            tooltip = "Stop all changes; observe only",
            onClick = header.onTogglePause,
        )
    }
}

/** Phones: Pause or Resume, and Resync, as icon buttons in the header. */
@Composable
private fun PhoneActions(header: HeaderModel) {
    Div({ classes("phone-actions") }) {
        IconButton(
            iconPath = if (header.paused) Icons.PLAY else Icons.PAUSE,
            label = if (header.paused) "Resume reconciliation" else "Pause reconciliation",
            highlighted = header.paused,
            onClick = header.onTogglePause,
        )
        IconButton(
            iconPath = Icons.RESYNC,
            label = "Resync now",
            enabled = !header.busy,
            onClick = header.onResync,
        )
    }
}

@Composable
private fun PassStatus(pass: PassStatusModel, paused: Boolean) {
    Div({
        classes("pass-status")
        pass.tooltip?.let { title(it) }
    }) {
        Span({ classes("status-dot", if (paused) "DEGRADED" else pass.health.name) })
        Span { Text(if (paused) "Paused · observing only" else pass.text) }
    }
}

@Composable
private fun NavFoot(header: HeaderModel) {
    Div({ classes("nav-foot") }) {
        Div { Text(header.domain) }
        Div { Text(header.versionLine) }
        // The pages for this version, served by the orchestrator itself.
        A(href = "/docs/", {
            classes("docs-link")
            target(ATarget.Blank)
            attr("rel", "noopener")
        }) {
            Text("Documentation ↗")
        }
    }
}

/** Blocks the page until a dashboard token is accepted; it can't be dismissed. */
@Composable
private fun SignInDialog(connection: ConnectionModel) {
    val prompt = connection.tokenPrompt ?: return
    Overlay(
        label = "Sign in",
        titleId = "sign-in-title",
        style = OverlayStyle.Modal,
    ) {
        Form(attrs = {
            classes("overlay-panel", "sign-in")
            onSubmit { event ->
                event.preventDefault()
                prompt.onSave()
            }
        }) {
            Img(src = "/emblem.svg", alt = "") { classes("sign-in-mark") }
            H2({ id("sign-in-title") }) { Text("Enter your dashboard token") }
            if (connection.linkExpired) {
                P({
                    classes("banner", "warn")
                    attr("role", "alert")
                }) {
                    Text(connection.message)
                }
            }
            P({ classes("sign-in-text") }) {
                Text("It's the value of ")
                Span({ classes("mono") }) { Text("HOMEGAP_DASHBOARD_TOKEN") }
                Text(" in the orchestrator's environment. Only this orchestrator checks it.")
            }
            Label {
                Text("Token")
                Input(InputType.Password) {
                    value(prompt.value)
                    attr("autocomplete", "current-password")
                    attr("aria-invalid", prompt.rejected.toString())
                    attr("aria-describedby", "sign-in-message")
                    autoFocus()
                    if (prompt.rejected) {
                        classes("invalid")
                    }
                    onInput { prompt.onChange(it.value) }
                }
            }
            P({
                id("sign-in-message")
                classes("field-note")
                if (prompt.rejected) {
                    classes("error")
                    attr("role", "alert")
                }
            }) {
                Text(
                    if (prompt.rejected) {
                        "That token wasn't accepted. Check for extra spaces and try again."
                    } else {
                        "Paste the full token."
                    }
                )
            }
            Button({
                classes("btn", "primary", "wide")
                attr("type", "submit")
            }) {
                Text("Sign in")
            }
            P({ classes("sign-in-foot") }) {
                Text("Lost it? Set a new token in the orchestrator's environment and restart it · ")
                A(href = "/docs/", {
                    target(ATarget.Blank)
                    attr("rel", "noopener")
                }) {
                    Text("Docs ↗")
                }
            }
        }
    }
}

/**
 * Marks the menu `compact` while the top of the page is scrolled out of view, so the phone's icon
 * grid becomes a single row; tapping the header brings the labels back. Only CSS reads the class.
 * It also keeps `--nav-space`, the full menu height the phone layout reserves above the page.
 */
@Composable
private fun ElementScope<HTMLElement>.ShrinkOnScroll() {
    DisposableEffect(Unit) {
        val menu = scopeElement
        val sentinel = document.querySelector(".nav-sentinel")
        val observer = js("new IntersectionObserver(function (entries) { menu.classList.toggle('compact', !entries[0].isIntersecting); })")
        if (sentinel != null) {
            observer.observe(sentinel)
        }
        // The page keeps the full menu's height as its top gap (--nav-space), so shrinking the
        // menu uncovers content instead of moving it.
        val spacer = js("new ResizeObserver(function () { if (!menu.classList.contains('compact')) document.documentElement.style.setProperty('--nav-space', menu.offsetHeight + 'px'); })")
        spacer.observe(menu)
        val expand: (org.w3c.dom.events.Event) -> Unit = { event ->
            val target = event.target as? HTMLElement
            if (target?.closest(".nav-head") != null && target.closest("button") == null) {
                menu.classList.remove("compact")
            }
        }
        menu.addEventListener("click", expand)
        onDispose {
            observer.disconnect()
            spacer.disconnect()
            menu.removeEventListener("click", expand)
        }
    }
}
