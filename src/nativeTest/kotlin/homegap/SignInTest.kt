package homegap

import homegap.identity.Directory
import homegap.identity.IdentityStore
import homegap.identity.Keys
import homegap.identity.SessionClaims
import homegap.identity.Sessions
import homegap.identity.Signed
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.signin.SignIn
import homegap.signin.SignInPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInTest {
    private class Fixture {
        val root = "/tmp/homegap-signin-test-${Keys.encode(Crypto.randomBytes(6))}"
        var time = 1_000_000L
        val store = IdentityStore("$root/authority", "$root/identity") { time }
        val signIn = SignIn("$root/sign-in", { directory() }) { time }
        val password: String
        val userId: String

        init {
            val (user, secret) = store.create("ada@example.net", "Ada", listOf("Admin"))
            password = secret
            userId = user.id
            store.setIssuer(SignIn.ISSUER, signIn.publicKey)
        }

        fun directory(): Directory? =
            Signed.directory(Files.readText("$root/identity/${IdentityStore.DIRECTORY_FILE}"), store.publicKey)

        fun signIn(code: String, password: String = this.password, address: String = "203.0.113.7") =
            signIn.signIn(code, "ada@example.net", password, address)

        fun session(): String {
            val code = signIn.start("Pixel", "Pangolin")!!
            assertIs<SignIn.Outcome.SignedIn>(signIn.signIn(code, "Ada@example.net", password, "203.0.113.7"))
            return signIn.collect(code)!!
        }
    }

    @Test
    fun aDeviceGetsItsSessionAfterTheUserSignsIn() {
        val fixture = Fixture()
        val code = fixture.signIn.start("Pixel", "Pangolin")!!
        assertNull(fixture.signIn.collect(code), "no session before the sign-in")
        assertIs<SignIn.Outcome.WrongPassword>(fixture.signIn.signIn(code, "ada@example.net", "wrong", "203.0.113.7"))
        assertIs<SignIn.Outcome.WrongPassword>(fixture.signIn.signIn(code, "eve@example.net", "wrong", "203.0.113.7"))
        assertNull(fixture.signIn.collect(code))
        // The code works with lower case and with a hyphen, as a person types it.
        val typed = code.lowercase().chunked(4).joinToString("-")
        assertIs<SignIn.Outcome.SignedIn>(fixture.signIn(typed))
        val token = assertNotNull(fixture.signIn.collect(code))
        assertNull(fixture.signIn.collect(code), "the code gives its session once")
        assertEquals("ada@example.net", fixture.signIn.user(token)?.email)
    }

    @Test
    fun aSessionEndsWithItsTimeASignOutOrABlock() {
        val fixture = Fixture()
        val token = fixture.session()
        fixture.time += SignIn.SESSION_LIFETIME.inWholeMilliseconds
        assertNull(fixture.signIn.user(token), "expired")

        val second = fixture.session()
        fixture.signIn.end(second)
        assertNull(fixture.signIn.user(second), "signed out")
        val restarted = SignIn("${fixture.root}/sign-in", { fixture.directory() }) { fixture.time }
        assertNull(restarted.user(second), "after a restart")

        val third = fixture.session()
        fixture.store.setBlocked(fixture.userId, true)
        assertNull(fixture.signIn.user(third), "blocked")
    }

    @Test
    fun onlyAnIssuerOfTheDirectoryGivesAccess() {
        val fixture = Fixture()
        val directory = fixture.directory()!!
        val claims = SessionClaims("sid", fixture.userId, SignIn.ISSUER, fixture.time, fixture.time + 60_000)
        // A node signs a session for a real user with its own key.
        val nodeKey = Keys.newSigningKey()
        assertNull(Sessions.read(Sessions.issue(nodeKey, claims), directory, fixture.time))
        assertNull(Sessions.read(Sessions.issue(nodeKey, claims.copy(issuer = "node")), directory, fixture.time))
        // A session of the homelab with one more character in its claims.
        val token = fixture.session()
        val parts = token.split('.')
        val claimsText = Keys.decode(parts[1])!!.decodeToString().replace("Pixel", "Pixal")
        val changed = Keys.encode(claimsText.encodeToByteArray())
        assertNull(Sessions.read("${parts[0]}.$changed.${parts[2]}", directory, fixture.time))
        assertNotNull(Sessions.read(token, directory, fixture.time))
        assertNull(Sessions.read("hg1.x.y", directory, fixture.time))
    }

    @Test
    fun wrongPasswordsStopTheSignInForAWhile() {
        val fixture = Fixture()
        val code = fixture.signIn.start("Pixel", "Pangolin")!!
        repeat(SignIn.MAX_FAILURES) {
            assertIs<SignIn.Outcome.WrongPassword>(fixture.signIn(code, "wrong", "203.0.113.$it"))
        }
        // The right password, from an address with no failure: the user has the limit.
        val locked = fixture.signIn.signIn(code, "ada@example.net", fixture.password, "198.51.100.1")
        assertIs<SignIn.Outcome.TooManyAttempts>(locked)
        fixture.time += SignIn.FAILURE_WINDOW.inWholeMilliseconds + 1
        val other = fixture.signIn.start("Pixel", "Pangolin")!!
        assertIs<SignIn.Outcome.SignedIn>(fixture.signIn(other, address = "198.51.100.1"))
    }

    @Test
    fun aCodeExpires() {
        val fixture = Fixture()
        val code = fixture.signIn.start("Pixel", "Pangolin")!!
        fixture.time += SignIn.CODE_LIFETIME.inWholeMilliseconds + 1
        assertNull(fixture.signIn.pending(code))
        assertIs<SignIn.Outcome.UnknownCode>(fixture.signIn(code))
    }

    @Test
    fun thePageEscapesTheNameOfTheDevice() {
        val pending = SignIn.Pending("<script>alert(1)</script>", "\"app\"", 0, "form")
        val html = SignInPage.form("ABCD2345", pending, "203.0.113.7")
        assertFalse("<script>alert" in html)
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html)
        assertTrue("&quot;app&quot;" in html)
    }
}
