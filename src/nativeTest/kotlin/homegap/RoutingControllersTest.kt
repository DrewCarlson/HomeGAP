package homegap

import homegap.config.ConfigLoader
import homegap.dashboard.DashboardAuth
import homegap.dashboard.ShareLinks
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
        application { RoutingControllers(controllers, DashboardAuth("s3cret", null), emptySet(), ShareLinks(tmpPath("links"))).init(this) }
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
        application { RoutingControllers(controllers, DashboardAuth.resolve(config), setOf(open), ShareLinks(tmpPath("links"))).init(this) }
        assertEquals("own check", client.get("/api/open").bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/one").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/one") { bearerAuth("wrong") }.status)
        assertEquals("one", client.get("/api/one") { bearerAuth("s3cret") }.bodyAsText())
        Files.delete(tokenFile)
    }

    @Test
    fun aShareLinkOnlyReads() = testApplication {
        val tokenFile = tmpPath("token")
        Files.writeAtomic(tokenFile, "s3cret\n")
        val config = ConfigLoader.parse("$TEST_CONFIG\ndashboard:\n  auth_token_file: $tokenFile\n")
        val links = ShareLinks(tmpPath("links"))
        val (link, viewer) = links.create(label = "Friend", ttlMillis = null)
        val routes = setOf(
            object : RoutingController {
                override fun init(parent: Route) {
                    parent.get("/one") { call.respondText("one") }
                    parent.post("/one") { call.respondText("changed") }
                }
            },
            object : RoutingController {
                override val adminOnly: Boolean get() = true

                override fun init(parent: Route) {
                    parent.get("/people") { call.respondText("people") }
                }
            },
            object : RoutingController {
                override val adminOnly: Boolean get() = true

                override fun init(parent: Route) {
                    parent.get("/links") { call.respondText("links") }
                }
            },
        )
        application { RoutingControllers(routes, DashboardAuth.resolve(config), emptySet(), links).init(this) }

        assertEquals("one", client.get("/api/one") { bearerAuth(viewer) }.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/one") { bearerAuth(viewer) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/people") { bearerAuth(viewer) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/links") { bearerAuth(viewer) }.status)
        // The admin keeps every route, and the admin-only group adds nothing to the paths.
        assertEquals("changed", client.post("/api/one") { bearerAuth("s3cret") }.bodyAsText())
        assertEquals("people", client.get("/api/people") { bearerAuth("s3cret") }.bodyAsText())

        links.revoke(link.id)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/one") { bearerAuth(viewer) }.status)
        Files.delete(tokenFile)
    }
}
