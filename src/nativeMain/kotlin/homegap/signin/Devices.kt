package homegap.signin

import homegap.identity.Keys
import homegap.identity.Signed
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
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
)

/**
 * The devices that this sign-in server knows, in [dir]. Each gets an address from [subnet]
 * (`a.b.c.d/prefix`) when the server sees it for the first time. The first address of the subnet
 * belongs to the homelab.
 */
class Devices(private val dir: String, private val subnet: String, private val now: () -> Long = ::nowMillis) {
    private val lock = SynchronizedObject()
    private var loaded: MutableMap<String, Device>? = null

    val prefix: Int get() = subnet.substringAfter('/').toInt()

    fun get(id: String): Device? = synchronized(lock) { devices()[id] }

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
        devices()[id] ?: Device(id = id, name = name, address = freeAddress(), createdAt = now()).also { save(it) }
    }

    /** The device [id] of a user if [secret] is its secret. */
    fun verify(id: String, secret: String): Device? {
        val device = get(id) ?: return null
        val expected = device.secretHash ?: return null
        return device.takeIf { Crypto.constantTimeEquals(expected, Keys.fingerprint(secret)) }
    }

    fun remove(id: String): Unit = synchronized(lock) {
        if (devices().remove(id) != null) write()
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
        Files.writeAtomic("$dir/$FILE", Signed.JSON.encodeToString(devices().values.toList()) + "\n", OWNER_ONLY)
    }

    /** The lowest free address of the subnet. The network, the homelab and the last address are not for devices. */
    private fun freeAddress(): String {
        val base = toInt(subnet.substringBefore('/'))
        val size = 1L shl (ADDRESS_BITS - prefix)
        val used = devices().values.map { toInt(it.address) }.toSet()
        val offset = (FIRST_DEVICE until size - 1).firstOrNull { base + it !in used }
            ?: error("The tunnel subnet $subnet has no free address.")
        return toText(base + offset)
    }

    private fun toInt(address: String): Long =
        address.split('.').fold(0L) { value, part -> (value shl BYTE_BITS) or part.toLong() }

    private fun toText(value: Long): String =
        (ADDRESS_BYTES - 1 downTo 0).joinToString(".") { ((value shr (it * BYTE_BITS)) and BYTE_MASK).toString() }

    companion object {
        /** The address of the homelab in [subnet]: the first one. */
        fun serverAddress(subnet: String): String {
            val parts = subnet.substringBefore('/').split('.')
            return (parts.dropLast(1) + (parts.last().toInt() + 1).toString()).joinToString(".")
        }

        private const val FILE = "devices.json"
        private const val MAX_PER_USER = 50
        private const val ID_LENGTH = 15
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val SECRET_LENGTH = 48
        private const val NAME_LENGTH = 80
        private const val FIRST_DEVICE = 2L
        private const val ADDRESS_BITS = 32
        private const val ADDRESS_BYTES = 4
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xffL
        private val OWNER_ONLY = "600".toInt(8)
    }
}
