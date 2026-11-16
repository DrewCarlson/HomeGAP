package homegap

import homegap.identity.IdentityStore
import homegap.identity.Keys
import homegap.identity.Signed
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.signin.Devices
import homegap.signin.SignIn
import homegap.signin.SignInServer
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppApiTest {
    private class Fixture {
        val root = "/tmp/homegap-appapi-test-${Keys.encode(Crypto.randomBytes(6))}"
        val store = IdentityStore("$root/authority", "$root/identity")
        val server = SignInServer("$root/sign-in", "$root/identity")
        val userId: String
        val password: String

        init {
            val (user, secret) = store.create("ada@example.net", "Ada", listOf("Admin"))
            userId = user.id
            password = secret
            store.setIssuer(SignIn.ISSUER, Files.readText("$root/sign-in/${SignIn.PUBLIC_KEY_FILE}").trim())
            server.reload()
        }
    }

    private fun app(fixture: Fixture, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { with(fixture.server) { module() } }
        block()
    }

    private suspend fun HttpResponse.data(): JsonObject =
        Signed.JSON.decodeFromString<JsonObject>(bodyAsText())["data"]!!.jsonObject

    /** Signs in with the password and returns the session. */
    private suspend fun ApplicationTestBuilder.session(fixture: Fixture): String {
        val code = client.post("/api/v1/auth/device-web-auth/start") { setBody("""{"deviceName":"Pixel"}""") }
            .data()["code"]!!.jsonPrimitive.content
        val page = client.get("/auth/login/device?code=$code").bodyAsText()
        val form = Regex("name=\"form\" value=\"([^\"]+)\"").find(page)!!.groupValues[1]
        val body = "code=$code&form=$form&email=ada%40example.net&password=${fixture.password}"
        val posted = client.post("/auth/login/device") {
            setBody(body)
            headers.append("Content-Type", "application/x-www-form-urlencoded")
        }
        assertEquals(HttpStatusCode.OK, posted.status)
        return client.get("/api/v1/auth/device-web-auth/poll/$code").data()["token"]!!.jsonPrimitive.content
    }

    @Test
    fun theAppGetsItsOrganisationItsDeviceAndItsToken() {
        val fixture = Fixture()
        app(fixture) {
            val session = session(fixture)
            val user = fixture.userId
            suspend fun get(path: String) = client.get("/api/v1$path") { cookie("p_session_token", session) }

            val orgs = get("/user/$user/orgs").data()["orgs"]!!.jsonArray
            assertEquals("homegap", orgs.single().jsonObject["orgId"]!!.jsonPrimitive.content)
            assertEquals("true", get("/org/homegap/user/$user/check").data()["allowed"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, get("/server-info").status)
            assertEquals(HttpStatusCode.OK, get("/org/homegap/site-resources?mode=gateway").status)
            assertEquals(HttpStatusCode.Forbidden, get("/user/other/orgs").status)
            assertEquals(HttpStatusCode.NotFound, get("/org/other").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/user/$user/orgs").status)

            val device = client.put("/api/v1/user/$user/olm") {
                cookie("p_session_token", session)
                setBody("""{"name":"Pixel"}""")
            }.data()
            val id = device["olmId"]!!.jsonPrimitive.content
            val secret = device["secret"]!!.jsonPrimitive.content
            assertEquals("Pixel", get("/user/$user/olm/$id").data()["name"]!!.jsonPrimitive.content)

            suspend fun token(body: String) = client.post("/api/v1/auth/olm/get-token") { setBody(body) }
            val granted = token("""{"olmId":"$id","secret":"$secret","userToken":"$session","orgId":"homegap"}""")
            assertEquals(HttpStatusCode.OK, granted.status)
            assertTrue(granted.data()["token"]!!.jsonPrimitive.content.startsWith("hg1."))
            // The secret alone is not enough for a device of a user, and a wrong secret is not either.
            assertEquals(HttpStatusCode.Unauthorized, token("""{"olmId":"$id","secret":"$secret"}""").status)
            val wrongSecret = token("""{"olmId":"$id","secret":"x","userToken":"$session"}""")
            assertEquals(HttpStatusCode.Unauthorized, wrongSecret.status)
        }
    }

    @Test
    fun aMachineGetsATokenWithItsSecretUntilItIsRemoved() {
        val fixture = Fixture()
        val (machine, secret) = fixture.store.createMachine("backup server", listOf("Member"))
        fixture.server.reload()
        app(fixture) {
            suspend fun token(text: String) =
                client.post("/api/v1/auth/olm/get-token") { setBody("""{"olmId":"${machine.id}","secret":"$text"}""") }
            assertEquals(HttpStatusCode.OK, token(secret).status)
            assertEquals(HttpStatusCode.Unauthorized, token("wrong").status)
            fixture.store.removeMachine(machine.id)
            fixture.server.reload()
            assertEquals(HttpStatusCode.Unauthorized, token(secret).status)
        }
    }

    @Test
    fun aTunnelTokenIsNotASessionAndASessionIsNotATunnelToken() {
        val fixture = Fixture()
        val directory = { Signed.directory(fixture.store.directory().render(), fixture.store.publicKey) }
        val signIn = SignIn("${fixture.root}/sign-in", directory)
        val devices = Devices("${fixture.root}/sign-in", "100.90.0.0/24")
        val (device, _) = devices.create(fixture.userId, "Pixel")
        val tunnel = signIn.tunnelToken(device)
        assertNull(signIn.user(tunnel))
        assertEquals(device.id, signIn.tunnelClaims(tunnel)?.device)
        val code = signIn.start("Pixel", "Pangolin")!!
        signIn.signIn(code, "ada@example.net", fixture.password, "203.0.113.7")
        val session = signIn.collect(code)!!
        assertNull(signIn.tunnelClaims(session))
        assertNotNull(signIn.user(session))
        fixture.store.setBlocked(fixture.userId, true)
        assertNull(signIn.tunnelClaims(tunnel), "a block ends the token of the device")
    }

    @Test
    fun eachDeviceKeepsItsOwnAddress() {
        val dir = "/tmp/homegap-devices-test-${Keys.encode(Crypto.randomBytes(6))}"
        val devices = Devices(dir, "100.90.0.0/29")
        val (first, secret) = devices.create("u1", "phone")
        val (second, _) = devices.create("u1", "laptop")
        assertEquals("100.90.0.2", first.address)
        assertEquals("100.90.0.3", second.address)
        assertEquals("100.90.0.1", Devices.serverAddress("100.90.0.0/29"))
        assertEquals(first, devices.verify(first.id, secret))
        assertNull(devices.verify(first.id, "wrong"))
        assertNotEquals(first.id, second.id)

        devices.remove(first.id)
        val restarted = Devices(dir, "100.90.0.0/29")
        val machine = restarted.machine("m1", "server")
        assertEquals("100.90.0.2", machine.address, "a free address is used again, also after a restart")
        assertEquals(machine, restarted.machine("m1", "server"))
        // 8 addresses: the network, the homelab and the last one are not for devices.
        repeat(3) { restarted.create("u2", "d$it") }
        assertFailsWith<IllegalStateException> { restarted.create("u2", "one more") }
    }
}
