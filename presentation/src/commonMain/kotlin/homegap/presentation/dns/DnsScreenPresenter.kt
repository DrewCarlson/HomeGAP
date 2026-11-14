package homegap.presentation.dns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.DnsRecordStatusDto
import homegap.api.Health
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.ControllerApi
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.InspectorSection
import homegap.presentation.ui.location
import homegap.presentation.ui.MarkRow
import homegap.presentation.ui.on
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.rememberProblemsFilter
import homegap.presentation.ui.Severity
import homegap.presentation.ui.statusLineOf

data class DnsScreenProps(
    val status: StatusSnapshot,
    val now: Long = 0,
    /** The selected record name. */
    val selection: String? = null,
)

@SingleIn(AppScope::class)
@Inject
class DnsScreenPresenter(
    private val api: ControllerApi,
    private val actions: ActionRunner,
) : Presenter<DnsScreenProps, DnsScreenModel> {
    @Composable
    override fun model(props: DnsScreenProps): DnsScreenModel {
        val status = props.status
        var query by remember { mutableStateOf("") }
        val all = status.dns
            .groupBy { it.name to it.type }
            .map { (key, records) -> recordSet(key.first, key.second, records) }
            .sortedWith(compareBy<RecordSetModel> { if (it.health == Health.OK) 1 else 0 }.thenBy { it.name })
        val inSync = all.count { it.health == Health.OK }
        val filter = rememberProblemsFilter(
            total = all.size,
            problems = all.size - inSync,
        )
        val needle = query.trim().lowercase()
        val sets = all
            .filter { !filter.problemsOnly || it.health != Health.OK }
            .filter { set -> needle.isEmpty() || needle in set.name.lowercase() || set.members.any { needle in it.value.lowercase() } }
        // The CAA records of a name belong to Certificates, so the Inspector of the name leaves them out.
        val selected = props.selection
            ?.let { name -> status.dns.filter { it.name == name && it.type != "CAA" } }
            ?.takeIf { it.isNotEmpty() }
        return DnsScreenModel(
            head = PageHeadModel(
                caption = "DNS",
                headline = "$inSync of ${all.size} record sets healthy.",
                statusLine = statusLineOf(status.attention.on(Route.Dns)),
                tabs = dnsTabs(Route.Dns, status),
            ),
            sets = sets,
            query = query,
            onQuery = { query = it },
            filter = filter,
            steering = status.dns
                .filter { it.geo?.startsWith("geo") == true && it.nodeId != null }
                .groupBy { it.name to it.type }
                .maxByOrNull { it.value.size }
                ?.value
                ?.map { record ->
                    val withdrawn = record.monitorStatus == "offline"
                    SteeringModel(
                        edge = record.nodeId.orEmpty(),
                        serving = !withdrawn,
                        since = status.attention
                            .firstOrNull { it.id == "dns:${record.name}:${record.type}:${record.nodeId}:offline" }
                            ?.takeIf { withdrawn }
                            ?.let { homegap.presentation.format.ago(it.since, props.now).removeSuffix(" ago") },
                    )
                }
                .orEmpty(),
            domains = status.domains
                .takeIf { it.size > 1 }
                .orEmpty()
                .map { domain ->
                    val error = domain.zoneError
                    DomainRowModel(
                        domain = domain.domain,
                        zone = domain.zone,
                        frontDoor = domain.frontDoor ?: "none",
                        health = if (error == null) Health.OK else Health.DEGRADED,
                        state = if (error == null) "healthy" else "warning · $error",
                    )
                },
            selected = selected?.let { Location(Route.Dns, props.selection) },
            inspector = selected?.let {
                inspector(
                    records = it,
                    now = props.now,
                )
            },
            empty = if (status.dns.isEmpty()) {
                EmptyStateModel(
                    title = "No DNS records yet",
                    text = "HomeGAP writes records once a node is provisioned and a service is published. " +
                        "It needs a DNS provider in the config to do it.",
                )
            } else {
                null
            },
        )
    }

    private fun recordSet(name: String, type: String, records: List<DnsRecordStatusDto>): RecordSetModel {
        val members = records.map { record ->
            RecordMemberModel(
                node = record.nodeId ?: "—",
                value = record.value,
                monitor = if (record.monitor == "none") "no monitor" else "${record.monitor} ${record.monitorStatus}",
                health = record.health(),
                state = when {
                    record.monitorStatus == "offline" -> "warning · withdrawn"
                    !record.inSync -> "warning · ${record.diff ?: "differs"}"
                    else -> "healthy"
                },
            )
        }
        val withdrawn = records.count { it.monitorStatus == "offline" }
        val outOfSync = records.count { !it.inSync }
        return RecordSetModel(
            name = name,
            type = type,
            routing = records.firstNotNullOfOrNull { it.geo }?.let { "geo" } ?: "plain",
            health = when {
                outOfSync > 0 || withdrawn > 0 -> Health.DEGRADED
                else -> Health.OK
            },
            summary = buildList {
                if (type == "CAA") add("owned by certificate management · read-only")
                add("${records.size} ${if (records.size == 1) "member" else "members"}")
                if (withdrawn > 0) add("$withdrawn withdrawn")
                if (outOfSync > 0) add("$outOfSync out of sync")
            }.joinToString(" · "),
            members = members,
            location = if (type == "CAA") Location(Route.Certificates) else Location(Route.Dns, name),
        )
    }

    private fun inspector(records: List<DnsRecordStatusDto>, now: Long): InspectorModel {
        val first = records.first()
        val problems = records.filter { it.health() != Health.OK }
        return InspectorModel(
            kind = "DNS record set",
            title = first.name,
            status = if (problems.isEmpty()) "healthy" else "${problems.size} of ${records.size} members need attention",
            health = if (problems.isEmpty()) Health.OK else Health.DEGRADED,
            sections = buildList {
                problems.firstNotNullOfOrNull { it.diff }?.let {
                    add(InspectorSection.Note(text = it, severity = Severity.Warn))
                }
                add(
                    InspectorSection.Marks(
                        title = "Members",
                        rows = records.map { record ->
                            MarkRow(
                                health = record.health(),
                                name = "${record.nodeId ?: "—"} · ${record.value}",
                                detail = listOfNotNull(
                                    record.geo,
                                    if (record.monitor == "none") null else "monitor ${record.monitorStatus}",
                                    if (record.inSync) null else (record.diff ?: "differs"),
                                ).joinToString(" · "),
                            )
                        },
                    )
                )
                add(
                    InspectorSection.Facts(
                        title = "Record set",
                        rows = listOfNotNull(
                            FactRow("Type", first.type),
                            FactRow("Routing", if (records.any { it.geo != null }) "geo: nearest healthy node" else "plain"),
                            FactRow("Monitor", first.monitor),
                            records.mapNotNull { it.changedAt }.maxOrNull()?.let { FactRow("Last change", homegap.presentation.format.ago(it, now)) },
                        ),
                    )
                )
            },
            actions = listOf(
                ActionModel(
                    label = "Check again",
                    tooltip = "Run a pass now; it reads every record from the DNS provider",
                    onClick = { actions.run("Check DNS") { api.resync() } },
                ),
            ),
        )
    }
}

private fun DnsRecordStatusDto.health(): Health = when {
    monitorStatus == "offline" -> Health.DEGRADED
    !inSync -> Health.DEGRADED
    else -> Health.OK
}

/** The tabs of DNS: its records and its certificates, each marked when something on it needs attention. */
fun dnsTabs(current: Route, status: StatusSnapshot): List<homegap.presentation.ui.PageTabModel> = Route.Dns.tabs.map { route ->
    val items = status.attention.filter { it.location.route == route }
    homegap.presentation.ui.PageTabModel(
        label = if (route == Route.Dns) "Records" else route.title,
        location = Location(route),
        selected = route == current,
        attention = when {
            items.any { it.severity == Health.DOWN } -> Health.DOWN
            items.isNotEmpty() -> Health.DEGRADED
            else -> null
        },
    )
}
