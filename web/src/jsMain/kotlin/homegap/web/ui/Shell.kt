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
import homegap.presentation.speedtest.SpeedTestScreenModel
import homegap.presentation.tunnels.TunnelsScreenModel
import homegap.web.routing.HashRouter
import homegap.web.screens.*
import homegap.web.terminal.TerminalPanel
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoFocus
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * The page shell: the navigation (a side menu on desktops, an icon rail on tablets, a header with
 * a row of sections that scrolls sideways on phones; CSS picks the form), the page title and the
 * active screen.
 */
@Composable
fun AppShell(model: AppModel) {
    val viewport = rememberViewport()
    val inspector = model.screen?.inspector
    SearchShortcut(model.search)
    // Phones: once the page scrolls past this marker, the header row folds away (CSS).
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
                    model.header?.let { header ->
                        PhoneActions(header)
                    }
                }
            }
            SearchField(model.search)
            // Phones: after the header row folds away, Search stands left of the row of sections.
            Div({ classes("nav-row") }) {
                Div({ classes("nav-search") }) {
                    IconButton(
                        iconPath = Icons.SEARCH,
                        label = "Search everything",
                        onClick = model.search.onOpen,
                    )
                }
                NavList(
                    items = model.nav,
                    onNavigate = model.onNavigate,
                )
            }
            model.header?.let { header ->
                ReconcileBox(header)
                NavFoot(header)
            }
        }
        // The page, with the terminal panel docked under it.
        Div({ classes("workspace") }) {
            Main({ classes("page") }) {
                // Tablets and desktops scroll the page here, not the window; a new section starts at the top.
                ScrollReset(model.route)
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
            model.terminal?.let { terminal ->
                // A drawer or a sheet is a modal dialog: it would cover the terminal that its action opened.
                val covered = inspector != null && (viewport == Viewport.Tablet || viewport == Viewport.Phone)
                LaunchedEffect(terminal.focusRequests) {
                    if (terminal.focusRequests > 0 && covered) {
                        model.onOpen(Location(model.route))
                    }
                }
                TerminalPanel(
                    model = terminal,
                    covered = covered,
                )
            }
        }
        if (inspector != null) {
            InspectorHost(
                inspector = inspector,
                toasts = model.toasts,
                viewport = viewport,
                onClose = { model.onOpen(Location(model.route)) },
                onOpen = model.onOpen,
            )
        }
    }
    // After the Inspector, so it stays above it (overlays stack in page order).
    model.nodeLocation?.let { nodeLocation ->
        NodeLocationDialog(
            model = nodeLocation,
            viewport = viewport,
        )
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
    // With the Inspector open, its own stack shows them above its action bar instead.
    if (inspector == null) {
        ToastStack(model.toasts)
    }
    model.connection?.let { connection ->
        if (connection.signInRequired) {
            SignInDialog(connection)
        }
        connection.lost?.let { lost ->
            LostConnectionDialog(
                lost = lost,
                tokenPrompt = connection.tokenPrompt,
            )
        }
    }
}

@Composable
private fun Content(model: AppModel, viewport: Viewport) {
    if (model.isLoading) {
        Div({ classes("loading") }) { Text("Loading…") }
        return
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
        is homegap.presentation.certificates.CertificatesScreenModel -> CertificatesScreen(
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
        is LocationsScreenModel, is AddNodeScreenModel -> Unit
    }
}

/** The page title for screens that don't have their own head yet. */
@Composable
private fun RouteHead(route: Route) {
    Header({ classes("page-head") }) {
        Div({ classes("page-head-text") }) {
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
        // Phones scroll the sections sideways: the selected one stays in view.
        val selected = items.firstOrNull { it.selected }?.route
        DisposableEffect(selected) {
            val active = scopeElement.querySelector(".nav-link.active")
            active?.asDynamic()?.scrollIntoView(js("({ block: 'nearest', inline: 'center' })"))
            onDispose {}
        }
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
            text = "Resume all",
            tooltip = "Apply changes everywhere again",
            onClick = header.onTogglePause,
        )
    } else {
        ActionButton(
            text = "Pause all",
            tooltip = "Stop all changes and only observe",
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
        Button({
            classes("sign-out")
            onClick { header.onSignOut() }
        }) {
            Text("Sign out")
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
            P({ classes("sign-in-text") }) {
                Text("Paste the value of ")
                Span({ classes("mono") }) { Text("HOMEGAP_DASHBOARD_TOKEN") }
                Text(" from the orchestrator's environment.")
            }
            Label {
                Span({ classes("sign-in-label") }) {
                    Text("Token")
                    Button({
                        classes("link-button")
                        attr("type", "button")
                        attr("aria-pressed", prompt.revealed.toString())
                        onClick { prompt.onToggleReveal() }
                    }) {
                        Text(if (prompt.revealed) "Hide" else "Show")
                    }
                }
                Input(if (prompt.revealed) InputType.Text else InputType.Password) {
                    attr("autocapitalize", "off")
                    attr("autocorrect", "off")
                    spellCheck(false)
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
                        ""
                    }
                )
            }
            Button({
                classes("btn", "primary", "wide")
                attr("type", "submit")
                if (prompt.checking) {
                    disabled()
                }
            }) {
                Text(if (prompt.checking) "Checking…" else "Sign in")
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
 * Marks the menu `compact` while the top of the page is scrolled out of view, so the phone's header
 * row folds away and only the row of sections stays. Only CSS reads the class. It also keeps
 * `--nav-space`, the full menu height the phone layout reserves above the page.
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
        onDispose {
            observer.disconnect()
            spacer.disconnect()
        }
    }
}
