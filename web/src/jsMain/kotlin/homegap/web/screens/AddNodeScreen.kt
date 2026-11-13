package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import homegap.presentation.addnode.*
import homegap.web.ui.*
import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.*

/**
 * The Add node wizard, over the Nodes page: a dialog with the step list on desktops, a drawer with
 * a stepper on tablets, and a full-screen sheet with fixed Back and Continue on phones. The draft
 * is kept on this device, so closing it loses nothing; a running setup carries on.
 */
@Composable
fun AddNodeWizard(
    model: AddNodeScreenModel,
    viewport: Viewport,
    onClose: () -> Unit,
) {
    Overlay(
        label = "Add a node",
        titleId = "add-node-title",
        style = when (viewport) {
            Viewport.Phone -> OverlayStyle.FullScreen
            Viewport.Tablet -> OverlayStyle.Drawer
            else -> OverlayStyle.Modal
        },
        onDismiss = onClose,
    ) {
        Div({ classes("overlay-panel", "wizard") }) {
            Aside({ classes("wizard-aside") }) {
                Div({ classes("caption") }) { Text("Nodes ›") }
                H2({ id("add-node-title") }) { Text("Add a node") }
                P({ classes("muted") }) { Text("Sets up a new server as an edge node. Nothing changes until you run it.") }
                Stepper(model)
                P({ classes("wizard-draft", "muted") }) { Text("Draft kept on this device.") }
            }
            Div({ classes("wizard-main") }) {
                Div({ classes("wizard-head") }) {
                    Div {
                        val index = model.steps.indexOfFirst { it.step == model.step } + 1
                        Div({ classes("caption") }) {
                            Text(if (model.job != null) "Setup" else "Step $index of ${model.steps.size}")
                        }
                        H2 { Text(if (model.job != null) "Setting up ${model[WizardField.NodeId]}" else model.step.title) }
                    }
                    IconButton(
                        iconPath = Icons.CLOSE,
                        label = "Close; the draft is kept",
                        onClick = onClose,
                    )
                }
                Div({ classes("wizard-progress") }) {
                    model.steps.forEach { tab ->
                        Span({ classes("wizard-progress-step", tab.state.name.lowercase()) })
                    }
                }
                Div({ classes("wizard-body") }) {
                    ScrollHint()
                    AddNodeContent(model)
                }
            }
        }
    }
}

/** One panel per step, then the live setup job. */
@Composable
private fun AddNodeContent(model: AddNodeScreenModel) {
    val send = model.onEvent
    val job = model.job
    if (job != null) {
        JobPanel(job, send)
        return
    }
    if (model.errors.isNotEmpty()) {
        Div({
            classes("banner", "error")
            attr("role", "alert")
        }) {
            B { Text("Fix ${model.errors.size} ${if (model.errors.size == 1) "problem" else "problems"} to continue") }
            Ul({ classes("plain-list") }) {
                model.errors.forEach { Li { Text(it) } }
            }
        }
    }
    when (model.step) {
        WizardStep.Server -> ServerStep(model)
        WizardStep.Login -> LoginStep(model)
        WizardStep.Access -> AccessStep(model)
        WizardStep.Firewall -> FirewallStep(model)
        WizardStep.Review -> ReviewStep(model)
    }
    model.notices.forEach { (warning, text) ->
        Div({
            classes("banner", if (warning) "warn" else "info")
        }) {
            Text(text)
        }
    }
    Div({ classes("wizard-actions") }) {
        ActionButton(
            text = "Start over",
            tooltip = "Clear everything entered"
        ) {
            send(WizardEvent.Reset)
        }
        Span({ classes("spacer") })
        if (model.canGoBack) {
            ActionButton("Back") {
                send(WizardEvent.Back)
            }
        }
        model.nextLabel?.let { nextLabel ->
            ActionButton(
                text = nextLabel,
                style = ButtonStyle.Primary
            ) {
                send(WizardEvent.Next)
            }
        }
    }
}

@Composable
private fun Stepper(model: AddNodeScreenModel) {
    Ol({ classes("wizard-steps") }) {
        model.steps.forEachIndexed { index, tab ->
            Li({
                classes("wizard-step", tab.state.name.lowercase())
                if (tab.state == StepState.Done && model.job == null) {
                    onClick { model.onEvent(WizardEvent.GoTo(tab.step)) }
                }
            }) {
                Span({ classes("wizard-step-num") }) {
                    Text("${index + 1}")
                }
                Span { Text(tab.step.title) }
            }
        }
    }
}

@Composable
private fun Field(
    model: AddNodeScreenModel,
    field: WizardField,
    type: InputType<String> = InputType.Text
) {
    TextField(
        label = field.label,
        value = model[field],
        placeholder = field.placeholder,
        type = type
    ) {
        model.onEvent(WizardEvent.Edit(field, it))
    }
}

@Composable
private fun Option(
    model: AddNodeScreenModel,
    toggle: WizardToggle,
    title: String,
    description: String?,
    enabled: Boolean = true
) {
    CheckOption(
        title = title,
        description = description,
        checked = model.has(toggle),
        enabled = enabled
    ) {
        model.onEvent(WizardEvent.Toggle(toggle, it))
    }
}

// ------------------------------------------------------------------------------- steps

@Composable
private fun ServerStep(model: AddNodeScreenModel) {
    Panel(
        title = "Server",
        subtitle = "The VPS to turn into a node. Its id names DNS records and the management host.",
        flush = false
    ) {
        Div({ classes("form-grid") }) {
            Field(model, WizardField.NodeId)
            Field(model, WizardField.Host)
            Field(model, WizardField.Port)
        }
    }
    Panel(
        title = "Location",
        subtitle = "Used for geo DNS routing and the maps. Search the known data-center cities, or click the map.",
        flush = false
    ) {
        LocationSearchBox(
            query = model.locationQuery,
            matches = model.locationMatches,
            onQuery = { model.onEvent(WizardEvent.SearchLocation(it)) },
            onPick = { model.onEvent(WizardEvent.PickLocation(it)) },
        )
        Div({ classes("form-grid") }) {
            Field(model, WizardField.LocationName)
            Field(model, WizardField.Region)
            Field(model, WizardField.Latitude)
            Field(model, WizardField.Longitude)
            Field(model, WizardField.Provider)
        }
    }
    Panel(
        title = "Pick on the map",
        subtitle = "Click where the server is; the nearest known city names it"
    ) {
        WorldMap(
            homegap.presentation.map.MapModel(),
            heightPx = 300,
            onPick = { lat, lon ->
                model.onEvent(WizardEvent.PickOnMap(lat, lon))
            },
            pick = model.pick,
        )
    }
}

@Composable
private fun LoginStep(model: AddNodeScreenModel) {
    val send = model.onEvent
    Panel(
        title = "Initial login",
        subtitle = "The credentials your provider gave you. Used only during setup; never stored.",
        flush = false
    ) {
        Segmented(
            AuthMethod.entries,
            model.auth,
            label = { if (it == AuthMethod.Password) "Password" else "Private key" }) {
            send(WizardEvent.ChooseAuth(it))
        }
        Div({ classes("form-grid") }) {
            Field(model, WizardField.LoginUser)
            if (model.auth == AuthMethod.Password) {
                Field(model, WizardField.Password, InputType.Password)
            } else {
                Field(model, WizardField.LoginKeyFile)
            }
        }
        Div({ classes("inline-row") }) {
            ActionButton(
                text = if (model.testing) "Testing…" else "Test connection",
                style = ButtonStyle.Primary,
                enabled = !model.testing
            ) {
                send(WizardEvent.TestConnection)
            }
        }
        model.connection?.let { ConnectionResult(it, model.testing, send) }
    }
}

@Composable
private fun ConnectionResult(
    result: ConnectionResultModel,
    testing: Boolean,
    send: (WizardEvent) -> Unit
) {
    Div({
        classes("result-card")
        if (result.ok) {
            classes("ok")
        } else if (!result.hostKeyUnconfirmed) {
            classes("bad")
        }
    }) {
        B { Text(result.headline) }
        result.note?.let { Muted(it) }
        result.error?.let { ErrorText(it, mono = false) }
        if (result.hostKeyChanged) {
            ActionButton(
                text = "Forget the old host key and retry",
                small = true,
                enabled = !testing
            ) {
                send(WizardEvent.ForgetHostKeyAndTest)
            }
        }
        if (result.facts.isNotEmpty()) {
            Facts(result.facts)
        }
        if (result.hostKeyUnconfirmed) {
            ActionButton(
                text = "The fingerprint matches",
                style = ButtonStyle.Primary,
                small = true,
                enabled = !testing
            ) {
                send(WizardEvent.ConfirmHostKey)
            }
        }
        if (result.ok) {
            Muted("Compare the host key with the one shown in your provider's console before continuing.")
        }
    }
}

@Composable
private fun AccessStep(model: AddNodeScreenModel) {
    val send = model.onEvent
    Panel(
        title = "Management user",
        subtitle = "The account HomeGAP manages the node with.",
        flush = false
    ) {
        Option(
            model = model,
            toggle = WizardToggle.CreateUser,
            title = "Create a dedicated user",
            description = "With passwordless sudo, and SSH login by key only."
        )
        if (model.has(WizardToggle.CreateUser)) {
            Div({ classes("form-grid") }) {
                Field(model, WizardField.NewUser)
            }
            Option(
                model,
                toggle = WizardToggle.DockerGroup,
                title = "Add it to the docker group",
                description = "Lets the user run docker without sudo when you log in yourself."
            )
        } else {
            Muted("The node will be managed as ${model.targetUser}.")
        }
    }
    Panel(
        title = "SSH key",
        subtitle = "HomeGAP logs in with this key from now on. The private key stays on the orchestrator.",
        flush = false
    ) {
        Segmented(
            options = KeyMode.entries,
            selected = model.keyMode,
            label = { it.label }
        ) {
            send(WizardEvent.ChooseKeyMode(it))
        }
        when (model.keyMode) {
            KeyMode.Generate -> {
                Muted("Creates an ed25519 key for this node in the orchestrator's data directory.")
            }

            KeyMode.Import -> TextAreaField(
                label = WizardField.ImportKey.label,
                value = model[WizardField.ImportKey],
                placeholder = WizardField.ImportKey.placeholder,
                rows = 6,
                mono = true
            ) {
                send(WizardEvent.Edit(WizardField.ImportKey, it))
            }

            KeyMode.Existing -> {
                Div({ classes("form-grid") }) {
                    Field(model, WizardField.ExistingKey)
                }
            }
        }
        Div({ classes("inline-row") }) {
            val label = when {
                model.keyBusy -> "Working…"
                model.keyMode == KeyMode.Generate -> {
                    if (model.key == null) "Generate key" else "Generate a new key"
                }

                else -> "Use this key"
            }
            ActionButton(
                text = label,
                style = ButtonStyle.Primary,
                enabled = !model.keyBusy
            ) {
                send(WizardEvent.CreateKey)
            }
        }
        model.key?.let { key ->
            Div({ classes("result-card", "ok") }) {
                B { Text("Key ready: ${key.fingerprint}") }
                Muted(key.path, mono = true)
                Div({ classes("code-block") }) {
                    Code { Text(key.publicKey) }
                    ActionButton("Copy", small = true) {
                        window.navigator.clipboard.writeText(key.publicKey)
                    }
                }
            }
        }
    }
    Panel(
        title = "SSH hardening",
        subtitle = "Applied only after the new key login is proven to work; reverted automatically if access breaks.",
        flush = false
    ) {
        Option(
            model,
            toggle = WizardToggle.DisableRootLogin,
            title = "Disable root login",
            description = when (model.targetUser) {
                "root" -> "Needs a management user other than root."
                else -> "root can no longer log in over SSH."
            },
            enabled = model.targetUser != "root",
        )
        Option(
            model,
            toggle = WizardToggle.DisablePasswordLogin,
            title = "Disable password login",
            description = "SSH accepts keys only, for every user."
        )
        model.lockableLogin?.let { login ->
            Option(
                model,
                toggle = WizardToggle.DisableLoginUser,
                title = "Disable SSH login for $login",
                description = "$login was only for setup. ${model.targetUser} runs the node from now on; the provider's console still works.",
            )
        }
    }
}

@Composable
private fun FirewallStep(model: AddNodeScreenModel) {
    val send = model.onEvent
    Panel(
        title = "Firewall",
        subtitle = "ufw: deny incoming by default, open only what the node serves.",
        flush = false
    ) {
        Option(
            model,
            WizardToggle.Firewall,
            "Let HomeGAP manage the firewall",
            "Rules are kept in sync on every provisioning pass."
        )
        if (model.has(WizardToggle.Firewall)) {
            Div({ classes("stream-picker") }) {
                Span({ classes("muted") }) { Text("SSH from") }
                Segmented(
                    options = remember { listOf(false, true) },
                    selected = model.has(WizardToggle.RestrictSsh),
                    label = { if (it) "Only these sources" else "Anywhere" }
                ) {
                    send(WizardEvent.Toggle(WizardToggle.RestrictSsh, it))
                }
            }
            if (model.has(WizardToggle.RestrictSsh)) {
                Field(model, WizardField.AllowFrom)
            }
            Div {
                Muted("Rules")
                Ul({ classes("plain-list", "rules") }) {
                    model.firewallRules.forEach { rule ->
                        Li { Text(rule) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewStep(model: AddNodeScreenModel) {
    Panel(
        title = "Review",
        subtitle = "Setup runs on the orchestrator; you can leave this page while it works.",
        flush = false
    ) {
        Facts(model.review)
        Div({ classes("inline-row") }) {
            ActionButton(
                text = if (model.starting) "Starting…" else "Set up and add node",
                style = ButtonStyle.Primary,
                enabled = !model.starting
            ) {
                model.onEvent(WizardEvent.Run)
            }
        }
    }
}

// --------------------------------------------------------------------------------- job

@Composable
private fun JobPanel(job: JobModel, send: (WizardEvent) -> Unit) {
    val subtitle = remember(job) {
        when {
            job.running -> "Working…"
            job.succeeded -> "Done. Provisioning starts on the next reconcile pass. The node serves visitors once the certificate order with its management name is complete, usually within a few minutes."
            else -> "Stopped. Nothing unsafe was left behind; fix the problem and retry."
        }
    }
    Panel(
        title = "Setup",
        subtitle = subtitle,
        flush = false
    ) {
        if (job.succeeded) {
            P {
                A(href = homegap.web.routing.HashRouter.href(homegap.presentation.routing.Route.Certificates), { classes("link") }) {
                    Text("Follow the certificate order on Certificates →")
                }
            }
        }
        Ol({ classes("job-steps") }) {
            job.steps.forEach { step ->
                key(step.title) {
                    Li({ classes("job-step") }) {
                        Div({ classes("inline-row") }) {
                            Span({ classes("job-step-title") }) {
                                Text(step.title)
                            }
                            Pill(step.badge, small = true)
                        }
                        step.detail?.let { Muted(it) }
                    }
                }
            }
        }
        job.error?.let {
            Div({ classes("banner", "error") }) {
                Text(it)
            }
        }
        Div({ classes("wizard-actions") }) {
            when {
                job.running -> Unit
                job.succeeded -> {
                    ActionButton(
                        text = "Go to nodes",
                        style = ButtonStyle.Primary
                    ) {
                        send(WizardEvent.Finish)
                    }
                }

                else -> {
                    ActionButton("Edit settings") {
                        send(WizardEvent.EditAfterFailure)
                    }
                    RetryButton(job.retryHoldSeconds) {
                        send(WizardEvent.RetryFromFailed)
                    }
                }
            }
        }
    }
}

@Composable
private fun Facts(lines: List<ReviewLine>) {
    Div({ classes("facts") }) {
        lines.forEach { line ->
            Div({ classes("fact-label") }) { Text(line.label) }
            Div({ classes("fact-value", "mono") }) { Text(line.value) }
        }
    }
}

/** The retry button of a failed run. While [holdSeconds] count down, a retry fails the same way. */
@Composable
private fun RetryButton(holdSeconds: Int?, onClick: () -> Unit) {
    var left by remember(holdSeconds) { mutableStateOf(holdSeconds ?: 0) }
    LaunchedEffect(holdSeconds) {
        while (left > 0) {
            delay(1000)
            left--
        }
    }
    ActionButton(
        text = if (left > 0) "Retry in ${left / 60}:${(left % 60).toString().padStart(2, '0')}" else "Retry",
        style = ButtonStyle.Primary,
        enabled = left == 0,
        tooltip = if (left > 0) "The block of new connections ends by itself. A retry before that fails the same way." else null,
        onClick = onClick,
    )
}
