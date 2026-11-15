package homegap.presentation.zone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.Health
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.Confirmations
import homegap.presentation.dns.dnsTabs
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.ActionStyle
import homegap.presentation.ui.ConfirmModel
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.InspectorSection
import homegap.presentation.ui.PageHeadModel
import kotlinx.coroutines.launch

data class ZoneRecordsScreenProps(
    val status: StatusSnapshot,
    /** The zone, and after it the record or the form: see [ZoneSelection]. */
    val selection: String? = null,
    val onOpen: (Location) -> Unit = {},
)

/**
 * What the URL selects on the screen: `zone`, `zone/r/id` (a record in the Inspector), `zone/new`
 * (the form for a new record) or `zone/edit/id` (the form for a record).
 */
data class ZoneSelection(
    val zone: String? = null,
    val record: String? = null,
    val form: Boolean = false,
) {
    val path: String?
        get() = when {
            zone == null -> null
            form && record != null -> "$zone/edit/$record"
            form -> "$zone/new"
            record != null -> "$zone/r/$record"
            else -> zone
        }

    companion object {
        fun parse(selection: String?): ZoneSelection {
            val zone = selection?.substringBefore('/') ?: return ZoneSelection()
            val rest = selection.substringAfter('/', missingDelimiterValue = "")
            return when {
                rest == "new" -> ZoneSelection(zone = zone, form = true)
                rest.startsWith("edit/") -> ZoneSelection(zone = zone, record = rest.removePrefix("edit/"), form = true)
                rest.startsWith("r/") -> ZoneSelection(zone = zone, record = rest.removePrefix("r/"))
                else -> ZoneSelection(zone = zone)
            }
        }
    }
}

@SingleIn(AppScope::class)
@Inject
class ZoneRecordsScreenPresenter(
    private val api: ZoneRecordsApi,
    private val actions: ActionRunner,
    private val confirmations: Confirmations,
) : Presenter<ZoneRecordsScreenProps, ZoneRecordsScreenModel> {
    @Composable
    override fun model(props: ZoneRecordsScreenProps): ZoneRecordsScreenModel {
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf("") }
        var changes by remember { mutableIntStateOf(0) }
        // The zones load again after a change of the user and after HomeGAP changes its own records.
        val zones by produceState<List<DnsZone>?>(null, props.status.dns, props.status.domains, changes) {
            runCatchingCancellable { api.zones() }
                .onSuccess { value = it }
                .onFailure {
                    actions.notify("The zone records did not load: ${it.message}", isError = true)
                    value = value.orEmpty()
                }
        }
        val selection = ZoneSelection.parse(props.selection)
        val zone = zones?.firstOrNull { it.name == selection.zone } ?: zones?.firstOrNull()
        val zoneName = zone?.name.orEmpty()
        val records = zone?.records.orEmpty().sortedWith(compareBy<ZoneRecord>({ it.name != ROOT }, { it.name }, { it.type }, { it.value }))
        val own = records.count { it.kind == RecordKind.Own }
        val needle = query.trim().lowercase()
        val shown = records.filter { record ->
            needle.isEmpty() || needle in fullName(record.name, zoneName) || needle == record.type.lowercase() || needle in record.line.lowercase()
        }
        val record = records.firstOrNull { it.id == selection.record }
        val canEdit = api.canEdit
        fun at(record: String? = null, form: Boolean = false) = Location(Route.ZoneRecords, ZoneSelection(zoneName, record, form).path)

        // The form: its text, and what the last try to save gave.
        val editing = record?.takeIf { selection.form && it.kind == RecordKind.Own }
        val formOpen = canEdit && zone != null && selection.form && (selection.record == null || editing != null)
        var input by remember(zoneName, formOpen, editing?.id) { mutableStateOf(editing?.toInput() ?: RecordInput()) }
        var tried by remember(zoneName, formOpen, editing?.id) { mutableStateOf(false) }
        var saving by remember(zoneName, formOpen, editing?.id) { mutableStateOf(false) }
        var refusal by remember(zoneName, formOpen, editing?.id) { mutableStateOf<String?>(null) }
        val problems = recordProblems(
            input = input,
            zone = zoneName,
            records = records,
            editing = editing?.id,
        )

        return ZoneRecordsScreenModel(
            head = PageHeadModel(
                caption = "DNS",
                headline = when {
                    zone == null -> "Zone records."
                    needle.isNotEmpty() -> "${shown.size} of ${records.size} records match."
                    else -> "${records.size} ${if (records.size == 1) "record" else "records"} in $zoneName."
                },
                tabs = dnsTabs(Route.ZoneRecords, props.status),
            ),
            zones = zones.orEmpty().map { option ->
                ZoneOptionModel(
                    name = option.name,
                    label = "${option.name} · ${option.records.size} records",
                    selected = option.name == zoneName,
                    location = Location(Route.ZoneRecords, option.name),
                )
            },
            query = query,
            onQuery = { query = it },
            counts = "$own yours · ${records.size - own} locked",
            rows = shown.map { it.toRow(zoneName) },
            add = ActionModel(
                label = "Add record",
                style = ActionStyle.Primary,
                location = at(form = true),
            ).takeIf { canEdit && zone != null },
            noOwn = zone != null && own == 0 && needle.isEmpty(),
            notice = api.notice,
            selected = record?.let { at(record = it.id) },
            form = if (formOpen) {
                RecordFormModel(
                    title = if (editing != null) "Edit record" else "Add record",
                    zone = zoneName,
                    types = RECORD_TYPES,
                    typeLocked = editing != null,
                    input = input,
                    fullName = fullName(input.relativeName, zoneName),
                    nameNote = "This name no longer goes to your nodes.".takeIf { underManagedWildcard(input.relativeName, records) },
                    valueLabel = when (input.type) {
                        "CNAME" -> "Target"
                        "MX" -> "Mail server"
                        else -> "Value"
                    },
                    valuePlaceholder = when (input.type) {
                        "A" -> "198.51.100.25"
                        "AAAA" -> "2001:db8::25"
                        "CNAME" -> "host.example.com"
                        "MX" -> "mail.example.net"
                        "CAA" -> "letsencrypt.org"
                        else -> "v=spf1 mx ~all"
                    },
                    errors = if (tried) problems else emptyMap(),
                    ttlChoices = (TTL_CHOICES + input.ttlSeconds).distinct().sorted().map { TtlChoiceModel(it, ttlLabel(it)) },
                    refusal = refusal,
                    saving = saving,
                    saveLabel = when {
                        saving -> "Saving…"
                        editing != null -> "Save"
                        else -> "Add record"
                    },
                    onChange = { if (!saving) input = it },
                    onSave = save@{
                        tried = true
                        if (problems.isNotEmpty() || saving) return@save
                        val draft = input.toDraft()
                        saving = true
                        refusal = null
                        scope.launch {
                            runCatchingCancellable {
                                if (editing != null) api.update(zoneName, editing.id, draft) else api.add(zoneName, draft)
                            }.onSuccess {
                                actions.notify("${if (editing != null) "Saved" else "Added"} ${fullName(draft.name, zoneName)} ${draft.type}.")
                                changes++
                                props.onOpen(at(record = editing?.id))
                            }.onFailure {
                                refusal = it.message ?: "The change failed."
                            }
                            saving = false
                        }
                    },
                    close = at(record = editing?.id),
                )
            } else {
                null
            },
            inspector = record?.takeIf { !formOpen }?.let { selected ->
                inspector(
                    record = selected,
                    zone = zoneName,
                    canEdit = canEdit,
                    edit = at(record = selected.id, form = true),
                    onDelete = {
                        confirmations.ask(
                            model = ConfirmModel(
                                title = "Delete this record?",
                                text = "${fullName(selected.name, zoneName)} ${selected.type} ${selected.line}",
                                confirmLabel = "Delete record",
                                consequences = listOf("The change applies at once."),
                                danger = true,
                            ),
                        ) {
                            scope.launch {
                                runCatchingCancellable { api.delete(zoneName, selected.id) }
                                    .onSuccess {
                                        actions.notify("Deleted ${fullName(selected.name, zoneName)} ${selected.type}.")
                                        changes++
                                        props.onOpen(at())
                                    }
                                    .onFailure { actions.notify("Delete failed: ${it.message}", isError = true) }
                            }
                        }
                    },
                )
            },
            empty = if (zones != null && zone == null) {
                EmptyStateModel(
                    title = "No DNS zone yet",
                    text = "HomeGAP needs a DNS provider in the config to show its zones.",
                )
            } else {
                null
            },
        )
    }

    private fun ZoneRecord.toRow(zone: String) = ZoneRecordRowModel(
        id = id,
        name = name,
        suffix = if (name == ROOT) " $zone" else ".$zone",
        type = type,
        value = line,
        ttl = ttlLabel(ttlSeconds),
        kind = kind,
        purpose = when (kind) {
            RecordKind.Own -> ""
            RecordKind.Challenge -> "certificate challenge"
            RecordKind.Managed -> listOfNotNull(purpose, nodeId).joinToString(" · ").ifEmpty { "managed" }
        },
        location = Location(Route.ZoneRecords, ZoneSelection(zone, id).path),
    )

    private fun ZoneRecord.toInput() = RecordInput(
        type = type,
        name = name,
        value = value,
        priority = priority?.toString().orEmpty(),
        flags = (flags ?: 0).toString(),
        tag = tag ?: CAA_TAGS.first(),
        ttlSeconds = ttlSeconds ?: DEFAULT_TTL,
    )

    private fun inspector(record: ZoneRecord, zone: String, canEdit: Boolean, edit: Location, onDelete: () -> Unit): InspectorModel {
        val locked = record.kind != RecordKind.Own
        return InspectorModel(
            kind = "DNS record",
            title = fullName(record.name, zone),
            status = record.type + " · " + when (record.kind) {
                RecordKind.Own -> "yours"
                RecordKind.Managed -> "managed by HomeGAP"
                RecordKind.Challenge -> "certificate challenge"
            },
            health = Health.UNKNOWN,
            sections = buildList {
                when {
                    record.kind == RecordKind.Managed -> add(InspectorSection.Note("HomeGAP manages this record."))
                    record.kind == RecordKind.Challenge -> add(InspectorSection.Note("A certificate order uses this record."))
                    !canEdit -> add(InspectorSection.Note("Your role is read-only."))
                }
                add(
                    InspectorSection.Facts(
                        title = null,
                        rows = listOfNotNull(
                            FactRow(
                                label = "Value",
                                value = record.line,
                                mono = true,
                                copy = true,
                            ),
                            record.purpose?.let { FactRow("For", it) },
                            record.nodeId?.let { FactRow("Node", it, mono = true) },
                            FactRow("Name", record.name, mono = true),
                            FactRow("Zone", zone, mono = true),
                            FactRow("Type", record.type, mono = true),
                            FactRow("TTL", ttlLabel(record.ttlSeconds)),
                        ),
                    )
                )
            },
            actions = when {
                record.kind == RecordKind.Managed -> listOf(ActionModel("Open the record set", location = Location(Route.Dns, fullName(record.name, zone))))
                record.kind == RecordKind.Challenge -> listOf(ActionModel("Open the certificate", location = Location(Route.Certificates)))
                !canEdit -> emptyList()
                else -> listOf(
                    ActionModel(
                        label = "Edit",
                        style = ActionStyle.Primary,
                        location = edit,
                    ),
                    ActionModel(
                        label = "Delete",
                        style = ActionStyle.Danger,
                        onClick = onDelete,
                    ),
                )
            },
        )
    }
}
