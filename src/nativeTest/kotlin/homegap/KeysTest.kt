package homegap

import homegap.identity.Keys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeysTest {
    @Test
    fun aSignatureVerifiesOnlyWithItsKeyAndItsData() {
        val key = Keys.newSigningKey()
        val other = Keys.newSigningKey()
        val data = "users".encodeToByteArray()
        val signature = Keys.sign(key.privateKey, data)
        assertTrue(Keys.verify(key.publicKey, data, signature))
        assertFalse(Keys.verify(other.publicKey, data, signature))
        assertFalse(Keys.verify(key.publicKey, "user".encodeToByteArray(), signature))
        assertFalse(Keys.verify("not a key", data, signature))
        assertFalse(Keys.verify(key.publicKey, data, ByteArray(64)))
    }

    @Test
    fun aPasswordHashAcceptsOnlyThePassword() {
        val stored = Keys.hashPassword("correct horse")
        assertTrue(stored.startsWith("pbkdf2-sha256$600000$"))
        assertTrue(Keys.verifyPassword("correct horse", stored))
        assertFalse(Keys.verifyPassword("correct horse ", stored))
        assertNotEquals(stored, Keys.hashPassword("correct horse"), "each hash has its own salt")
        assertFalse(Keys.verifyPassword("x", "plain"))
        assertFalse(Keys.verifyPassword("x", $$"pbkdf2-sha256$999999999$AAAA$AAAA"))
    }

    @Test
    fun sha256IsTheStandardOne() {
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", Keys.fingerprint("abc"))
    }
}
