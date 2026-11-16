package homegap

import homegap.identity.Directory
import homegap.identity.IdentityStore
import homegap.identity.Keys
import homegap.identity.Signed
import homegap.platform.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityStoreTest {
    private fun dir() = "/tmp/homegap-identity-test-${Keys.encode(homegap.platform.Crypto.randomBytes(6))}"

    private fun store(root: String) = IdentityStore("$root/authority", "$root/identity") { 1_000 }

    private fun directory(root: String, key: String): Directory? =
        Signed.directory(Files.readText("$root/identity/${IdentityStore.DIRECTORY_FILE}"), key)

    @Test
    fun eachChangeGetsTheSignatureOfTheHomelab() {
        val root = dir()
        val store = store(root)
        val (user, password) = store.create(" Ada@Example.net ", "Ada", listOf("Admin"))
        assertEquals("ada@example.net", user.email)
        assertTrue(Keys.verifyPassword(password, user.passwordHash!!))

        val published = assertNotNull(directory(root, store.publicKey))
        assertEquals(listOf("ada@example.net"), published.users.map { it.email })
        assertEquals(store.publicKey, Files.readText("$root/identity/${IdentityStore.PUBLIC_KEY_FILE}").trim())

        store.setBlocked(user.id, true)
        val blocked = assertNotNull(directory(root, store.publicKey))
        assertTrue(blocked.user(user.id)!!.blocked)
        assertTrue(blocked.version > published.version)
    }

    @Test
    fun aDirectoryWithAnotherSignatureIsNotADirectory() {
        val root = dir()
        val store = store(root)
        store.create("ada@example.net", "", emptyList())
        val file = "$root/identity/${IdentityStore.DIRECTORY_FILE}"
        assertNull(Signed.directory(Files.readText(file), Keys.newSigningKey().publicKey))
        // A node that adds an admin to the text has no signature for the new text.
        val signed = Signed.parse(Files.readText(file))!!
        val forged = signed.copy(payload = signed.payload.replace("Member", "Admin"))
        assertNull(Signed.directory(forged.render(), store.publicKey))
    }

    @Test
    fun theKeyAndTheUsersStayAfterARestart() {
        val root = dir()
        val first = store(root)
        val (user, _) = first.create("ada@example.net", "", listOf("Member", "Family"))
        val second = store(root)
        assertEquals(first.publicKey, second.publicKey)
        assertEquals(listOf("Member", "Family"), second.users().single { it.id == user.id }.roles)
        assertFalse("privateKey" in Files.readText("$root/identity/${IdentityStore.DIRECTORY_FILE}"))
    }

    @Test
    fun badInputIsRefused() {
        val store = store(dir())
        store.create("ada@example.net", "", emptyList())
        assertFailsWith<IllegalArgumentException> { store.create("ADA@example.net", "", emptyList()) }
        assertFailsWith<IllegalArgumentException> { store.create("not an address", "", emptyList()) }
        assertFailsWith<IllegalArgumentException> { store.create("bob@example.net", "", listOf("a;b")) }
        assertFailsWith<IllegalArgumentException> { store.setPassword(store.users().first().id, "short") }
        assertFailsWith<IllegalArgumentException> { store.remove("nobody") }
        assertEquals(listOf("Member"), store.users().single().roles)
    }

    @Test
    fun theIssuerOfSessionsIsInTheSignedDirectory() {
        val root = dir()
        val store = store(root)
        val issuer = Keys.newSigningKey().publicKey
        assertTrue(store.setIssuer("homelab", issuer))
        assertFalse(store.setIssuer("homelab", issuer))
        assertEquals(mapOf("homelab" to issuer), directory(root, store.publicKey)!!.issuers)
    }
}
