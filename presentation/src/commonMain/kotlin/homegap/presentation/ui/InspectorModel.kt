package homegap.presentation.ui

import androidx.compose.runtime.Immutable
import homegap.api.Health

/**
 * The selected item, shown beside the page (desktops) or over it (a drawer or a sheet). [kind]
 * names what it is ("Node"), [actions] sit in a fixed bar at the bottom.
 */
@Immutable
data class InspectorModel(
    val kind: String,
    val title: String,
    val status: String,
    val health: Health,
    val sections: List<InspectorSection>,
    val actions: List<ActionModel> = emptyList(),
)

/** One part of the Inspector. */
sealed interface InspectorSection {
    /** Graphs, each with its current value printed. */
    @Immutable
    data class Charts(val charts: List<ChartModel>) : InspectorSection

    /** Label and value rows. */
    @Immutable
    data class Facts(
        val title: String?,
        val rows: List<FactRow>,
    ) : InspectorSection

    /** Rows with a health mark: checks, or a service through each edge. */
    @Immutable
    data class Marks(
        val title: String,
        val rows: List<MarkRow>,
    ) : InspectorSection

    /** Preformatted text: labels, an error, a log excerpt. */
    @Immutable
    data class Code(
        val title: String,
        val text: String,
    ) : InspectorSection

    /** Options to switch on and off (roles); changes wait for the Save action. */
    @Immutable
    data class Choices(
        val title: String,
        val options: List<ChoiceOption>,
        val onToggle: (id: String) -> Unit,
    ) : InspectorSection

    /** A note, in the color of [severity]. */
    @Immutable
    data class Note(
        val text: String,
        val severity: Severity = Severity.Info,
    ) : InspectorSection
}

@Immutable
data class FactRow(
    val label: String,
    val value: String,
    val mono: Boolean = false,
    /** A copy button beside the value: only for public values, such as a fingerprint. */
    val copy: Boolean = false,
)

/** [name] with a health mark, a [detail] and a right-aligned [value]. */
@Immutable
data class MarkRow(
    val health: Health,
    val name: String,
    val detail: String = "",
    val value: String = "",
    /** A short label beside the name ("held by the homelab"). */
    val tag: String? = null,
    /** Where the row explains itself further (a TLS check on Certificates). */
    val location: homegap.presentation.routing.Location? = null,
)

@Immutable
data class ChoiceOption(
    val id: String,
    val label: String,
    val selected: Boolean,
)
