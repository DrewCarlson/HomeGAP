package homegap.signin

import homegap.VERSION
import homegap.identity.Directory
import homegap.identity.IdentityStore
import homegap.identity.Signed
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.watchDirectory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile

/**
 * `homegap sign-in`: the public sign-in of the homelab, in a container of its own. It has no SSH
 * key, no DNS key and no Docker socket. It reads the signed directory of users from
 * [identityDir] and keeps its own key in [dir].
 *
 * It serves the calls of the Pangolin apps for the sign-in of a device, with the paths and the
 * answers that the apps expect, and the page where the user signs in.
 */
class SignInServer(private val dir: String, private val identityDir: String) {
    private val logger = KotlinLogging.logger("sign-in")

    @Volatile
    private var directory: Directory? = null
    private val signIn = SignIn(dir, { directory })

    suspend fun run(host: String, port: Int): Unit = coroutineScope {
        Files.writeAtomic("$dir/${SignIn.PUBLIC_KEY_FILE}", signIn.publicKey + "\n")
        launch { watchDirectory(identityDir).collect { load() } }
        embeddedServer(
            CIO,
            configure = {
                reuseAddress = true
                connector {
                    this.host = host
                    this.port = port
                }
            },
        ) { module() }.start(wait = false)
        logger.info { "sign-in listening on $host:$port; users from $identityDir" }
        awaitCancellation()
    }

    /** Takes the directory of [identityDir] if the homelab signed it and it is not older than the current one. */
    private fun load() {
        val key = Files.readTextOrNull("$identityDir/${IdentityStore.PUBLIC_KEY_FILE}")?.trim()
        val text = Files.readTextOrNull("$identityDir/${IdentityStore.DIRECTORY_FILE}")
        if (key == null || text == null) return
        val loaded = Signed.directory(text, key)
        if (loaded == null) {
            logger.error { "the directory in $identityDir has no valid signature; the users stay as they are" }
            return
        }
        val current = directory
        if (current != null && loaded.version < current.version) return
        if (current?.version != loaded.version) {
            val ready = if (SignIn.ISSUER in loaded.issuers) "" else "; it does not name the key of this sign-in yet"
            logger.info { "directory version ${loaded.version}: ${loaded.users.size} user(s)$ready" }
        }
        directory = loaded
    }

    fun Application.module() {
        routing {
            route("/api/v1") {
                get { call.answer(buildJsonObject { put("status", "healthy") }) }
                get("/") { call.answer(buildJsonObject { put("status", "healthy") }) }
                post("/auth/device-web-auth/start") { call.start() }
                get("/auth/device-web-auth/poll/{code}") { call.poll() }
                get("/user") { call.user() }
                post("/auth/logout") {
                    call.sessionToken()?.let { signIn.end(it) }
                    call.answer(JsonNull)
                }
            }
            get("/auth/login/device") { call.showForm() }
            post("/auth/login/device") { call.submitForm() }
        }
    }

    // ------------------------------------------------------------------ app calls

    private suspend fun ApplicationCall.start() {
        val text = receiveText().take(MAX_BODY)
        val body = runCatching { Signed.JSON.decodeFromString<JsonObject>(text) }.getOrNull()
        val device = body?.get("deviceName")?.jsonPrimitive?.content.orEmpty()
        val application = body?.get("applicationName")?.jsonPrimitive?.content.orEmpty()
        val code = signIn.start(device, application)
            ?: return fail(HttpStatusCode.TooManyRequests, "Too many devices wait for a sign-in")
        answer(
            buildJsonObject {
                put("code", code)
                put("expiresInSeconds", SignIn.CODE_LIFETIME.inWholeSeconds)
            },
        )
    }

    private suspend fun ApplicationCall.poll() {
        val code = parameters["code"].orEmpty()
        val token = signIn.collect(code)
        when {
            token != null -> answer(
                buildJsonObject {
                    put("verified", true)
                    put("token", token)
                },
            )
            signIn.pending(code) != null -> answer(buildJsonObject { put("verified", false) })
            else -> fail(HttpStatusCode.NotFound, "Unknown code")
        }
    }

    private suspend fun ApplicationCall.user() {
        val user = sessionToken()?.let { signIn.user(it) } ?: return fail(HttpStatusCode.Unauthorized, "Unauthorized")
        answer(
            buildJsonObject {
                put("userId", user.id)
                put("email", user.email)
                put("username", user.email)
                put("name", user.name.ifBlank { user.email })
                put("type", "internal")
                put("emailVerified", true)
                put("twoFactorEnabled", false)
                put("serverAdmin", IdentityStore.ADMIN_ROLE in user.roles)
            },
        )
    }

    private fun ApplicationCall.sessionToken(): String? = request.cookies[SESSION_COOKIE]?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ the page

    private suspend fun ApplicationCall.showForm() {
        val code = request.queryParameters["code"]
        val pending = code?.let { signIn.pending(it) }
        when {
            code.isNullOrBlank() -> page(SignInPage.askCode())
            pending == null -> page(SignInPage.askCode(UNKNOWN_CODE), HttpStatusCode.NotFound)
            else -> page(SignInPage.form(code.trim().uppercase(), pending, address()))
        }
    }

    private suspend fun ApplicationCall.submitForm() {
        val form = receiveParameters()
        val code = form["code"].orEmpty()
        val pending = signIn.pending(code)
        if (pending == null || !Crypto.constantTimeEquals(pending.form, form["form"].orEmpty())) {
            return page(SignInPage.askCode(UNKNOWN_CODE), HttpStatusCode.NotFound)
        }
        val email = form["email"].orEmpty()
        when (val outcome = signIn.signIn(code, email, form["password"].orEmpty(), address())) {
            is SignIn.Outcome.SignedIn -> {
                logger.info { "${outcome.user.email} signed in for the device '${pending.device}' from ${address()}" }
                val nonce = Crypto.token(NONCE_LENGTH)
                page(SignInPage.approved(outcome.user.email, nonce), nonce = nonce)
            }
            SignIn.Outcome.WrongPassword -> {
                logger.warn { "failed sign-in for '${email.take(MAX_LOGGED)}' from ${address()}" }
                val problem = "The email or the password is not correct."
                page(SignInPage.form(code, pending, address(), problem), HttpStatusCode.Unauthorized)
            }
            SignIn.Outcome.TooManyAttempts -> {
                val text = "Too many attempts. Try again in ${SignIn.FAILURE_WINDOW.inWholeMinutes} minutes."
                page(SignInPage.message("Sign in", text), HttpStatusCode.TooManyRequests)
            }
            SignIn.Outcome.UnknownCode -> page(SignInPage.askCode(UNKNOWN_CODE), HttpStatusCode.NotFound)
            SignIn.Outcome.NotReady -> {
                val text = "The sign-in is not ready. Try again in a moment."
                page(SignInPage.message("Sign in", text), HttpStatusCode.ServiceUnavailable)
            }
        }
    }

    /** The address of the browser. A node adds it to the header as the last entry. */
    private fun ApplicationCall.address(): String =
        request.headers["X-Forwarded-For"]?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.origin.remoteHost

    private suspend fun ApplicationCall.page(
        html: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        nonce: String? = null,
    ) {
        val script = nonce?.let { "script-src 'nonce-$it'; " }.orEmpty()
        val policy = "default-src 'none'; ${script}style-src 'unsafe-inline'; form-action 'self'; " +
            "base-uri 'none'; frame-ancestors 'none'"
        response.header("Content-Security-Policy", policy)
        response.header("X-Frame-Options", "DENY")
        response.header("X-Content-Type-Options", "nosniff")
        response.header("Referrer-Policy", "no-referrer")
        response.header("Cache-Control", "no-store")
        respondText(html, ContentType.Text.Html, status)
    }

    // ------------------------------------------------------------------ answers

    private suspend fun ApplicationCall.answer(data: JsonElement) = envelope(HttpStatusCode.OK, data, "ok")

    private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String) =
        envelope(status, JsonNull, message)

    /** The form of each answer of the Pangolin API. */
    private suspend fun ApplicationCall.envelope(status: HttpStatusCode, data: JsonElement, message: String) {
        val ok = status.value < HttpStatusCode.BadRequest.value
        val body = buildJsonObject {
            put("data", data)
            put("success", ok)
            put("error", !ok)
            put("message", message)
            put("status", status.value)
        }
        response.header("Cache-Control", "no-store")
        response.header("X-HomeGAP-Version", VERSION)
        respondText(body.toString(), ContentType.Application.Json, status)
    }

    private companion object {
        const val SESSION_COOKIE = "p_session_token"
        const val UNKNOWN_CODE = "This code is not known, or it expired."
        const val MAX_BODY = 4096
        const val MAX_LOGGED = 80
        const val NONCE_LENGTH = 24
    }
}
