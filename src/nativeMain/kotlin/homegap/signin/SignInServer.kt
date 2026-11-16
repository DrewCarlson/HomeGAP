package homegap.signin

import homegap.config.OidcConfig
import homegap.config.VpnConfig
import homegap.identity.Directory
import homegap.identity.IdentityStore
import homegap.identity.ProviderUser
import homegap.identity.ProviderUsers
import homegap.identity.Signed
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.platform.runCatchingCancellable
import homegap.platform.watchDirectory
import homegap.provision.Mesh
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * `homegap sign-in`: the public sign-in of the homelab, in a container of its own. It has no SSH
 * key, no DNS key and no Docker socket. It reads the signed directory of users from
 * [identityDir] and keeps its own key in [dir].
 *
 * It serves the calls of the Pangolin apps for the sign-in of a device, with the paths and the
 * answers that the apps expect, and the page where the user signs in.
 */
class SignInServer(
    private val dir: String,
    private val identityDir: String,
    setup: Setup = Setup(),
    private val role: Role = Role(),
) {
    /** How the server signs users in, and the VPN that it gives the devices. */
    class Setup(
        val options: SignInPage.Options = SignInPage.Options(),
        val oidc: Oidc? = null,
        val vpn: VpnConfig = VpnConfig(),
        /** The folder shared with the VPN peer, and the name that the devices connect to. With null, no VPN. */
        val tunnel: Pair<String, String>? = null,
    )

    /**
     * What makes the server of a node different from the server of the homelab. A node signs its
     * sessions with a key that the directory of the homelab does not name, so they give access to
     * the node only. It accepts them itself ([ownSessions]).
     */
    class Role(
        val issuer: String = SignIn.ISSUER,
        val ownSessions: Boolean = false,
        val pool: Devices.Pool? = null,
        /** The gateway of the server, from the source of its directory. With null, the gateway of the homelab. */
        val gateway: ((() -> Directory?) -> TunnelGateway)? = null,
    )

    private val options = setup.options
    private val oidc = setup.oidc
    private val vpn = setup.vpn
    private val logger = KotlinLogging.logger("sign-in")

    @Volatile
    private var directory: Directory? = null

    /** The version of the directory that this server has. It changes after each new directory. */
    private val directoryVersion = MutableStateFlow(0L)
    private val signIn = SignIn(dir, { directory }, issuer = role.issuer)
    private val vpnDir = setup.tunnel?.first
    private val devices = Devices(
        dir = dir,
        subnet = vpn.clientSubnet,
        // The nodes get the list of the devices of the homelab through the folder of the VPN.
        pool = role.pool ?: Devices.Pool(mirror = vpnDir?.let { "$it/${Mesh.VPN_DEVICES_FILE}" }),
    )
    private val gateway: TunnelGateway = when {
        role.gateway != null -> role.gateway.invoke { directory }
        setup.tunnel != null -> HomeTunnel(setup.tunnel.first, setup.tunnel.second) { directory }
        else -> NoTunnels(Devices.serverAddress(vpn.clientSubnet))
    }
    private val appApi = AppApi(signIn, devices, gateway)
    private val control = ControlSocket(signIn, devices, gateway, vpn.utilitySubnet)

    init {
        // The orchestrator reads the public key here and puts it in the directory.
        Files.writeAtomic("$dir/${SignIn.PUBLIC_KEY_FILE}", signIn.publicKey + "\n")
    }

    suspend fun run(host: String, port: Int): Unit = coroutineScope {
        launch { watchDirectory(identityDir).collect { reload() } }
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
    fun reload() {
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
            val named = role.ownSessions || role.issuer in loaded.issuers
            val ready = if (named) "" else "; it does not name the key of this sign-in yet"
            logger.info { "directory version ${loaded.version}: ${loaded.users.size} user(s)$ready" }
        }
        // A node accepts its own sessions. No other server does, because only this copy names the key.
        directory = when {
            role.ownSessions -> loaded.copy(issuers = loaded.issuers + (role.issuer to signIn.publicKey))
            else -> loaded
        }
        directoryVersion.value = loaded.version
        val gone = devices.prune(loaded)
        if (gone.isNotEmpty()) logger.info { "removed ${gone.size} device(s) of users and machines that are gone" }
        gateway.refresh()
        // The nodes get the directory, with the signature of the homelab, through the folder of the VPN.
        vpnDir?.let { shared ->
            val copy = "$shared/${Mesh.VPN_DIRECTORY_FILE}"
            if (Files.readTextOrNull(copy) != text) Files.writeAtomic(copy, text)
            devices.publish()
        }
    }

    fun Application.module() {
        install(WebSockets) {
            pingPeriod = 30.seconds
            timeout = 90.seconds
            maxFrameSize = MAX_FRAME
        }
        routing {
            route("/api/v1") {
                appApi.init(this)
                control.init(this)
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
            get("/auth/oidc/start") { call.startProvider() }
            get(Oidc.CALLBACK_PATH) { call.finishProvider() }
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

    // ------------------------------------------------------------------ the page

    private suspend fun ApplicationCall.showForm() {
        val code = request.queryParameters["code"]
        val pending = code?.let { signIn.pending(it) }
        when {
            code.isNullOrBlank() -> page(SignInPage.askCode())
            pending == null -> page(SignInPage.askCode(UNKNOWN_CODE), HttpStatusCode.NotFound)
            else -> page(form(code, pending))
        }
    }

    private fun ApplicationCall.form(code: String, pending: SignIn.Pending, problem: String? = null): String =
        SignInPage.form(SignInPage.Request(code.trim().uppercase(), pending, address()), options, problem)

    /** The code that waits, if the request has the secret of its page. */
    private fun pendingOf(code: String, form: String?): SignIn.Pending? =
        signIn.pending(code)?.takeIf { Crypto.constantTimeEquals(it.form, form.orEmpty()) }

    private suspend fun ApplicationCall.submitForm() {
        val form = receiveParameters()
        val code = form["code"].orEmpty()
        val pending = pendingOf(code, form["form"])
        if (pending == null || !options.passwords) {
            return page(SignInPage.askCode(UNKNOWN_CODE), HttpStatusCode.NotFound)
        }
        val email = form["email"].orEmpty()
        val outcome = signIn.signIn(code, email, form["password"].orEmpty(), address())
        if (outcome is SignIn.Outcome.WrongPassword) {
            logger.warn { "failed sign-in for '${email.take(MAX_LOGGED)}' from ${address()}" }
        }
        show(outcome, code, pending, "a password")
    }

    private suspend fun ApplicationCall.startProvider() {
        val code = request.queryParameters["code"].orEmpty()
        val pending = pendingOf(code, request.queryParameters["form"])
        if (pending == null || oidc == null) {
            return page(SignInPage.askCode(UNKNOWN_CODE), HttpStatusCode.NotFound)
        }
        val address = runCatchingCancellable { oidc.begin(code) }.getOrElse { e ->
            logger.error { "the sign-in provider is not usable: ${e.message}" }
            val problem = "The sign-in provider does not answer. Try again in a moment."
            return page(form(code, pending, problem), HttpStatusCode.BadGateway)
        }
        respondRedirect(address)
    }

    /**
     * Tells the homelab what the provider said about [person], and waits until the directory has
     * the user with that answer. The homelab makes the user or gives it the roles of its groups.
     * Returns false if the person gets no access: no role, or the homelab gave no answer.
     */
    private suspend fun report(oidc: OidcConfig, person: Oidc.Person): Boolean {
        if (!oidc.reportsUsers) return true
        val existing = directory?.userByEmail(person.email)
        val roles = oidc.rolesFor(person.groups)
        if (roles.isEmpty() && (oidc.managesRoles || existing == null)) return false
        if (existing == null && !oidc.createUsers) return true
        val at = nowMillis()
        ProviderUsers.record(dir, ProviderUser(person.email, person.name, person.groups, at))
        val taken = withTimeoutOrNull(REPORT_TIMEOUT) {
            directoryVersion.first { directory?.userByEmail(person.email)?.providerAt == at }
        }
        if (taken == null) logger.error { "the homelab did not take the user of the provider in time" }
        return taken != null
    }

    private suspend fun ApplicationCall.finishProvider() {
        val query = request.queryParameters
        val provider = oidc ?: return page(SignInPage.askCode(UNKNOWN_CODE), HttpStatusCode.NotFound)
        when (val result = provider.finish(query["state"].orEmpty(), query["code"], query["error"])) {
            is Oidc.Result.Proven -> {
                val pending = signIn.pending(result.device)
                    ?: return page(SignInPage.askCode(UNKNOWN_CODE), HttpStatusCode.NotFound)
                val outcome = when {
                    report(provider.settings, result.person) -> signIn.signInProven(result.device, result.person.email)
                    else -> SignIn.Outcome.NoSuchUser
                }
                if (outcome is SignIn.Outcome.NoSuchUser) {
                    val email = result.person.email.take(MAX_LOGGED)
                    logger.warn { "the provider proved '$email', who has no user or no role in HomeGAP" }
                }
                show(outcome, result.device, pending, "the provider")
            }
            is Oidc.Result.Failed -> {
                logger.warn { "sign-in through the provider failed: ${result.reason}" }
                val pending = result.device?.let { signIn.pending(it) }
                when (pending) {
                    null -> page(SignInPage.askCode(result.reason), HttpStatusCode.BadRequest)
                    else -> page(form(result.device, pending, result.reason), HttpStatusCode.BadRequest)
                }
            }
        }
    }

    /** The page for the [outcome] of a sign-in for [code]. [how] names the way of the sign-in in the log. */
    private suspend fun ApplicationCall.show(
        outcome: SignIn.Outcome,
        code: String,
        pending: SignIn.Pending,
        how: String,
    ) {
        when (outcome) {
            is SignIn.Outcome.SignedIn -> {
                val device = pending.device
                logger.info { "${outcome.user.email} signed in with $how for the device '$device' from ${address()}" }
                val nonce = Crypto.token(NONCE_LENGTH)
                page(SignInPage.approved(outcome.user.email, nonce), nonce = nonce)
            }
            SignIn.Outcome.WrongPassword -> {
                val problem = "The email or the password is not correct."
                page(form(code, pending, problem), HttpStatusCode.Unauthorized)
            }
            SignIn.Outcome.NoSuchUser -> {
                val problem = "This account has no access to HomeGAP. Ask your admin."
                page(form(code, pending, problem), HttpStatusCode.Forbidden)
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

    private companion object {
        const val UNKNOWN_CODE = "This code is not known, or it expired."
        const val MAX_BODY = 4096
        const val MAX_FRAME = 64L * 1024
        const val MAX_LOGGED = 80

        /** How long a sign-in waits for the homelab to take the user of the provider. */
        val REPORT_TIMEOUT = 15.seconds
        const val NONCE_LENGTH = 24
    }
}
