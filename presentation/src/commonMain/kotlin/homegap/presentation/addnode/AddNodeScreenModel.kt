package homegap.presentation.addnode

import androidx.compose.runtime.Immutable
import homegap.api.SshKeyDto
import homegap.presentation.common.Badge
import homegap.presentation.core.ScreenModel

@Immutable
data class AddNodeScreenModel(
    val step: WizardStep,
    val steps: List<StepTabModel>,
    val values: Map<WizardField, String>,
    val toggles: Set<WizardToggle>,
    val auth: AuthMethod,
    val keyMode: KeyMode,
    val connection: ConnectionResultModel?,
    val testing: Boolean,
    val key: SshKeyDto?,
    val keyBusy: Boolean,
    /** The management user the node will be run as. */
    val targetUser: String,
    /** The initial login user whose SSH login setup can end; null when it is root or the management user. */
    val lockableLogin: String? = null,
    /** `ufw` rules the firewall step will apply. */
    val firewallRules: List<String>,
    val notices: List<NoticeModel>,
    /** Problems blocking the current step (shown once the user tried to continue). */
    val errors: List<String>,
    /** The first problem of each field that has one, shown under the field. */
    val fieldErrors: Map<WizardField, String> = emptyMap(),
    val review: List<ReviewLine>,
    val job: JobModel?,
    val starting: Boolean,
    val canGoBack: Boolean,
    val nextLabel: String?,
    val onEvent: (WizardEvent) -> Unit,
    /** The location search: what's typed and the catalog entries it matches. */
    val locationQuery: String = "",
    val locationMatches: List<LocationOptionModel> = emptyList(),
    /** The chosen coordinates (latitude, longitude), shown on the map. */
    val pick: Pair<Double, Double>? = null,
) : ScreenModel {
    operator fun get(field: WizardField): String = values[field].orEmpty()

    fun has(toggle: WizardToggle): Boolean = toggle in toggles
}

@Immutable
data class StepTabModel(val step: WizardStep, val state: StepState)

enum class StepState { Done, Current, Upcoming }

/** Outcome of "Test connection". */
@Immutable
data class ConnectionResultModel(
    val ok: Boolean,
    val headline: String,
    val facts: List<ReviewLine>,
    val error: String?,
    val hostKeyChanged: Boolean,
    /** The operator must compare the host key in [facts] before HomeGAP logs in. */
    val hostKeyUnconfirmed: Boolean = false,
    /** What to do about the result, when it is not an error. */
    val note: String? = null,
)

@Immutable
data class NoticeModel(val warning: Boolean, val text: String)

@Immutable
data class ReviewLine(val label: String, val value: String)

/** A running or finished setup job. */
@Immutable
data class JobModel(
    val running: Boolean,
    val succeeded: Boolean,
    val steps: List<JobStepModel>,
    val error: String?,
    /** After a failure by a block of new connections: the seconds that a retry must wait. */
    val retryHoldSeconds: Int? = null,
)

@Immutable
data class JobStepModel(val title: String, val badge: Badge, val detail: String?)

/** Everything the wizard view can ask for. */
sealed interface WizardEvent {
    data class Edit(val field: WizardField, val value: String) : WizardEvent
    data class Toggle(val toggle: WizardToggle, val on: Boolean) : WizardEvent
    data class ChooseAuth(val method: AuthMethod) : WizardEvent
    data class ChooseKeyMode(val mode: KeyMode) : WizardEvent
    data class GoTo(val step: WizardStep) : WizardEvent
    data object TestConnection : WizardEvent
    data object ForgetHostKeyAndTest : WizardEvent
    /** The fingerprint of the last test matches the provider's: tests again, now with the login. */
    data object ConfirmHostKey : WizardEvent
    data object CreateKey : WizardEvent
    data object Next : WizardEvent
    data object Back : WizardEvent
    data object Run : WizardEvent
    data object EditAfterFailure : WizardEvent
    /** Runs the failed job again, keeping what its earlier steps left on the server. */
    data object RetryFromFailed : WizardEvent
    data object Finish : WizardEvent
    data object Reset : WizardEvent
    data class SearchLocation(val query: String) : WizardEvent
    data class PickLocation(val id: String) : WizardEvent
    data class PickOnMap(val latitude: Double, val longitude: Double) : WizardEvent
}

/** A location search result. */
@Immutable
data class LocationOptionModel(val id: String, val label: String, val detail: String)
