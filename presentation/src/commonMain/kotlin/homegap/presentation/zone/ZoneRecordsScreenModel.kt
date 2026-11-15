package homegap.presentation.zone

import androidx.compose.runtime.Immutable
import homegap.presentation.core.ScreenModel
import homegap.presentation.routing.Location
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.PageHeadModel

/** Every record of one DNS zone. The records of the user can change, the others are locked. */
@Immutable
data class ZoneRecordsScreenModel(
    override val head: PageHeadModel,
    val zones: List<ZoneOptionModel>,
    val query: String,
    val onQuery: (String) -> Unit,
    /** How many records are of the user and how many are locked ("5 yours · 10 locked"). */
    val counts: String,
    val rows: List<ZoneRecordRowModel>,
    /** Opens the form for a new record. Null for a user who can only look. */
    val add: ActionModel? = null,
    /** True when the zone has only locked records. */
    val noOwn: Boolean = false,
    /** A note for the top of the screen. */
    val notice: String? = null,
    /** The row that the Inspector shows, so the list can mark it. */
    val selected: Location? = null,
    /** The form for a new record or for an edit. It opens in the place of the Inspector. */
    val form: RecordFormModel? = null,
    override val inspector: InspectorModel? = null,
    override val empty: EmptyStateModel? = null,
) : ScreenModel

/** One zone in the zone switcher. */
@Immutable
data class ZoneOptionModel(
    val name: String,
    val label: String,
    val selected: Boolean,
    val location: Location,
)

@Immutable
data class ZoneRecordRowModel(
    val id: String,
    /** The name relative to the zone, then the rest of the full name ("mail" and ".example.net"). */
    val name: String,
    val suffix: String,
    val type: String,
    val value: String,
    val ttl: String,
    val kind: RecordKind,
    /** What a locked record is for ("front door · na1"). Empty for a record of the user. */
    val purpose: String,
    val location: Location,
)

/** The form for one record. Add and Edit use the same form, and Edit cannot change the type. */
@Immutable
data class RecordFormModel(
    val title: String,
    val zone: String,
    val types: List<String>,
    val typeLocked: Boolean,
    val input: RecordInput,
    /** The full name of the record, as the user types it. */
    val fullName: String,
    /** A quiet note under the name, for a name under a managed wildcard. */
    val nameNote: String?,
    val valueLabel: String,
    val valuePlaceholder: String,
    /** The problem of each field, after the first try to save. */
    val errors: Map<RecordField, String>,
    val ttlChoices: List<TtlChoiceModel>,
    /** The refusal of the server or of the DNS provider, in its words. */
    val refusal: String?,
    val saving: Boolean,
    val saveLabel: String,
    val onChange: (RecordInput) -> Unit,
    val onSave: () -> Unit,
    /** Where Cancel goes: the record for an edit, the list for a new record. */
    val close: Location,
)

@Immutable
data class TtlChoiceModel(
    val seconds: Int,
    val label: String,
)
