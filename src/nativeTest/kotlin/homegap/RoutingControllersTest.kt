package homegap

import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import io.ktor.http.HttpHeaders
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.HttpRequestBuilder
import homegap.dashboard.SsoRequest
import homegap.dashboard.DashboardUser
import homegap.dashboard.DashboardSso
import homegap.dashboard.DashboardRole
import homegap.config.DashboardSsoConfig
import homegap.api.SwitchoverRequest
import homegap.config.ConfigLoader
import homegap.dashboard.DashboardAuth
import homegap.dashboard.DashboardServer
import homegap.platform.Files
import homegap.routes.OpenRoutingController
import homegap.routes.RoutingController
import homegap.routes.RoutingControllers
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingControllersTest {
    /** Two independent route groups, as feature controllers contribute them. */
    private val controllers = setOf(
        object : RoutingController {
            override fun init(parent: Route) { parent.get("/one") { call.respondText("one") } }
        },
        object : RoutingController {
            override fun init(parent: Route) { parent.get("/two") { call.respondText("two") } }
        },
    )

    @Test
    fun mountsEveryControllerUnderApi() = testApplication {
        application { RoutingControllers(controllers, DashboardAuth("s3cret", null), emptySet()).init(this) }
        assertEquals("one", client.get("/api/one") { bearerAuth("s3cret") }.bodyAsText())
        assertEquals("two", client.get("/api/two") { bearerAuth("s3cret") }.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/one").status)
    }

    @Test
    fun withoutAConfiguredTokenOneIsGeneratedAndKept() {
        val dir = tmpPath("data")
        val base = TEST_CONFIG.replace("/tmp/homegap-test", dir)
        val config = ConfigLoader.parse(base)
        val first = DashboardAuth.resolve(config)
        assertEquals(DashboardAuth.tokenFile(config), first.generatedFile)
        assertEquals(40, first.token.length)
        // The next start, and the CLI, use the same token.
        assertEquals(first.token, DashboardAuth.resolve(config).token)
        assertEquals(first.token, DashboardAuth.current(config))
        // A token named in the config but not available does not open the API either.
        val named = ConfigLoader.parse("$base\ndashboard:\n  auth_token_file: $dir/missing\n")
        assertEquals(first.token, DashboardAuth.resolve(named).token)
        Files.delete(DashboardAuth.tokenFile(config))
    }

    @Test
    fun requiresTheDashboardTokenWhenOneIsSet() = testApplication {
        val tokenFile = tmpPath("token")
        Files.writeAtomic(tokenFile, "s3cret\n")
        val config = ConfigLoader.parse("$TEST_CONFIG\ndashboard:\n  auth_token_file: $tokenFile\n")
        // A route with its own check stays reachable without the token. The others do not.
        val open = object : OpenRoutingController {
            override fun init(parent: Route, auth: DashboardAuth) {
                parent.get("/api/open") { call.respondText(if (auth.matches("s3cret")) "own check" else "no token") }
            }
        }
        application { RoutingControllers(controllers, DashboardAuth.resolve(config), setOf(open)).init(this) }
        assertEquals("own check", client.get("/api/open").bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/one").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/one") { bearerAuth("wrong") }.status)
        assertEquals("one", client.get("/api/one") { bearerAuth("s3cret") }.bodyAsText())
        Files.delete(tokenFile)
    }

    @Test
    fun manyWrongTokensBlockTheAddressForAWhile() = testApplication {
        application { RoutingControllers(controllers, DashboardAuth("s3cret", null), emptySet()).init(this) }
        repeat(DashboardAuth.MAX_FAILURES + 1) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/one") { bearerAuth("wrong") }.status)
        }
        // The right token does not pass either, so a trial of tokens learns nothing.
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/one") { bearerAuth("s3cret") }.status)
    }

    @Test
    fun aBodyThatTheApiCannotReadIsABadRequest() = testApplication {
        val echo = object : RoutingController {
            override fun init(parent: Route) {
                parent.post("/echo") { call.respond(call.receive<SwitchoverRequest>()) }
            }
        }
        val routes = RoutingControllers(setOf(echo), DashboardAuth("s3cret", null), emptySet())
        application { with(DashboardServer(routes, testConfig())) { module() } }
        val malformed = client.post("/api/echo") {
            bearerAuth("s3cret")
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/echo") { bearerAuth("s3cret") }.status)
    }

    @Test
    fun theFrontDoorNamesTheClientOfARequest() {
        val auth = DashboardAuth("s3cret", null, frontDoor = { setOf("172.32.0.2") })
        assertEquals("203.0.113.9", auth.client("172.32.0.2", "203.0.113.9"))
        // A client can write the start of the header, but the front door writes the end.
        assertEquals("203.0.113.9", auth.client("172.32.0.2", "198.51.100.1, 203.0.113.9"))
        assertEquals("172.32.0.2", auth.client("172.32.0.2", null))
        // A sender that is not the front door can write what it wants.
        assertEquals("192.168.1.50", auth.client("192.168.1.50", "203.0.113.9"))

        // Wrong tokens from one viewer do not block a different viewer behind the same front door.
        repeat(DashboardAuth.MAX_FAILURES + 1) { auth.accepts("wrong", auth.client("172.32.0.2", "203.0.113.9")) }
        assertEquals(false, auth.accepts("s3cret", auth.client("172.32.0.2", "203.0.113.9")))
        assertEquals(true, auth.accepts("s3cret", auth.client("172.32.0.2", "203.0.113.10")))
    }

    private val ssoConfig = DashboardSsoConfig(
        middleware = "authelia@docker",
        groups = listOf("admins"),
        readOnlyGroups = listOf("family"),
    )
    private val frontDoor = setOf("172.32.0.2")

    private fun signIn(
        user: String?,
        groups: String?,
        peer: String = "172.32.0.2",
        host: String = "orc.test.example.net",
    ) = SsoRequest(peer, host, user, groups)

    @Test
    fun theGroupsOfTheSignInGiveTheRole() {
        val sso = DashboardSso("orc.test.example.net", ssoConfig)
        assertEquals(DashboardUser("ada", DashboardRole.ADMIN), sso.user(signIn("ada", "users,admins"), frontDoor))
        assertEquals(DashboardUser("bo", DashboardRole.READ_ONLY), sso.user(signIn("bo", "family, users"), frontDoor))
        assertNull(sso.user(signIn("cy", "users"), frontDoor), "in no group of the config")
        assertNull(sso.user(signIn(null, "admins"), frontDoor), "no user")
        assertNull(sso.user(signIn(" ", "admins"), frontDoor))
        // Only the front door, and only for the name of the dashboard, sends a sign-in that counts.
        assertNull(sso.user(signIn("ada", "admins", peer = "172.32.0.9"), frontDoor))
        assertNull(sso.user(signIn("ada", "admins", host = "wiki.test.example.net"), frontDoor))
        assertEquals("ada", sso.user(signIn("ada", "admins", host = "ORC.test.example.net:443"), frontDoor)?.name)

        val open = DashboardSso("orc.test.example.net", DashboardSsoConfig("authelia@docker"))
        assertEquals(DashboardRole.ADMIN, open.user(signIn("cy", null), frontDoor)?.role, "no groups in the config")
        val readers = DashboardSsoConfig("a@b", readOnlyGroups = listOf("family"))
        val onlyReaders = DashboardSso("orc.test.example.net", readers)
        assertEquals(DashboardRole.READ_ONLY, onlyReaders.user(signIn("bo", "family"), frontDoor)?.role)
        assertEquals(DashboardRole.ADMIN, onlyReaders.user(signIn("ada", "users"), frontDoor)?.role)
    }

    @Test
    fun theTokenOrTheSignInGivesAUser() {
        val sso = DashboardSso("orc.test.example.net", ssoConfig)
        val auth = DashboardAuth("s3cret", null, { frontDoor }, { sso })
        val none = signIn(null, null)
        assertEquals(DashboardUser.TOKEN_USER, auth.user("s3cret", "203.0.113.9", none)?.name)
        assertEquals(DashboardRole.READ_ONLY, auth.user(null, "203.0.113.9", signIn("bo", "family"))?.role)
        // The token gives full access, also to a user who can only look.
        assertEquals(DashboardRole.ADMIN, auth.user("s3cret", "203.0.113.9", signIn("bo", "family"))?.role)
        // An old token in the browser does not count against a user who signed in.
        repeat(DashboardAuth.MAX_FAILURES + 5) { auth.user("old", "203.0.113.9", signIn("ada", "admins")) }
        assertFalse(auth.blocked("203.0.113.9"))
        // A request with no token and no sign-in is not a wrong token.
        repeat(DashboardAuth.MAX_FAILURES + 5) { assertNull(auth.user(null, "203.0.113.10", none)) }
        assertFalse(auth.blocked("203.0.113.10"))
        repeat(DashboardAuth.MAX_FAILURES + 1) { auth.user("wrong", "203.0.113.11", none) }
        assertTrue(auth.blocked("203.0.113.11"))
        val tokenOnly = DashboardAuth("s3cret", null)
        assertNull(tokenOnly.user(null, "203.0.113.9", signIn("ada", "admins")), "no sign-in in the config")
    }

    @Test
    fun aUserWhoCanOnlyLookGetsNoChange() = testApplication {
        val echo = object : RoutingController {
            override fun init(parent: Route) {
                parent.get("/look") { call.respond(HttpStatusCode.OK) }
                parent.post("/change") { call.respond(HttpStatusCode.OK) }
            }
        }
        // The test host gives its own address as the sender, so each address is the front door here.
        val everyone = object : AbstractSet<String>() {
            override val size = 1
            override fun contains(element: String) = true
            override fun iterator() = listOf("any").iterator()
        }
        val sso = DashboardSso("orc.test.example.net", ssoConfig)
        val routes = RoutingControllers(setOf(echo), DashboardAuth("s3cret", null, { everyone }, { sso }), emptySet())
        application { with(DashboardServer(routes, testConfig())) { module() } }
        fun HttpRequestBuilder.as_(user: String, groups: String) {
            header(HttpHeaders.Host, "orc.test.example.net")
            header("Remote-User", user)
            header("Remote-Groups", groups)
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/look") { as_("bo", "family") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/change") { as_("bo", "family") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/change") { as_("ada", "admins") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/change") { as_("cy", "users") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/look").status)
        assertEquals(HttpStatusCode.OK, client.post("/api/change") { bearerAuth("s3cret") }.status)
    }
}
