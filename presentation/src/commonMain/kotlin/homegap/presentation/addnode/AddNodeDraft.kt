package homegap.presentation.addnode

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.ConnectionTestDto
import homegap.api.SetupJobDto
import homegap.api.SshKeyDto
import homegap.api.SshLoginDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class WizardStep(val title: String) {
    Server("Server"),
    Login("Initial login"),
    Access("Access"),
    Firewall("Firewall"),
    Review("Review & run"),
}

enum class AuthMethod { Password, Key }

enum class KeyMode(val wire: String, val label: String) {
    Generate("generate", "Generate a new key"),
    Import("import", "Paste a private key"),
    Existing("existing", "Use a key file on the orchestrator"),
}

/** Text inputs of the wizard. */
enum class WizardField(val label: String, val placeholder: String = "") {
    NodeId("Node id", "eu1"),
    Host("Public IP or host name", "203.0.113.30"),
    Port("SSH port", "22"),
    Region("Region code", "EU"),
    Provider("Provider", "optional"),
    Latitude("Latitude", "50.1"),
    Longitude("Longitude", "8.7"),
    LocationName("Place name", "Frankfurt"),
    LoginUser("User", "root"),
    Password("Password"),
    LoginKeyFile("Private key on the orchestrator", "/secrets/ssh/provider"),
    NewUser("User name", "homegap"),
    ImportKey("Private key", "-----BEGIN OPENSSH PRIVATE KEY-----"),
    ExistingKey("Key file on the orchestrator", "/secrets/ssh/eu1"),
    AllowFrom("Allowed sources", "203.0.113.7, 198.51.100.0/24"),
}

/** On/off choices of the wizard. */
enum class WizardToggle {
    CreateUser,
    DockerGroup,
    DisableRootLogin,
    DisablePasswordLogin,
    Firewall,
    RestrictSsh,
}

/**
 * Everything entered so far. Kept in [AddNodeWizardStore] so it survives switching tabs; the
 * password lives only here, in memory, and is cleared once setup succeeds or the wizard is reset.
 */
data class AddNodeDraft(
    val step: WizardStep = WizardStep.Server,
    val values: Map<WizardField, String> = DEFAULT_VALUES,
    val toggles: Set<WizardToggle> = DEFAULT_TOGGLES,
    val auth: AuthMethod = AuthMethod.Password,
    val keyMode: KeyMode = KeyMode.Generate,
    /** Last connection test, and the login it was made with (a change of login invalidates it). */
    val test: ConnectionTestDto? = null,
    val testedLogin: SshLoginDto? = null,
    /** The node's management key, and the node id it was made for. */
    val key: SshKeyDto? = null,
    val keyFor: String? = null,
    /** Set once a step was submitted with problems, so they are shown. */
    val showErrors: Boolean = false,
    val job: SetupJobDto? = null,
) {
    operator fun get(field: WizardField): String = values[field].orEmpty()

    fun has(toggle: WizardToggle): Boolean = toggle in toggles

    companion object {
        val DEFAULT_VALUES = mapOf(
            WizardField.Port to "22",
            WizardField.LoginUser to "root",
            WizardField.NewUser to "homegap",
        )
        val DEFAULT_TOGGLES = setOf(
            WizardToggle.CreateUser,
            WizardToggle.DockerGroup,
            WizardToggle.DisableRootLogin,
            WizardToggle.DisablePasswordLogin,
            WizardToggle.Firewall,
        )
    }
}

/** Holds the wizard's [AddNodeDraft] for the life of the page. */
@SingleIn(AppScope::class)
@Inject
class AddNodeWizardStore {
    private val _draft = MutableStateFlow(AddNodeDraft())
    val draft: StateFlow<AddNodeDraft> = _draft.asStateFlow()

    fun update(block: (AddNodeDraft) -> AddNodeDraft) = _draft.update(block)

    fun reset() {
        _draft.value = AddNodeDraft()
    }
}
