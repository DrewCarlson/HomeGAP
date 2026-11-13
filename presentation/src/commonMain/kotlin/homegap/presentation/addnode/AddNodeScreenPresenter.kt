package homegap.presentation.addnode

import androidx.compose.runtime.*
import dev.zacsweers.metro.Inject
import homegap.api.*
import homegap.presentation.common.Badge
import homegap.presentation.core.Presenter
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.AppCoroutineScope
import homegap.presentation.data.ControllerApi
import homegap.presentation.data.ControllerSession
import homegap.presentation.locations.LocationSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * [existingNodeIds] guards against reusing an id. [onFinished] leaves the wizard (to the nodes
 * list), and [onStartOver] replaces it with a new, empty one.
 */
data class AddNodeScreenProps(
    val existingNodeIds: Set<String>,
    val onStartOver: () -> Unit = {},
    val onFinished: () -> Unit,
)

/**
 * The add-node wizard: server, initial login (with a connection test), management access (user,
 * key, SSH hardening), firewall, then a setup job on the orchestrator whose progress is shown live.
 *
 * One instance serves one wizard, from open to close: the app makes a new one, with a new
 * [store], each time the wizard opens, so nothing from an earlier run shows.
 */
@Inject
class AddNodeScreenPresenter(
    private val api: ControllerApi,
    private val session: ControllerSession,
    private val store: AddNodeWizardStore,
    private val actions: ActionRunner,
    @AppCoroutineScope private val appScope: CoroutineScope,
) : Presenter<AddNodeScreenProps, AddNodeScreenModel> {
    @Composable
    override fun model(props: AddNodeScreenProps): AddNodeScreenModel {
        val draft by store.draft.collectAsState()
        DisposableEffect(store) {
            onDispose { store.close() }
        }
        var testing by remember { mutableStateOf(false) }
        var keyBusy by remember { mutableStateOf(false) }
        var starting by remember { mutableStateOf(false) }
        val latestProps by rememberUpdatedState(props)
        // The locations catalog, for the location search and naming a point picked on the map.
        val catalog by produceState(emptyList<LocationDto>()) {
            while (true) {
                runCatchingCancellable { api.locations().locations }
                    .onSuccess {
                        value = it
                        return@produceState
                    }
                delay(3.seconds)
            }
        }
        val latestCatalog by rememberUpdatedState(catalog)
        var locationQuery by remember { mutableStateOf("") }

        // A running job's steps come from the event stream as they change.
        val jobId = draft.job?.id
        val streamedJob = session.jobs.collectAsState().value[jobId]
        LaunchedEffect(streamedJob) {
            val job = streamedJob ?: return@LaunchedEffect
            store.update { d ->
                val done = job.state == "succeeded"
                // The password is not needed once setup has finished.
                d.copy(job = job, values = if (done) d.values - WizardField.Password else d.values)
            }
        }

        val onEvent = remember {
            { event: WizardEvent ->
                when (event) {
                    is WizardEvent.Edit -> store.update { edit(it, event.field, event.value) }
                    is WizardEvent.Toggle -> store.update { toggle(it, event.toggle, event.on) }
                    is WizardEvent.ChooseAuth -> store.update { it.copy(auth = event.method) }
                    is WizardEvent.ChooseKeyMode -> store.update { it.copy(keyMode = event.mode) }
                    is WizardEvent.GoTo -> store.update { d ->
                        if (event.step.ordinal <= d.step.ordinal) d.copy(step = event.step, showErrors = false) else d
                    }

                    WizardEvent.Back -> store.update { d ->
                        d.copy(step = WizardStep.entries[(d.step.ordinal - 1).coerceAtLeast(0)], showErrors = false)
                    }

                    WizardEvent.Next -> store.update { d ->
                        if (AddNodeValidation.errors(d, d.step, latestProps.existingNodeIds).isNotEmpty()) {
                            d.copy(showErrors = true)
                        } else {
                            d.copy(
                                step = WizardStep.entries[(d.step.ordinal + 1).coerceAtMost(WizardStep.entries.lastIndex)],
                                showErrors = false
                            )
                        }
                    }

                    WizardEvent.TestConnection,
                    WizardEvent.ForgetHostKeyAndTest,
                    WizardEvent.ConfirmHostKey -> if (!testing) {
                        testing = true
                        when (event) {
                            WizardEvent.ConfirmHostKey -> store.update { confirmHostKey(it) }
                            // A rebuilt server has a new key, which needs its own confirmation.
                            WizardEvent.ForgetHostKeyAndTest -> store.update { it.copy(confirmedHostKey = null) }
                            else -> Unit
                        }
                        appScope.launch {
                            try {
                                testConnection(resetHostKey = event == WizardEvent.ForgetHostKeyAndTest)
                            } finally {
                                testing = false
                            }
                        }
                    }

                    WizardEvent.CreateKey -> if (!keyBusy) {
                        keyBusy = true
                        appScope.launch {
                            try {
                                createKey()
                            } finally {
                                keyBusy = false
                            }
                        }
                    }

                    WizardEvent.Run -> if (!starting) {
                        starting = true
                        appScope.launch {
                            try {
                                startJob()
                            } finally {
                                starting = false
                            }
                        }
                    }

                    WizardEvent.RetryFromFailed -> if (!starting) {
                        starting = true
                        appScope.launch {
                            try {
                                startJob(retryOf = store.draft.value.job?.id)
                            } finally {
                                starting = false
                            }
                        }
                    }

                    WizardEvent.EditAfterFailure -> store.update { it.copy(job = null) }
                    WizardEvent.Finish -> latestProps.onFinished()
                    WizardEvent.Reset -> latestProps.onStartOver()
                    is WizardEvent.SearchLocation -> locationQuery = event.query
                    is WizardEvent.PickLocation -> {
                        val l = latestCatalog.firstOrNull { it.id == event.id }
                        if (l != null) {
                            locationQuery = ""
                            store.update { place(it, l.latitude, l.longitude, l.region, l.name) }
                        }
                    }

                    is WizardEvent.PickOnMap -> {
                        val lat = LocationSearch.round2(event.latitude)
                        val lon = LocationSearch.round2(event.longitude)
                        val near = LocationSearch.nearest(latestCatalog, lat, lon)
                        store.update {
                            place(
                                it,
                                lat,
                                lon,
                                near?.region ?: LocationSearch.regionFor(lat, lon),
                                near?.name.orEmpty()
                            )
                        }
                    }
                }
            }
        }

        val step = draft.step
        val errors = if (draft.showErrors) AddNodeValidation.errors(draft, step, props.existingNodeIds) else emptyList()
        val target = AddNodeValidation.targetUser(draft)
        return AddNodeScreenModel(
            step = step,
            steps = WizardStep.entries.map {
                StepTabModel(
                    it,
                    if (it == step) StepState.Current else if (it.ordinal < step.ordinal) StepState.Done else StepState.Upcoming
                )
            },
            values = draft.values,
            toggles = draft.toggles,
            auth = draft.auth,
            keyMode = draft.keyMode,
            connection = draft.test?.takeIf { draft.testedLogin == AddNodeValidation.login(draft) }?.toModel(),
            testing = testing,
            key = draft.key?.takeIf { draft.keyFor == draft[WizardField.NodeId].trim() },
            keyBusy = keyBusy,
            targetUser = target,
            lockableLogin = AddNodeValidation.lockableLogin(draft),
            firewallRules = firewallRules(draft),
            notices = notices(draft),
            errors = errors,
            review = review(draft),
            job = draft.job?.toModel(),
            starting = starting,
            canGoBack = step != WizardStep.Server && draft.job == null,
            nextLabel = if (step == WizardStep.Review) null else "Continue",
            onEvent = onEvent,
            locationQuery = locationQuery,
            locationMatches = LocationSearch.search(catalog, locationQuery).map { l ->
                LocationOptionModel(
                    l.id,
                    LocationSearch.label(l),
                    listOf(l.region, l.providers.take(4).joinToString(", ")).filter { it.isNotBlank() }
                        .joinToString(" · ")
                )
            },
            pick = draft[WizardField.Latitude].trim().toDoubleOrNull()
                ?.let { lat -> draft[WizardField.Longitude].trim().toDoubleOrNull()?.let { lat to it } },
        )
    }

    /** Fills the location fields from a catalog entry or a point picked on the map. */
    private fun place(d: AddNodeDraft, lat: Double, lon: Double, region: String, name: String) = d.copy(
        values = d.values + mapOf(
            WizardField.Latitude to lat.toString(),
            WizardField.Longitude to lon.toString(),
            WizardField.Region to region,
            WizardField.LocationName to name,
        ),
    )

    // ------------------------------------------------------------------------ actions

    private suspend fun testConnection(resetHostKey: Boolean) {
        val draft = store.draft.value
        val login = AddNodeValidation.login(draft)
        if (login.host.isBlank()) {
            actions.notify("Enter the server address first.", isError = true)
            return
        }
        runCatchingCancellable { api.testConnection(AddNodeValidation.login(draft, resetHostKey)) }
            .onSuccess { result -> store.update { it.copy(test = result, testedLogin = login, showErrors = false) } }
            .onFailure { actions.notify("Connection test failed: ${it.message}", isError = true) }
    }

    private suspend fun createKey() {
        val draft = store.draft.value
        val nodeId = draft[WizardField.NodeId].trim()
        val request = SshKeyRequestDto(
            nodeId = nodeId,
            mode = draft.keyMode.wire,
            privateKey = draft[WizardField.ImportKey].takeIf { draft.keyMode == KeyMode.Import },
            path = draft[WizardField.ExistingKey].trim().takeIf { draft.keyMode == KeyMode.Existing },
        )
        runCatchingCancellable { api.createKey(request) }
            .onSuccess { key ->
                // A pasted private key is not kept in the browser once the orchestrator has it.
                store.update { it.copy(key = key, keyFor = nodeId, values = it.values - WizardField.ImportKey) }
            }
            .onFailure { actions.notify("SSH key: ${it.message}", isError = true) }
    }

    private suspend fun startJob(retryOf: String? = null) {
        val draft = store.draft.value
        val key = draft.key ?: return
        runCatchingCancellable { api.startSetup(request(draft, key).copy(retryOf = retryOf)) }
            .onSuccess { job -> store.update { it.copy(job = job) } }
            .onFailure { actions.notify("Setup could not start: ${it.message}", isError = true) }
    }

    // ---------------------------------------------------------------------- state

    /** Takes the fingerprint of the current test as confirmed, when that test asks for it. */
    private fun confirmHostKey(draft: AddNodeDraft): AddNodeDraft {
        val test = draft.test?.takeIf { it.hostKeyUnconfirmed && draft.testedLogin == AddNodeValidation.login(draft) }
        return draft.copy(confirmedHostKey = test?.hostKeyFingerprint ?: draft.confirmedHostKey)
    }

    private fun edit(draft: AddNodeDraft, field: WizardField, value: String): AddNodeDraft {
        val sameServer = (field != WizardField.Host && field != WizardField.Port) || draft[field].trim() == value.trim()
        val next = draft.copy(
            values = draft.values + (field to value),
            confirmedHostKey = draft.confirmedHostKey.takeIf { sameServer },
        )
        // A different node id needs its own key.
        return if (field == WizardField.NodeId && draft.keyFor != null && draft.keyFor != value.trim()) next.copy(
            key = null,
            keyFor = null
        ) else next
    }

    private fun toggle(draft: AddNodeDraft, toggle: WizardToggle, on: Boolean): AddNodeDraft {
        val toggles = if (on) draft.toggles + toggle else draft.toggles - toggle
        var next = draft.copy(toggles = toggles)
        // Restricting SSH starts from the address the server sees the orchestrator at.
        if (toggle == WizardToggle.RestrictSsh && on && draft[WizardField.AllowFrom].isBlank()) {
            draft.test?.clientIp?.let { next = next.copy(values = next.values + (WizardField.AllowFrom to it)) }
        }
        return next
    }

    private fun request(draft: AddNodeDraft, key: SshKeyDto): NodeSetupRequestDto {
        val restrict = draft.has(WizardToggle.Firewall) && draft.has(WizardToggle.RestrictSsh)
        val port = AddNodeValidation.port(draft) ?: 22
        return NodeSetupRequestDto(
            node = NodeConfigDto(
                id = draft[WizardField.NodeId].trim(),
                region = draft[WizardField.Region].trim().uppercase(),
                provider = draft[WizardField.Provider].trim(),
                host = draft[WizardField.Host].trim(),
                sshUser = AddNodeValidation.targetUser(draft),
                sshPort = port,
                sshKeyFile = key.path,
                latitude = draft[WizardField.Latitude].trim().toDoubleOrNull(),
                longitude = draft[WizardField.Longitude].trim().toDoubleOrNull(),
                locationName = draft[WizardField.LocationName].trim().ifBlank { null },
            ),
            login = AddNodeValidation.login(draft),
            keyFile = key.path,
            createUser = AddNodeValidation.targetUser(draft).takeIf { draft.has(WizardToggle.CreateUser) },
            dockerGroup = draft.has(WizardToggle.DockerGroup),
            disableRootLogin = draft.has(WizardToggle.DisableRootLogin),
            disablePasswordLogin = draft.has(WizardToggle.DisablePasswordLogin),
            disableLoginUser = draft.has(WizardToggle.DisableLoginUser) && AddNodeValidation.lockableLogin(draft) != null,
            configureFirewall = draft.has(WizardToggle.Firewall),
            sshAllowFrom = if (restrict) AddNodeValidation.sources(draft) else emptyList(),
        )
    }

    // ---------------------------------------------------------------------- views

    private fun ConnectionTestDto.toModel() = ConnectionResultModel(
        ok = ok,
        headline = when {
            hostKeyUnconfirmed -> "Check the server's fingerprint"
            ok -> "Connected to $os $osVersion ($arch)"
            os != null -> "Connected, but this server cannot be used"
            else -> "Could not connect"
        },
        facts = buildList {
            hostKeyFingerprint?.let { add(ReviewLine("Host key", it)) }
            if (os != null) {
                add(ReviewLine("System", "$os $osVersion ($arch)"))
                add(ReviewLine("Root access", PRIVILEGE_LABELS[privilege] ?: privilege))
                add(ReviewLine("Docker", if (dockerInstalled) "installed" else "will be installed"))
            }
            clientIp?.let { add(ReviewLine("Orchestrator seen from", it)) }
        },
        error = error,
        hostKeyChanged = hostKeyChanged,
        hostKeyUnconfirmed = hostKeyUnconfirmed,
        note = HOST_KEY_NOTE.takeIf { hostKeyUnconfirmed },
    )

    private fun firewallRules(draft: AddNodeDraft): List<String> {
        val port = AddNodeValidation.port(draft) ?: 22
        val ssh = if (draft.has(WizardToggle.RestrictSsh)) {
            AddNodeValidation.sources(draft).map { "SSH ($port/tcp) from $it" }
                .ifEmpty { listOf("SSH ($port/tcp) from: none yet") }
        } else {
            listOf("SSH ($port/tcp) from anywhere")
        }
        return ssh + listOf(
            "HTTP 80/tcp, HTTPS 443/tcp and 443/udp",
            "WireGuard 51820/udp and 21820/udp",
            "Ports of raw TCP/UDP services, as they are labeled"
        )
    }

    private fun notices(draft: AddNodeDraft): List<NoticeModel> = buildList {
        when (draft.step) {
            WizardStep.Server -> if (draft[WizardField.Region].isBlank() && draft[WizardField.Latitude].isBlank()) {
                add(
                    NoticeModel(
                        false,
                        "Geolocation routing needs a region code (NA, SA, EU, AF, ME, AS, OC) or coordinates."
                    )
                )
            }

            WizardStep.Access -> {
                if (draft.has(WizardToggle.DisableRootLogin) && draft[WizardField.LoginUser].trim() != "root") {
                    add(
                        NoticeModel(
                            false,
                            "If root login is off already, or the server keeps it on, setup notes it and goes on.",
                        )
                    )
                }
                if (draft.has(WizardToggle.DisablePasswordLogin)) {
                    add(
                        NoticeModel(
                            false,
                            "After setup, SSH accepts only keys. Keep your provider's web console as the way back in."
                        )
                    )
                }
                if (!draft.has(WizardToggle.CreateUser) && AddNodeValidation.targetUser(draft) != "root") {
                    add(
                        NoticeModel(
                            false,
                            "${AddNodeValidation.targetUser(draft)} will be given passwordless sudo, which HomeGAP needs."
                        )
                    )
                }
            }

            WizardStep.Firewall -> if (draft.has(WizardToggle.Firewall) && draft.has(WizardToggle.RestrictSsh)) {
                add(
                    NoticeModel(
                        true,
                        "If the orchestrator's public IP changes to an address not listed here, it loses SSH access to this node until the rule is fixed from the provider's console. Prefer a stable range, or allow SSH from anywhere (key-only logins)."
                    )
                )
                val seen = draft.test?.clientIp
                if (seen != null && !AddNodeValidation.covers(AddNodeValidation.sources(draft), seen)) {
                    add(
                        NoticeModel(
                            true,
                            "The server sees the orchestrator at $seen, which is not covered: setup would fail its access check and turn the firewall off again."
                        )
                    )
                }
            }

            else -> Unit
        }
    }

    private fun review(draft: AddNodeDraft): List<ReviewLine> = buildList {
        val target = AddNodeValidation.targetUser(draft)
        add(
            ReviewLine(
                "Node",
                "${draft[WizardField.NodeId].trim()} at ${draft[WizardField.Host].trim()}:${AddNodeValidation.port(draft) ?: 22}"
            )
        )
        val place = listOfNotNull(
            draft[WizardField.LocationName].trim().ifBlank { null },
            draft[WizardField.Region].trim().uppercase().ifBlank { null },
            draft[WizardField.Latitude].trim().ifBlank { null }?.let { "$it, ${draft[WizardField.Longitude].trim()}" },
        )
        if (place.isNotEmpty()) add(ReviewLine("Location", place.joinToString(" · ")))
        add(
            ReviewLine(
                "Initial login",
                "${draft[WizardField.LoginUser].trim()} with ${if (draft.auth == AuthMethod.Password) "password" else "key"}"
            )
        )
        add(
            ReviewLine(
                "Managed as",
                if (draft.has(WizardToggle.CreateUser)) "$target (new: sudo${if (draft.has(WizardToggle.DockerGroup)) ", docker" else ""}, passwordless sudo)" else target
            )
        )
        draft.key?.let { add(ReviewLine("SSH key", "${it.fingerprint} (${it.path})")) }
        val ssh = listOfNotNull(
            "no root login".takeIf { draft.has(WizardToggle.DisableRootLogin) },
            "no password login".takeIf { draft.has(WizardToggle.DisablePasswordLogin) },
            AddNodeValidation.lockableLogin(draft)?.takeIf { draft.has(WizardToggle.DisableLoginUser) }?.let { "no SSH login for $it" },
        )
        add(ReviewLine("SSH", ssh.joinToString(", ").ifEmpty { "unchanged" }))
        add(
            ReviewLine(
                "Firewall",
                when {
                    !draft.has(WizardToggle.Firewall) -> "not managed by HomeGAP"
                    draft.has(WizardToggle.RestrictSsh) -> {
                        val sources = AddNodeValidation.sources(draft).joinToString()
                        "on; SSH only from $sources"
                    }

                    else -> "on; SSH from anywhere"
                },
            )
        )
    }

    private fun SetupJobDto.toModel() = JobModel(
        running = state == "running",
        succeeded = state == "succeeded",
        // Both times are from the clock of the orchestrator, so the difference needs no clock here.
        retryHoldSeconds = retryAt?.let { until -> finishedAt?.let { ((until - it) / 1000).toInt() } }?.takeIf { it > 0 },
        steps = steps.map {
            JobStepModel(
                it.title,
                STEP_BADGES[it.status] ?: Badge(Health.UNKNOWN, it.status),
                it.detail
            )
        },
        error = error,
    )

    private companion object {
        const val HOST_KEY_NOTE = "HomeGAP has not connected to this server before. Compare this fingerprint with " +
            "the one your provider shows for the server. HomeGAP sends the password only after you confirm."
        val PRIVILEGE_LABELS = mapOf(
            "root" to "logged in as root",
            "nopasswd" to "sudo without a password",
            "password" to "sudo with the login password",
            "none" to "none",
        )
        val STEP_BADGES = mapOf(
            "pending" to Badge(Health.UNKNOWN, "waiting"),
            "running" to Badge(Health.DEGRADED, "running…"),
            "ok" to Badge(Health.OK, "done"),
            "failed" to Badge(Health.DOWN, "failed"),
            "skipped" to Badge(Health.UNKNOWN, "skipped"),
        )
    }
}
