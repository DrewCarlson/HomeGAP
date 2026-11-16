package homegap.identity

import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable

/**
 * The users of HomeGAP, on the homelab. This is the authority: it holds the private key that
 * signs the [Directory], and the key never leaves [privateDir]. After each change it writes the
 * signed directory and the public key to [sharedDir], where the sign-in server reads them.
 */
class IdentityStore(
    private val privateDir: String,
    private val sharedDir: String,
    private val now: () -> Long = ::nowMillis,
) {
    private val lock = SynchronizedObject()
    private val keyFile = "$privateDir/$KEY_FILE"
    private val usersFile = "$privateDir/$USERS_FILE"

    @Serializable
    private data class Stored(
        val version: Long = 0,
        val users: List<User> = emptyList(),
        val machines: List<Machine> = emptyList(),
        val resources: List<Resource> = emptyList(),
        val issuers: Map<String, String> = emptyMap(),
    )

    private val key: SigningKey by lazy { loadKey() }
    private var stored: Stored? = null

    /** The public key that each reader of the directory needs. */
    val publicKey: String get() = key.publicKey

    fun users(): List<User> = synchronized(lock) { load().users }

    /** Creates a user with a new password. Returns the user and the password, which HomeGAP does not keep. */
    fun create(email: String, name: String, roles: List<String>): Pair<User, String> = synchronized(lock) {
        val address = email.trim().lowercase()
        require(EMAIL.matches(address)) { "$email is not an email address" }
        require(load().users.none { it.email == address }) { "a user with the address $address exists already" }
        val password = Crypto.password()
        val user = User(
            id = Crypto.token(ID_LENGTH, ID_ALPHABET),
            email = address,
            name = name.trim(),
            roles = cleanRoles(roles),
            passwordHash = Keys.hashPassword(password),
            createdAt = now(),
        )
        save { it.copy(users = it.users + user) }
        user to password
    }

    /** Gives the user a new password and returns it. With [password], the user gets that one. */
    fun setPassword(id: String, password: String? = null): String = synchronized(lock) {
        val chosen = password ?: Crypto.password()
        require(chosen.length >= MIN_PASSWORD_LENGTH) { "a password has $MIN_PASSWORD_LENGTH characters or more" }
        update(id) { it.copy(passwordHash = Keys.hashPassword(chosen)) }
        chosen
    }

    fun setRoles(id: String, roles: List<String>): User = synchronized(lock) {
        update(id) { it.copy(roles = cleanRoles(roles)) }
    }

    fun setBlocked(id: String, blocked: Boolean): User = synchronized(lock) {
        update(id) { it.copy(blocked = blocked) }
    }

    fun remove(id: String): User = synchronized(lock) {
        val user = requireNotNull(load().users.firstOrNull { it.id == id }) { "no user $id" }
        save { it.copy(users = it.users - user) }
        user
    }

    fun machines(): List<Machine> = synchronized(lock) { load().machines }

    /** Creates a machine. Returns it and its secret, which HomeGAP does not keep. */
    fun createMachine(name: String, roles: List<String>): Pair<Machine, String> = synchronized(lock) {
        val label = name.trim()
        require(MACHINE_NAME.matches(label)) { "a machine name has 1 to 60 letters, digits, spaces, '.', '-' and '_'" }
        require(load().machines.none { it.name.equals(label, ignoreCase = true) }) { "a machine $label exists already" }
        val secret = Crypto.token(MACHINE_SECRET_LENGTH)
        val machine = Machine(
            id = Crypto.token(ID_LENGTH, ID_ALPHABET),
            name = label,
            roles = cleanRoles(roles),
            secretHash = Keys.fingerprint(secret),
            createdAt = now(),
        )
        save { it.copy(machines = it.machines + machine) }
        machine to secret
    }

    fun removeMachine(id: String): Machine = synchronized(lock) {
        val machine = requireNotNull(load().machines.firstOrNull { it.id == id }) { "no machine $id" }
        save { it.copy(machines = it.machines - machine) }
        machine
    }

    /**
     * Sets the private names of the VPN: name to the roles that can reach it. A name keeps its
     * address for as long as it exists, and a new name gets a free address of [subnet]
     * (`a.b.c.0/24`). Returns the resources, in the order of their names.
     */
    fun setResources(wanted: Map<String, List<String>>, subnet: String): List<Resource> = synchronized(lock) {
        val current = load().resources.associateBy { it.name }
        val base = subnet.substringBefore('/').substringBeforeLast('.')
        val used = current.filterKeys { it in wanted }.values.map { it.address }.toMutableSet()
        val next = wanted.keys.sorted().map { name ->
            val kept = current[name]?.address?.takeIf { it.startsWith("$base.") }
            val address = kept ?: (FIRST_ALIAS_HOST..LAST_ALIAS_HOST).map { "$base.$it" }.firstOrNull { it !in used }
                ?: error("the subnet $subnet has no free address for the private name $name")
            used += address
            Resource(name, address, wanted.getValue(name).sorted())
        }
        if (next != load().resources) save { it.copy(resources = next) }
        next
    }

    /** Sets the public key [publicKey] of the session issuer [name]. Returns true if that is a change. */
    fun setIssuer(name: String, publicKey: String): Boolean = synchronized(lock) {
        if (load().issuers[name] == publicKey) return@synchronized false
        save { it.copy(issuers = it.issuers + (name to publicKey)) }
        true
    }

    /** The current directory with the signature of the homelab. */
    fun directory(): Signed = synchronized(lock) { seal(load()) }

    /** Writes the signed directory and the public key again, for a shared folder that lost them. */
    fun publish(): Unit = synchronized(lock) { publish(load()) }

    private fun update(id: String, change: (User) -> User): User {
        val user = requireNotNull(load().users.firstOrNull { it.id == id }) { "no user $id" }
        val changed = change(user)
        save { state -> state.copy(users = state.users.map { if (it.id == id) changed else it }) }
        return changed
    }

    private fun cleanRoles(roles: List<String>): List<String> {
        val clean = roles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(clean.all { ROLE.matches(it) }) { "a role has letters, digits, spaces, '-' and '_' only" }
        return clean.ifEmpty { listOf(DEFAULT_ROLE) }
    }

    private fun load(): Stored {
        stored?.let { return it }
        val loaded = when (val text = Files.readTextOrNull(usersFile)) {
            null -> Stored()
            else -> runCatching { Signed.JSON.decodeFromString<Stored>(text) }
                .getOrElse { error("$usersFile is not readable: ${it.message}") }
        }
        stored = loaded
        return loaded
    }

    private fun save(change: (Stored) -> Stored) {
        val next = change(load()).let { it.copy(version = it.version + 1) }
        Files.mkdirs(privateDir)
        Files.chmodPath(privateDir, OWNER_DIR)
        Files.writeAtomic(usersFile, Signed.JSON.encodeToString(next) + "\n", OWNER_ONLY)
        stored = next
        publish(next)
    }

    private fun seal(state: Stored): Signed {
        val directory = Directory(
            version = state.version,
            issuedAt = now(),
            users = state.users.sortedBy { it.email },
            machines = state.machines.sortedBy { it.name },
            resources = state.resources,
            issuers = state.issuers,
        )
        return Signed.seal(key, Signed.JSON.encodeToString(directory))
    }

    private fun publish(state: Stored) {
        Files.mkdirs(sharedDir)
        Files.writeAtomic("$sharedDir/$PUBLIC_KEY_FILE", key.publicKey + "\n", READABLE)
        Files.writeAtomic("$sharedDir/$DIRECTORY_FILE", seal(state).render(), READABLE)
    }

    private fun loadKey(): SigningKey {
        Files.readTextOrNull(keyFile)?.let { text ->
            return runCatching { Signed.JSON.decodeFromString<SigningKey>(text) }
                .getOrElse { error("$keyFile is not readable. Without this key, each node needs the new one.") }
        }
        val created = Keys.newSigningKey()
        Files.mkdirs(privateDir)
        Files.chmodPath(privateDir, OWNER_DIR)
        Files.writeAtomic(keyFile, Signed.JSON.encodeToString(created) + "\n", OWNER_ONLY)
        return created
    }

    companion object {
        const val DIRECTORY_FILE = "directory.json"
        const val PUBLIC_KEY_FILE = "authority.pub"
        const val ADMIN_ROLE = "Admin"
        const val DEFAULT_ROLE = "Member"
        const val MIN_PASSWORD_LENGTH = 12
        private const val KEY_FILE = "authority.key"
        private const val USERS_FILE = "users.json"
        private const val MACHINE_SECRET_LENGTH = 48

        /** The low addresses of the subnet belong to the apps, which run their DNS proxy on the first one. */
        private const val FIRST_ALIAS_HOST = 10
        private const val LAST_ALIAS_HOST = 254
        private val MACHINE_NAME = Regex("^[A-Za-z0-9 ._-]{1,60}$")
        private const val ID_LENGTH = 15
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val OWNER_ONLY = "600".toInt(8)
        private val OWNER_DIR = "700".toInt(8)
        private val READABLE = "644".toInt(8)
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val ROLE = Regex("^[A-Za-z0-9 _-]{1,40}$")
    }
}
