package homegap.presentation.dns

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.presentation.core.ScreenModel
import homegap.presentation.routing.Location
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.ProblemsFilterModel

/** The DNS records HomeGAP manages, grouped into record sets (one name and type, several members). */
@Immutable
data class DnsScreenModel(
    override val head: PageHeadModel,
    val sets: List<RecordSetModel>,
    val query: String,
    val onQuery: (String) -> Unit,
    val filter: ProblemsFilterModel,
    /** Geo steering: which edges visitors are sent to now, from the largest geo-routed record set. */
    val steering: List<SteeringModel>,
    /** The domains that services can use. Empty when the config has no extra domain. */
    val domains: List<DomainRowModel> = emptyList(),
    /** The row that the Inspector shows, so the list can mark it. */
    val selected: Location? = null,
    override val inspector: InspectorModel? = null,
    override val empty: EmptyStateModel? = null,
) : ScreenModel

/** One domain that services can use: its DNS zone, its front door and the state of the zone. */
@Immutable
data class DomainRowModel(
    val domain: String,
    val zone: String,
    val frontDoor: String,
    val health: Health,
    val state: String,
)

/** One name and type, with a member per edge (geo-routed) or a single value. */
@Immutable
data class RecordSetModel(
    val name: String,
    val type: String,
    val routing: String,
    val health: Health,
    val summary: String,
    val members: List<RecordMemberModel>,
    val location: Location,
) {
    /** A set with a problem shows its members in the list. A healthy set shows them in the Inspector. */
    val expanded: Boolean get() = health != Health.OK
}

@Immutable
data class RecordMemberModel(
    val node: String,
    val value: String,
    val monitor: String,
    val health: Health,
    val state: String,
)

/** One edge in geo steering: answering for its region, or withdrawn (visitors go to the next nearest). */
@Immutable
data class SteeringModel(
    val edge: String,
    val serving: Boolean,
    /** How long it has been withdrawn ("for 12m"). */
    val since: String? = null,
)
