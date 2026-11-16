package homegap

import homegap.config.OidcConfig
import homegap.identity.Keys
import homegap.signin.Oidc
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OidcTest {
    private val issuer = "https://auth.example.net"
    private val redirect = "https://vpn.example.net/auth/oidc/callback"

    /** A provider that answers as Authelia does: the address is at the user info endpoint only. */
    private class Provider(val issuer: String) {
        var claims: (nonce: String) -> String = { nonce ->
            """{"iss":"https://auth.example.net","aud":["homegap"],"exp":2000,"sub":"u1","nonce":"$nonce"}"""
        }
        var userinfo = """{"sub":"u1","email":"ada@example.net","email_verified":true}"""
        var nonce = ""
        var tokenForm: Map<String, String> = emptyMap()
        var tokenAuthorization: String? = null

        val http = HttpClient(MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(
                    """{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize",
                        "token_endpoint":"$issuer/token","userinfo_endpoint":"$issuer/userinfo"}""",
                    headers = json,
                )
                "/token" -> {
                    val form = (request.body as FormDataContent).formData
                    tokenForm = form.names().associateWith { form[it]!! }
                    tokenAuthorization = request.headers[HttpHeaders.Authorization]
                    val token = "h.${Keys.encode(claims(nonce).encodeToByteArray())}.s"
                    respond("""{"id_token":"$token","access_token":"at"}""", headers = json)
                }
                "/userinfo" -> respond(userinfo, headers = json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
    }

    private fun oidc(provider: Provider) =
        Oidc(OidcConfig(issuer, "homegap"), "secret", redirect, provider.http) { 1_000_000 }

    /** Starts a sign-in and returns the state that the provider sends back. */
    private suspend fun begin(oidc: Oidc, provider: Provider): String {
        val url = Url(oidc.begin("ABCD2345"))
        assertEquals("$issuer/authorize", "${url.protocol.name}://${url.host}${url.encodedPath}")
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals(redirect, url.parameters["redirect_uri"])
        provider.nonce = url.parameters["nonce"]!!
        return url.parameters["state"]!!
    }

    @Test
    fun theProviderProvesTheAddressOfTheUser() = runTest {
        val provider = Provider(issuer)
        val oidc = oidc(provider)
        val state = begin(oidc, provider)
        val result = oidc.finish(state, "code-1", null)
        assertEquals(Oidc.Result.Proven("ABCD2345", Oidc.Person("ada@example.net")), result)
        assertEquals("authorization_code", provider.tokenForm["grant_type"])
        assertEquals("code-1", provider.tokenForm["code"])
        assertTrue(provider.tokenForm["code_verifier"]!!.length >= 43)
        assertTrue(provider.tokenAuthorization!!.startsWith("Basic "))
        // A state works once.
        assertNull((oidc.finish(state, "code-1", null) as Oidc.Result.Failed).device)
    }

    @Test
    fun theProviderGivesTheNameAndTheGroupsOfTheUser() = runTest {
        val provider = Provider(issuer)
        provider.userinfo = """{"sub":"u1","email":"ada@example.net","name":"Ada L","groups":["family","admins"]}"""
        val config = OidcConfig(issuer, "homegap", groupRoles = mapOf("admins" to listOf("Admin")))
        val oidc = Oidc(config, "secret", redirect, provider.http) { 1_000_000 }
        val url = Url(oidc.begin("ABCD2345"))
        assertTrue("groups" in url.parameters["scope"]!!.split(' '), "the groups need their scope")
        provider.nonce = url.parameters["nonce"]!!
        val proven = assertIs<Oidc.Result.Proven>(oidc.finish(url.parameters["state"]!!, "code", null))
        assertEquals(Oidc.Person("ada@example.net", "Ada L", listOf("family", "admins")), proven.person)
    }

    @Test
    fun aTokenForAnotherSignInIsRefused() = runTest {
        val provider = Provider(issuer)
        val oidc = oidc(provider)
        val cases = listOf<(String) -> String>(
            { _ -> """{"iss":"$issuer","aud":"homegap","exp":2000,"sub":"u1","nonce":"other"}""" },
            { nonce -> """{"iss":"$issuer","aud":"other","exp":2000,"sub":"u1","nonce":"$nonce"}""" },
            { nonce -> """{"iss":"https://evil.example","aud":"homegap","exp":2000,"sub":"u1","nonce":"$nonce"}""" },
            { nonce -> """{"iss":"$issuer","aud":"homegap","exp":999,"sub":"u1","nonce":"$nonce"}""" },
            { nonce -> """{"iss":"$issuer","aud":"homegap","exp":2000,"sub":"u2","nonce":"$nonce"}""" },
        )
        for (claims in cases) {
            provider.claims = claims
            val failed = assertIs<Oidc.Result.Failed>(oidc.finish(begin(oidc, provider), "code", null))
            assertEquals("ABCD2345", failed.device)
        }
    }

    @Test
    fun anAddressThatIsNotVerifiedIsRefused() = runTest {
        val provider = Provider(issuer)
        provider.userinfo = """{"sub":"u1","email":"ada@example.net","email_verified":false}"""
        val oidc = oidc(provider)
        assertIs<Oidc.Result.Failed>(oidc.finish(begin(oidc, provider), "code", null))
    }

    @Test
    fun aRefusalOfTheProviderAndAnUnknownStateFail() = runTest {
        val provider = Provider(issuer)
        val oidc = oidc(provider)
        val refused = assertIs<Oidc.Result.Failed>(oidc.finish(begin(oidc, provider), null, "access_denied"))
        assertTrue("access_denied" in refused.reason)
        assertNull(assertIs<Oidc.Result.Failed>(oidc.finish("no-such-state", "code", null)).device)
    }
}
