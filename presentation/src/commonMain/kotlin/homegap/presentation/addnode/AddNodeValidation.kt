package homegap.presentation.addnode

import homegap.api.SshLoginDto

/** Per-step checks of an [AddNodeDraft]; pure, so they are easy to test. */
internal object AddNodeValidation {
    private val NODE_ID = Regex("^[a-z][a-z0-9-]{0,30}$")
    private val UNIX_USER = Regex("^[a-z_][a-z0-9_-]{0,31}$")
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(/(\d{1,2}))?$""")
    private val IPV6 = Regex("""^[0-9a-fA-F:]*:[0-9a-fA-F:]*(/\d{1,3})?$""")

    fun port(draft: AddNodeDraft): Int? = draft[WizardField.Port].trim().toIntOrNull()?.takeIf { it in 1..65535 }

    fun targetUser(draft: AddNodeDraft): String =
        if (draft.has(WizardToggle.CreateUser)) draft[WizardField.NewUser].trim() else draft[WizardField.LoginUser].trim()

    fun sources(draft: AddNodeDraft): List<String> =
        draft[WizardField.AllowFrom].split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** The login as entered (what "Test connection" and the job use). */
    fun login(draft: AddNodeDraft, resetHostKey: Boolean = false) = SshLoginDto(
        host = draft[WizardField.Host].trim(),
        port = port(draft) ?: 22,
        user = draft[WizardField.LoginUser].trim(),
        password = draft[WizardField.Password].takeIf { draft.auth == AuthMethod.Password },
        keyFile = draft[WizardField.LoginKeyFile].trim().takeIf { draft.auth == AuthMethod.Key },
        resetHostKey = resetHostKey,
    )

    fun errors(draft: AddNodeDraft, step: WizardStep, existingNodeIds: Set<String>): List<String> = buildList {
        when (step) {
            WizardStep.Server -> {
                val id = draft[WizardField.NodeId].trim()
                if (!NODE_ID.matches(id)) add("Node id: lowercase letters, digits and dashes, starting with a letter.")
                if (id in existingNodeIds) add("A node named $id already exists.")
                if (draft[WizardField.Host].isBlank()) add("Enter the server's public IP or host name.")
                if (port(draft) == null) add("SSH port must be a number from 1 to 65535.")
                val lat = draft[WizardField.Latitude].trim()
                val lon = draft[WizardField.Longitude].trim()
                if (lat.isNotEmpty() || lon.isNotEmpty()) {
                    val la = lat.toDoubleOrNull()
                    val lo = lon.toDoubleOrNull()
                    if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) {
                        add("Location needs both latitude (-90 to 90) and longitude (-180 to 180).")
                    }
                }
            }
            WizardStep.Login -> {
                if (!UNIX_USER.matches(draft[WizardField.LoginUser].trim())) add("Enter the user to log in as.")
                when (draft.auth) {
                    AuthMethod.Password -> if (draft[WizardField.Password].isEmpty()) add("Enter the password.")
                    AuthMethod.Key -> if (draft[WizardField.LoginKeyFile].isBlank()) add("Enter the key file path.")
                }
                val test = draft.test
                when {
                    test == null || draft.testedLogin != login(draft) -> add("Test the connection before continuing.")
                    !test.ok -> add(test.error ?: "The connection test failed.")
                }
            }
            WizardStep.Access -> {
                val target = targetUser(draft)
                if (draft.has(WizardToggle.CreateUser)) {
                    if (!UNIX_USER.matches(target)) add("User name: lowercase letters, digits, - and _.")
                    if (target == "root") add("Pick a name other than root for the new user.")
                }
                if (draft.has(WizardToggle.DisableRootLogin) && target == "root") {
                    add("Root login can only be disabled when the node is managed as another user; create one.")
                }
                if (draft.key == null || draft.keyFor != draft[WizardField.NodeId].trim()) add("Create or choose the node's SSH key.")
            }
            WizardStep.Firewall -> if (draft.has(WizardToggle.Firewall) && draft.has(WizardToggle.RestrictSsh)) {
                val sources = sources(draft)
                if (sources.isEmpty()) add("List at least one allowed source, or allow SSH from anywhere.")
                sources.filterNot { IPV4.matches(it) || IPV6.matches(it) }.forEach { add("$it is not an IP address or CIDR.") }
            }
            WizardStep.Review -> Unit
        }
    }

    /** Whether [ip] is one of [sources] or inside one of their IPv4 ranges. */
    fun covers(sources: List<String>, ip: String): Boolean = sources.any { source ->
        if (source == ip) return@any true
        val net = IPV4.matchEntire(source) ?: return@any false
        val addr = IPV4.matchEntire(ip) ?: return@any false
        val prefix = net.groupValues[6].toIntOrNull() ?: 32
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        (toLong(net) and mask) == (toLong(addr) and mask)
    }

    private fun toLong(m: MatchResult): Long =
        (1..4).fold(0L) { acc, i -> (acc shl 8) or (m.groupValues[i].toLongOrNull() ?: 0L) }
}
