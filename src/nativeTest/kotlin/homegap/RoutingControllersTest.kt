package homegap

import homegap.config.ConfigLoader
import homegap.dashboard.DashboardAuth
import homegap.platform.Files
import homegap.routes.OpenRoutingController
import homegap.routes.RoutingController
import homegap.routes.RoutingControllers
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
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
        val config = ConfigLoader.parse(TEST_CONFIG.replace("/tmp/homegap-test", dir))
        val first = DashboardAuth.resolve(config)
        assertEquals(DashboardAuth.tokenFile(config), first.generatedFile)
        assertEquals(40, first.token.length)
        // The next start, and the CLI, use the same token.
        assertEquals(first.token, DashboardAuth.resolve(config).token)
        assertEquals(first.token, DashboardAuth.current(config))
        // A token named in the config but not available does not open the API either.
        val named = ConfigLoader.parse("${TEST_CONFIG.replace("/tmp/homegap-test", dir)}\ndashboard:\n  auth_token_file: $dir/missing\n")
        assertEquals(first.token, DashboardAuth.resolve(named).token)
        Files.delete(DashboardAuth.tokenFile(config))
    }

    @Test
    fun requiresTheDashboardTokenWhenOneIsSet() = testApplication {
        val tokenFile = tmpPath("token")
        Files.writeAtomic(tokenFile, "s3cret\n")
        val config = ConfigLoader.parse("$TEST_CONFIG\ndashboard:\n  auth_token_file: $tokenFile\n")
        // A route with its own check stays reachable without the token; the others do not.
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
    fun theFrontDoorNamesTheClientOfARequest() {
        val auth = DashboardAuth("s3cret", null) { setOf("172.32.0.2") }
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
}
