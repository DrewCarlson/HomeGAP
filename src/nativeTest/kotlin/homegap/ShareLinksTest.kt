package homegap

import homegap.dashboard.ShareLinks
import homegap.platform.Crypto
import homegap.platform.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareLinksTest {
    @Test
    fun sha256MatchesKnownDigests() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Crypto.sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Crypto.sha256Hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Crypto.sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun keepsOnlyAHashOfTheToken() {
        val path = tmpPath("links")
        val (link, token) = ShareLinks(path).create(label = "Friend", ttlMillis = null)
        val file = Files.readText(path)
        assertFalse(token in file)
        assertTrue(Crypto.sha256Hex(token) in file)
        // A fresh store reads the file back and still knows the token.
        assertEquals(link.id, ShareLinks(path).verify(token)?.id)
        Files.delete(path)
    }

    @Test
    fun expiredAndRevokedLinksStopWorking() {
        var now = 1_000L
        val links = ShareLinks(tmpPath("links")) { now }
        val (short, shortToken) = links.create(label = "One day", ttlMillis = 500)
        val (forever, foreverToken) = links.create(label = "Forever", ttlMillis = null)
        assertNotNull(links.verify(shortToken))
        now = 1_500L
        assertNull(links.verify(shortToken))
        assertFalse(links.isLive(short.id))
        assertEquals(listOf(forever.id), links.list().map { it.id })

        assertTrue(links.revoke(forever.id))
        assertNull(links.verify(foreverToken))
        assertFalse(links.revoke(forever.id))
    }

    @Test
    fun theDashboardTokenIsNeverAShareLink() {
        val links = ShareLinks(tmpPath("links"))
        links.create(label = "Friend", ttlMillis = null)
        assertNull(links.verify("s3cret"))
        assertNull(links.verify(""))
    }
}
