package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.addnode.*
import homegap.web.ui.*
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.*

/** The add-node wizard: one panel per step, then the live setup job. */
@Composable
fun AddNodeScreen(model: AddNodeScreenModel) {
    val send = model.onEvent
    Stepper(model)
    val job = model.job
    if (job != null) {
        JobPanel(job, send)
        return
    }
    when (model.step) {
        WizardStep.Server -> ServerStep(model)
        WizardStep.Login -> LoginStep(model)
        WizardStep.Access -> AccessStep(model)
        WizardStep.Firewall -> FirewallStep(model)
        WizardStep.Review -> ReviewStep(model)
    }
    model.notices.forEach { Div({ classes("banner", if (it.warning) "warn" else "info") }) { Text(it.text) } }
    if (model.errors.isNotEmpty()) {
        Div({ classes("banner", "error") }) {
            Ul({ classes("plain-list") }) { model.errors.forEach { Li { Text(it) } } }
        }
    }
    Div({ classes("wizard-actions") }) {
        ActionButton("Start over", tooltip = "Clear everything entered") { send(WizardEvent.Reset) }
        Span({ classes("spacer") })
        if (model.canGoBack) ActionButton("Back") { send(WizardEvent.Back) }
        model.nextLabel?.let { ActionButton(it, ButtonStyle.Primary) { send(WizardEvent.Next) } }
    }
}

@Composable
private fun Stepper(model: AddNodeScreenModel) {
    Ol({ classes("wizard-steps") }) {
        model.steps.forEachIndexed { index, tab ->
            Li({
                classes("wizard-step", tab.state.name.lowercase())
                if (tab.state == StepState.Done && model.job == null) onClick { model.onEvent(WizardEvent.GoTo(tab.step)) }
            }) {
                Span({ classes("wizard-step-num") }) { Text("${index + 1}") }
                Span { Text(tab.step.title) }
            }
        }
    }
}

@Composable
private fun Field(model: AddNodeScreenModel, field: WizardField, type: InputType<String> = InputType.Text) {
    TextField(field.label, model[field], field.placeholder, type) { model.onEvent(WizardEvent.Edit(field, it)) }
}

@Composable
private fun Option(model: AddNodeScreenModel, toggle: WizardToggle, title: String, description: String?, enabled: Boolean = true) {
    CheckOption(title, description, model.has(toggle), enabled) { model.onEvent(WizardEvent.Toggle(toggle, it)) }
}

// ------------------------------------------------------------------------------- steps

@Composable
private fun ServerStep(model: AddNodeScreenModel) {
    Panel("Server", "The VPS to turn into a node. Its id names DNS records and the management host.", flush = false) {
        Div({ classes("form-grid") }) {
            Field(model, WizardField.NodeId)
            Field(model, WizardField.Host)
            Field(model, WizardField.Port)
        }
    }
    Panel("Location", "Used for geo DNS routing and the maps. Search the known data-center cities, or click the map.", flush = false) {
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
    Panel("Pick on the map", "Click where the server is; the nearest known city names it") {
        WorldMap(
            homegap.presentation.map.MapModel(),
            heightPx = 300,
            onPick = { lat, lon -> model.onEvent(WizardEvent.PickOnMap(lat, lon)) },
            pick = model.pick,
        )
    }
}

@Composable
private fun LoginStep(model: AddNodeScreenModel) {
    val send = model.onEvent
    Panel("Initial login", "The credentials your provider gave you. Used only during setup; never stored.", flush = false) {
        Segmented(AuthMethod.entries, model.auth, label = { if (it == AuthMethod.Password) "Password" else "Private key" }) {
            send(WizardEvent.ChooseAuth(it))
        }
        Div({ classes("form-grid") }) {
            Field(model, WizardField.LoginUser)
            if (model.auth == AuthMethod.Password) Field(model, WizardField.Password, InputType.Password)
            else Field(model, WizardField.LoginKeyFile)
        }
        Div({ classes("inline-row") }) {
            ActionButton(if (model.testing) "Testing…" else "Test connection", ButtonStyle.Primary, enabled = !model.testing) {
                send(WizardEvent.TestConnection)
            }
        }
        model.connection?.let { ConnectionResult(it, model.testing, send) }
    }
}

@Composable
private fun ConnectionResult(result: ConnectionResultModel, testing: Boolean, send: (WizardEvent) -> Unit) {
    Div({ classes("result-card", if (result.ok) "ok" else "bad") }) {
        B { Text(result.headline) }
        result.error?.let { ErrorText(it, mono = false) }
        if (result.hostKeyChanged) {
            ActionButton("Forget the old host key and retry", small = true, enabled = !testing) {
                send(WizardEvent.ForgetHostKeyAndTest)
            }
        }
        if (result.facts.isNotEmpty()) Facts(result.facts)
        if (result.ok) Muted("Compare the host key with the one shown in your provider's console before continuing.")
    }
}

@Composable
private fun AccessStep(model: AddNodeScreenModel) {
    val send = model.onEvent
    Panel("Management user", "The account HomeGAP manages the node with.", flush = false) {
        Option(model, WizardToggle.CreateUser, "Create a dedicated user", "With passwordless sudo, and SSH login by key only.")
        if (model.has(WizardToggle.CreateUser)) {
            Div({ classes("form-grid") }) { Field(model, WizardField.NewUser) }
            Option(model, WizardToggle.DockerGroup, "Add it to the docker group", "Lets the user run docker without sudo when you log in yourself.")
        } else {
            Muted("The node will be managed as ${model.targetUser}.")
        }
    }
    Panel("SSH key", "HomeGAP logs in with this key from now on. The private key stays on the orchestrator.", flush = false) {
        Segmented(KeyMode.entries, model.keyMode, label = { it.label }) { send(WizardEvent.ChooseKeyMode(it)) }
        when (model.keyMode) {
            KeyMode.Generate -> Muted("Creates an ed25519 key for this node in the orchestrator's data directory.")
            KeyMode.Import -> TextAreaField(WizardField.ImportKey.label, model[WizardField.ImportKey], WizardField.ImportKey.placeholder, rows = 6, mono = true) {
                send(WizardEvent.Edit(WizardField.ImportKey, it))
            }
            KeyMode.Existing -> Div({ classes("form-grid") }) { Field(model, WizardField.ExistingKey) }
        }
        Div({ classes("inline-row") }) {
            val label = when {
                model.keyBusy -> "Working…"
                model.keyMode == KeyMode.Generate -> if (model.key == null) "Generate key" else "Generate a new key"
                else -> "Use this key"
            }
            ActionButton(label, ButtonStyle.Primary, enabled = !model.keyBusy) { send(WizardEvent.CreateKey) }
        }
        model.key?.let { key ->
            Div({ classes("result-card", "ok") }) {
                B { Text("Key ready: ${key.fingerprint}") }
                Muted(key.path, mono = true)
                Div({ classes("code-block") }) {
                    Code { Text(key.publicKey) }
                    ActionButton("Copy", small = true) { window.navigator.clipboard.writeText(key.publicKey) }
                }
            }
        }
    }
    Panel("SSH hardening", "Applied only after the new key login is proven to work; reverted automatically if access breaks.", flush = false) {
        Option(
            model, WizardToggle.DisableRootLogin, "Disable root login",
            if (model.targetUser == "root") "Needs a management user other than root." else "root can no longer log in over SSH.",
            enabled = model.targetUser != "root",
        )
        Option(model, WizardToggle.DisablePasswordLogin, "Disable password login", "SSH accepts keys only, for every user.")
    }
}

@Composable
private fun FirewallStep(model: AddNodeScreenModel) {
    val send = model.onEvent
    Panel("Firewall", "ufw: deny incoming by default, open only what the node serves.", flush = false) {
        Option(model, WizardToggle.Firewall, "Let HomeGAP manage the firewall", "Rules are kept in sync on every provisioning pass.")
        if (model.has(WizardToggle.Firewall)) {
            Div({ classes("stream-picker") }) {
                Span({ classes("muted") }) { Text("SSH from") }
                Segmented(listOf(false, true), model.has(WizardToggle.RestrictSsh), label = { if (it) "Only these sources" else "Anywhere" }) {
                    send(WizardEvent.Toggle(WizardToggle.RestrictSsh, it))
                }
            }
            if (model.has(WizardToggle.RestrictSsh)) Field(model, WizardField.AllowFrom)
            Div {
                Muted("Rules")
                Ul({ classes("plain-list", "rules") }) { model.firewallRules.forEach { Li { Text(it) } } }
            }
        }
    }
}

@Composable
private fun ReviewStep(model: AddNodeScreenModel) {
    Panel("Review", "Setup runs on the orchestrator; you can leave this page while it works.", flush = false) {
        Facts(model.review)
        Div({ classes("inline-row") }) {
            ActionButton(if (model.starting) "Starting…" else "Set up and add node", ButtonStyle.Primary, enabled = !model.starting) {
                model.onEvent(WizardEvent.Run)
            }
        }
    }
}

// --------------------------------------------------------------------------------- job

@Composable
private fun JobPanel(job: JobModel, send: (WizardEvent) -> Unit) {
    val subtitle = when {
        job.running -> "Working…"
        job.succeeded -> "Done. Provisioning starts on the next reconcile pass."
        else -> "Stopped. Nothing unsafe was left behind; fix the problem and retry."
    }
    Panel("Setup", subtitle, flush = false) {
        Ol({ classes("job-steps") }) {
            job.steps.forEach { step ->
                key(step.title) {
                    Li({ classes("job-step") }) {
                        Div({ classes("inline-row") }) {
                            Span({ classes("job-step-title") }) { Text(step.title) }
                            Pill(step.badge, small = true)
                        }
                        step.detail?.let { Muted(it) }
                    }
                }
            }
        }
        job.error?.let { Div({ classes("banner", "error") }) { Text(it) } }
        Div({ classes("wizard-actions") }) {
            when {
                job.running -> Unit
                job.succeeded -> ActionButton("Go to nodes", ButtonStyle.Primary) { send(WizardEvent.Finish) }
                else -> {
                    ActionButton("Edit settings") { send(WizardEvent.EditAfterFailure) }
                    ActionButton("Retry", ButtonStyle.Primary) { send(WizardEvent.Run) }
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
