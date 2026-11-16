package homegap.signin

import homegap.identity.Directory
import homegap.identity.Keys
import homegap.identity.Signed
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.provision.Mesh
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable

/**
 * A device that connects: an app of a user, or a machine. [address] is its address in the
 * tunnel, which it keeps for as long as it exists. A machine has no [secretHash] here, because
 * the directory of the homelab holds it.
 */
@Serializable
data class Device(
    val id: String,
    /** Empty for a machine. */
    val userId: String = "",
    val name: String = "",
    val secretHash: String? = null,
    val address: String,
    val createdAt: Long = 0,
    /** The app and its version, as the device said at its last connection. */
    val agent: String? = null,
    /** When the device connected or disconnected last (epoch ms). */
    val lastSeen: Long? = null,
    /** The node that the last connection came through. */
    val node: String? = null,
    /** The WireGuard public key of the tunnel that the device runs, or null after it stopped the tunnel. */
    val tunnelKey: String? = null,
)

/**
 * The devices that this sign-in server knows, in [dir]. Each gets an address from [subnet]
 * (`a.b.c.d/prefix`) when the server sees it for the first time. The first address of the subnet
 * belongs to the homelab.
 */
class Devices(
    private val dir: String,
    private val subnet: String,
    private val pool: Pool = Pool(),
    private val now: () -> Long = ::nowMillis,
) {
    /**
     * The addresses that this server gives, and the devices of other servers that it accepts.
     *
     * Each server that creates devices has its own part of the subnet, so two servers never give
     * one address. The homelab has the start of the subnet. A node has 254 addresses, at
     * [NODE_POOL_BASE] plus its number in the third part of the address.
     */
    class Pool(
        /** The number of the node, or null for the homelab. */
        val node: Int? = null,
        /** The devices that another server created. This server accepts them and never changes them. */
        val known: () -> List<Device> = { emptyList() },
        /** A file that gets the list of devices after each change, for the nodes. */
        val mirror: String? = null,
    )

    private val lock = SynchronizedObject()
    private var loaded: MutableMap<String, Device>? = null

    val prefix: Int get() = subnet.substringAfter('/').toInt()

    fun get(id: String): Device? = synchronized(lock) { devices()[id] } ?: pool.known().firstOrNull { it.id == id }

    /** Creates a device of the user. Returns it and its secret, which HomeGAP does not keep. */
    fun create(userId: String, name: String): Pair<Device, String> = synchronized(lock) {
        check(devices().values.count { it.userId == userId } < MAX_PER_USER) { "This user has too many devices." }
        val secret = Crypto.token(SECRET_LENGTH)
        val device = Device(
            id = Crypto.token(ID_LENGTH, ID_ALPHABET),
            userId = userId,
            name = name.take(NAME_LENGTH),
            secretHash = Keys.fingerprint(secret),
            address = freeAddress(),
            createdAt = now(),
        )
        save(device)
        device to secret
    }

    /** The device of the machine [id] with the name [name]. The first call gives it its address. */
    fun machine(id: String, name: String): Device = synchronized(lock) {
        // A machine that another server saw first keeps the address from there.
        devices()[id] ?: pool.known().firstOrNull { it.id == id }
            ?: Device(id = id, name = name, address = freeAddress(), createdAt = now()).also { save(it) }
    }

    /** The device [id] of a user if [secret] is its secret. */
    fun verify(id: String, secret: String): Device? {
        val device = get(id) ?: return null
        val expected = device.secretHash ?: return null
        return device.takeIf { Crypto.constantTimeEquals(expected, Keys.fingerprint(secret)) }
    }

    /** Notes a connection or a disconnection of the device [id] of this server. [agent] and [node] stay if null. */
    fun seen(id: String, agent: String? = null, node: String? = null): Unit = synchronized(lock) {
        val device = devices()[id] ?: return@synchronized
        save(device.copy(agent = agent ?: device.agent, node = node ?: device.node, lastSeen = now()))
    }

    /** Keeps [key] as the key of the tunnel that the device [id] runs. Null says that the tunnel stopped. */
    fun tunnel(id: String, key: String?): Unit = synchronized(lock) {
        val device = devices()[id] ?: return@synchronized
        if (device.tunnelKey != key) save(device.copy(tunnelKey = key))
    }

    /** The devices of this server that run a tunnel. */
    fun running(): List<Device> = synchronized(lock) { devices().values.filter { it.tunnelKey != null } }

    fun remove(id: String): Unit = synchronized(lock) {
        if (devices().remove(id) != null) write()
    }

    /**
     * Removes each device whose user or machine is not in [directory] any more, and each device
     * that the admin removed. Its address is free again. Returns the devices that went.
     */
    fun prune(directory: Directory): List<Device> = synchronized(lock) {
        val gone = devices().values.filter { device ->
            val owner = if (device.userId.isEmpty()) directory.machine(device.id) else directory.user(device.userId)
            owner == null || device.id in directory.removedDevices
        }
        gone.forEach { devices().remove(it.id) }
        if (gone.isNotEmpty()) write()
        gone
    }

    private fun devices(): MutableMap<String, Device> {
        loaded?.let { return it }
        val text = Files.readTextOrNull("$dir/$FILE")
        val list = text?.let { runCatching { Signed.JSON.decodeFromString<List<Device>>(it) }.getOrNull() }
        check(text == null || list != null) { "$dir/$FILE is not readable" }
        return list.orEmpty().associateByTo(LinkedHashMap()) { it.id }.also { loaded = it }
    }

    private fun save(device: Device) {
        devices()[device.id] = device
        write()
    }

    private fun write() {
        Files.mkdirs(dir)
        val text = Signed.JSON.encodeToString(devices().values.toList()) + "\n"
        Files.writeAtomic("$dir/$FILE", text, OWNER_ONLY)
        pool.mirror?.let { Files.writeAtomic(it, text) }
    }

    /** Writes the list for the nodes again, for a shared folder that lost it. */
    fun publish(): Unit = synchronized(lock) {
        val text = Signed.JSON.encodeToString(devices().values.toList()) + "\n"
        pool.mirror?.takeIf { Files.readTextOrNull(it) != text }?.let { Files.writeAtomic(it, text) }
    }

    /** The lowest free address of the subnet. The network, the homelab and the last address are not for devices. */
    private fun freeAddress(): String {
        val base = toInt(subnet.substringBefore('/'))
        val size = 1L shl (ADDRESS_BITS - prefix)
        val used = (devices().values + pool.known()).map { toInt(it.address) }.toSet()
        val block = Mesh.EXIT_RANGE.toLong()
        val (first, end) = when (val node = pool.node) {
            // The start of the subnet, up to the pools of the nodes. A small subnet has no such pools.
            null -> FIRST_DEVICE to if (size > NODE_POOL_BASE * block) NODE_POOL_BASE * block else size - 1
            else -> {
                val start = (NODE_POOL_BASE + node) * block
                val fits = start + block <= size - block
                check(fits) { "The tunnel subnet $subnet has no room for the devices of a node." }
                start + 1 to start + block - 1
            }
        }
        val offset = (first until end).firstOrNull { base + it !in used }
            ?: error("The tunnel subnet $subnet has no free address.")
        return toText(base + offset)
    }

    private fun toInt(address: String): Long =
        address.split('.').fold(0L) { value, part -> (value shl BYTE_BITS) or part.toLong() }

    private fun toText(value: Long): String =
        (ADDRESS_BYTES - 1 downTo 0).joinToString(".") { ((value shr (it * BYTE_BITS)) and BYTE_MASK).toString() }

    companion object {
        /** The address of the homelab in [subnet]: the first one. */
        fun serverAddress(subnet: String): String = Mesh.vpnAddress(subnet)

        private const val FILE = "devices.json"
        private const val MAX_PER_USER = 50
        private const val ID_LENGTH = 15
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val SECRET_LENGTH = 48
        private const val NAME_LENGTH = 80
        private const val FIRST_DEVICE = 2L

        /** The third part of the address where the pools of the nodes start, in a /16. */
        const val NODE_POOL_BASE = 100L
        private const val ADDRESS_BITS = 32
        private const val ADDRESS_BYTES = 4
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xffL
        private val OWNER_ONLY = "600".toInt(8)
    }
}
