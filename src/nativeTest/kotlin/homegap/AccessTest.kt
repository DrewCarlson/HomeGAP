package homegap

import homegap.clients.PangolinBootstrapClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccessTest {
    @Test
    fun signUpCompletesTheInviteWithTheNewUsersSession() = runTest {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = (request.body as? TextContent)?.text.orEmpty()
            val cookie = request.headers[HttpHeaders.Cookie]
            calls += "${request.method.value} ${request.url.encodedPath} cookie=$cookie $body"
            val ok = """{"data":{},"success":true,"error":false,"message":"ok","status":200}"""
            val signUpHeaders = headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.SetCookie to listOf("p_session_token=user-session; Path=/; HttpOnly"),
            )
            when (request.url.encodedPath) {
                "/api/v1/auth/signup" -> respond(ok, HttpStatusCode.OK, signUpHeaders)
                else -> respond(ok, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = PangolinBootstrapClient(HttpClient(engine), "http://pangolin/api/v1")
        client.signUpWithInvite(email = "p@x.org", password = "long enough", inviteToken = "inv1-tok-en")

        val signup = calls[0]
        assertTrue(signup.startsWith("PUT /api/v1/auth/signup cookie=null"), signup)
        assertTrue(
            "\"inviteId\":\"inv1\"" in signup && "\"inviteToken\":\"tok-en\"" in signup,
            "the token after the first dash, dashes and all",
        )
        val accept = calls[1]
        val acceptBody = """{"inviteId":"inv1","token":"tok-en"}"""
        assertEquals("POST /api/v1/invite/accept cookie=p_session_token=user-session $acceptBody", accept)
    }
}
