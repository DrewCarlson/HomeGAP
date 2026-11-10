package homegap

import homegap.config.ConfigLoader
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
        application { RoutingControllers(controllers, ConfigLoader.parse(TEST_CONFIG), emptySet()).init(this) }
        assertEquals("one", client.get("/api/one").bodyAsText())
        assertEquals("two", client.get("/api/two").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/one").status)
    }

    @Test
    fun requiresTheDashboardTokenWhenOneIsSet() = testApplication {
        val tokenFile = tmpPath("token")
        Files.writeAtomic(tokenFile, "s3cret\n")
        val config = ConfigLoader.parse("$TEST_CONFIG\ndashboard:\n  auth_token_file: $tokenFile\n")
        // A route with its own check stays reachable without the token; the others do not.
        val open = object : OpenRoutingController {
            override fun init(parent: Route, token: String?) {
                parent.get("/api/open") { call.respondText(if (token == "s3cret") "own check" else "no token") }
            }
        }
        application { RoutingControllers(controllers, config, setOf(open)).init(this) }
        assertEquals("own check", client.get("/api/open").bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/one").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/one") { bearerAuth("wrong") }.status)
        assertEquals("one", client.get("/api/one") { bearerAuth("s3cret") }.bodyAsText())
        Files.delete(tokenFile)
    }
}
