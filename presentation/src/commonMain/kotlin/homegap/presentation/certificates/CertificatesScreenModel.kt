package homegap.presentation.certificates

import androidx.compose.runtime.Immutable
import homegap.presentation.core.ScreenModel
import homegap.presentation.routing.Location
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.PageHeadModel

/**
 * The Certificates tab of DNS: the certificate the nodes share, which node has it, what a visitor
 * gets on each name through each node, the orders, and who can issue for the domain.
 */
@Immutable
data class CertificatesScreenModel(
    override val head: PageHeadModel,
    override val inspector: InspectorModel? = null,
    override val empty: EmptyStateModel? = null,
    /** Says that the certificate is from the staging directory, or that the names do not fit. */
    val banner: String? = null,
    val progress: OrderProgressModel? = null,
    val failure: OrderFailureModel? = null,
    val certificate: CertificatePanelModel? = null,
    val delivery: List<DeliveryRowModel> = emptyList(),
    val deliverySummary: String = "",
    val visitor: VisitorMatrixModel? = null,
    val orders: List<OrderRowModel> = emptyList(),
    val policy: PolicyModel? = null,
) : ScreenModel

/** The color of a value: cyan, amber, rose, dim, or violet for work in progress. */
enum class Tone {
    Ok,
    Warn,
    Down,
    Muted,
    Accent,
}

/** A running order, step by step. */
@Immutable
data class OrderProgressModel(
    val caption: String,
    val started: String,
    val steps: List<OrderStepModel>,
)

@Immutable
data class OrderStepModel(
    val label: String,
    val note: String,
    val state: StepState,
)

enum class StepState {
    Done,
    Now,
    Next,
}

/** The last order failed: why, for which names, and when the next try is. */
@Immutable
data class OrderFailureModel(
    val title: String,
    val text: String,
    val names: List<String>,
    val retry: String,
    val failures: String,
    val activity: Location,
)

@Immutable
data class CertificatePanelModel(
    val location: Location,
    val selected: Boolean,
    val lifetime: LifetimeModel,
    val facts: List<FactRow>,
    val names: List<CertNameModel>,
    val pending: PendingChangeModel?,
    val replaceKey: ActionModel,
)

/** Issued, renews and expires on one bar, with today. Positions are percentages of the lifetime. */
@Immutable
data class LifetimeModel(
    val issued: String,
    val renews: String,
    val expires: String,
    val left: String,
    val todayPercent: Double,
    val renewPercent: Double,
    val tone: Tone,
)

@Immutable
data class CertNameModel(
    val name: String,
    val reason: String,
    val location: Location?,
)

@Immutable
data class PendingChangeModel(
    val add: List<CertNameModel>,
    val remove: List<String>,
    val note: String,
)

@Immutable
data class DeliveryRowModel(
    val node: String,
    val place: String,
    val installed: String,
    val installedTone: Tone,
    val installedAt: String,
    val management: String,
    val managementTone: Tone,
    val state: String,
    val stateTone: Tone,
    val selected: Boolean,
    val location: Location,
    val resend: ActionModel?,
)

@Immutable
data class VisitorMatrixModel(
    val nodes: List<String>,
    val groups: List<VisitorGroupModel>,
    val faultsOnly: Boolean,
    val faultRows: Int,
    val probed: String,
    val onFaultsOnly: (Boolean) -> Unit,
    val check: ActionModel,
)

@Immutable
data class VisitorGroupModel(
    val title: String,
    val rows: List<VisitorRowModel>,
)

@Immutable
data class VisitorRowModel(
    val name: String,
    /** "Node" (the nodes end TLS) or "Homelab" (passthrough). */
    val tag: String,
    val passthrough: Boolean,
    val cells: List<VisitorCellModel>,
)

@Immutable
data class VisitorCellModel(
    val text: String,
    val label: String,
    val tone: Tone,
    val selected: Boolean,
    val location: Location,
)

@Immutable
data class OrderRowModel(
    val at: String,
    val what: String,
    val note: String,
    val noteTone: Tone,
    val result: String,
    val tone: Tone,
    val selected: Boolean,
    val location: Location,
)

/** Who can issue certificates for the domain. */
@Immutable
data class PolicyModel(
    val caption: String,
    val records: List<CaaRowModel>,
    val warning: String?,
    val facts: List<FactRow>,
    val note: String,
)

@Immutable
data class CaaRowModel(
    val value: String,
    val state: String,
    val tone: Tone,
)
