package homegap.signin

import homegap.config.OidcConfig
import homegap.identity.Keys
import homegap.identity.Signed
import homegap.platform.Crypto
import homegap.platform.nowMillis
import homegap.platform.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration.Companion.minutes

/**
 * Sign-in through an OpenID Connect provider, with the authorization code flow and PKCE.
 *
 * The browser goes to the provider and comes back with a code. HomeGAP gives the code and its
 * client secret to the provider and gets the ID token over TLS, direct from the provider. For
 * that reason HomeGAP does not check the signature of the token: the standard permits this for a
 * token that comes this way. HomeGAP checks the issuer, the audience, the time and the nonce.
 *
 * [redirect] is the address of HomeGAP that the provider sends the browser back to.
 */
class Oidc(
    private val config: OidcConfig,
    private val secret: String,
    private val redirect: String,
    private val http: HttpClient,
    private val now: () -> Long = ::nowMillis,
) {
    private val lock = SynchronizedObject()
    private val started = HashMap<String, Started>()
    private var endpoints: Endpoints? = null

    private class Endpoints(val issuer: String, val authorization: String, val token: String, val userinfo: String?)

    /** A sign-in that is at the provider. [device] is the device code that it is for. */
    private class Started(val device: String, val nonce: String, val verifier: String, val at: Long)

    /** What the provider says about a user: the address that it proved, the name and the groups. */
    data class Person(val email: String, val name: String = "", val groups: List<String> = emptyList())

    /** The settings of the provider. */
    val settings: OidcConfig get() = config

    sealed interface Result {
        /** The provider says that the user is [person]. [device] is the device code. */
        data class Proven(val device: String, val person: Person) : Result

        /** [device] is null if HomeGAP does not know which sign-in this was. */
        data class Failed(val device: String?, val reason: String) : Result
    }

    /** The address of the provider for the browser, for a sign-in of the device code [device]. */
    suspend fun begin(device: String): String {
        val provider = endpoints()
        val state = Crypto.token(STATE_LENGTH)
        val entry = Started(device, Crypto.token(STATE_LENGTH), Crypto.token(VERIFIER_LENGTH), now())
        synchronized(lock) {
            started.entries.removeAll { it.value.at < now() - LIFETIME.inWholeMilliseconds }
            check(started.size < MAX_STARTED) { "too many sign-ins wait at the provider" }
            started[state] = entry
        }
        return URLBuilder(provider.authorization).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", redirect)
            parameters.append("scope", config.requestedScopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("nonce", entry.nonce)
            parameters.append("code_challenge", Keys.fingerprint(entry.verifier))
            parameters.append("code_challenge_method", "S256")
        }.buildString()
    }

    /** Ends the sign-in that the browser comes back from, with the [state] and the [code] of the provider. */
    suspend fun finish(state: String, code: String?, error: String?): Result {
        val entry = synchronized(lock) { started.remove(state) }
            ?.takeIf { it.at >= now() - LIFETIME.inWholeMilliseconds }
            ?: return Result.Failed(null, "This sign-in is not known, or it took too long.")
        if (code.isNullOrEmpty()) {
            return Result.Failed(entry.device, "The provider refused the sign-in (${error ?: "no code"}).")
        }
        val proven = runCatchingCancellable { person(entry, code) }
        return proven.fold(
            onSuccess = { Result.Proven(entry.device, it) },
            onFailure = { Result.Failed(entry.device, it.message ?: "The provider gave no valid answer.") },
        )
    }

    private suspend fun person(entry: Started, code: String): Person {
        val provider = endpoints()
        val response = http.submitForm(
            url = provider.token,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirect)
                append("code_verifier", entry.verifier)
            },
        ) { basicAuth(config.clientId, secret) }
        val tokens = json(response, "the token call")
        val claims = claims(tokens.text("id_token") ?: error("The provider gave no ID token."))
        check(claims.text("iss") == provider.issuer) { "The ID token is from another issuer." }
        check(config.clientId in claims.texts("aud")) { "The ID token is for another client." }
        check((claims.number("exp") ?: 0) * MILLIS > now()) { "The ID token has expired." }
        check(claims.text("nonce") == entry.nonce) { "The ID token is for another sign-in." }
        val subject = claims.text("sub") ?: error("The ID token names no user.")
        // Authelia gives the address, the name and the groups only at the user info endpoint, not in the token.
        val complete = claims.text("email") != null && (!config.reportsUsers || wanted().all { it in claims })
        val person = if (complete) claims else JsonObject(claims + userinfo(provider, tokens, subject))
        check((person["email_verified"] as? JsonPrimitive)?.booleanOrNull != false) {
            "The provider did not verify the email address of this user."
        }
        return Person(
            email = person.text("email") ?: error("The provider gave no email address. Add the scope 'email'."),
            name = person.text("name").orEmpty(),
            groups = person.texts(config.groupsClaim),
        )
    }

    /** The claims that HomeGAP needs besides the address when it takes its users from the provider. */
    private fun wanted(): List<String> = listOfNotNull("name", config.groupsClaim.takeIf { config.managesRoles })

    private suspend fun userinfo(provider: Endpoints, tokens: JsonObject, subject: String): JsonObject {
        val url = provider.userinfo ?: error("The provider gave no email address and has no user info endpoint.")
        val access = tokens.text("access_token") ?: error("The provider gave no access token.")
        val person = json(http.get(url) { bearerAuth(access) }, "the user info call")
        check(person.text("sub") == subject) { "The user info is for another user." }
        return person
    }

    private suspend fun endpoints(): Endpoints {
        synchronized(lock) { endpoints }?.let { return it }
        val issuer = config.issuer.trimEnd('/')
        val document = json(http.get("$issuer/.well-known/openid-configuration"), "the settings of the provider")
        val named = document.text("issuer")
        check(named?.trimEnd('/') == issuer) { "The provider names another issuer than ${config.issuer}." }
        val loaded = Endpoints(
            issuer = named,
            authorization = document.text("authorization_endpoint")
                ?: error("The provider has no authorization endpoint."),
            token = document.text("token_endpoint") ?: error("The provider has no token endpoint."),
            userinfo = document.text("userinfo_endpoint"),
        )
        check(listOf(loaded.authorization, loaded.token).all { it.startsWith("https://") }) {
            "The provider gives an endpoint that is not HTTPS."
        }
        synchronized(lock) { endpoints = loaded }
        return loaded
    }

    private suspend fun json(response: HttpResponse, what: String): JsonObject {
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "The provider refused $what (${response.status.value})." }
        return runCatching { Signed.JSON.decodeFromString<JsonObject>(body) }
            .getOrElse { error("The provider gave no JSON for $what.") }
    }

    /** The claims of the ID token [token], without a check of its signature (see the class). */
    private fun claims(token: String): JsonObject {
        val payload = token.split('.').getOrNull(1)?.let { Keys.decode(it.trimEnd('=')) }
        val text = payload?.decodeToString() ?: error("The ID token of the provider is not readable.")
        return runCatching { Signed.JSON.decodeFromString<JsonObject>(text) }
            .getOrElse { error("The ID token of the provider is not readable.") }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    /** A claim that is one text or a list of texts. */
    private fun JsonObject.texts(name: String): List<String> = when (val value = this[name]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        else -> emptyList()
    }

    companion object {
        const val CALLBACK_PATH = "/auth/oidc/callback"
        private val LIFETIME = 10.minutes
        private const val MAX_STARTED = 1000
        private const val STATE_LENGTH = 32
        private const val VERIFIER_LENGTH = 64
        private const val MILLIS = 1000
    }
}
