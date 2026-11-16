package homegap.signin

import homegap.VERSION
import homegap.identity.Keys
import homegap.identity.Signed
import homegap.identity.User
import homegap.platform.Crypto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The calls of the Pangolin apps after the sign-in, with the paths and the answers that the apps
 * expect. HomeGAP has one organisation, so each answer about organisations is the same for each
 * user. The calls about the device give it its id and secret, and the token for its control
 * connection.
 */
class AppApi(private val signIn: SignIn, private val devices: Devices) {
    private val logger = KotlinLogging.logger("sign-in")

    /** Adds the calls to [parent], which is the route `/api/v1`. */
    fun init(parent: Route) = with(parent) {
        get("/server-info") { call.withUser { serverInfo() } }
        get("/user/{user}/orgs") { call.withUser { organisations() } }
        get("/org/{org}") { call.withUser { answer(buildJsonObject { put("org", organisation()) }) } }
        get("/org/{org}/user/{user}/check") { call.withUser { answer(buildJsonObject { put("allowed", true) }) } }
        get("/org/{org}/site-resources") {
            call.withUser { answer(buildJsonObject { putJsonArray("siteResources") {} }) }
        }
        post("/user/{user}/olm/recover") { call.withUser { fail(HttpStatusCode.NotFound, "No device to recover") } }
        put("/user/{user}/olm") { call.withUser { createDevice(it) } }
        get("/user/{user}/olm/{olm}") { call.withUser { showDevice(it) } }
        post("/auth/olm/get-token") { call.token() }
    }

    /** Runs [block] for the user of the session. The id in the path, if there is one, must be that user. */
    private suspend fun ApplicationCall.withUser(block: suspend ApplicationCall.(User) -> Unit) {
        val user = sessionToken()?.let { signIn.user(it) } ?: return fail(HttpStatusCode.Unauthorized, "Unauthorized")
        val named = parameters["user"]
        if (named != null && named != user.id) return fail(HttpStatusCode.Forbidden, "Not your user")
        val org = parameters["org"]
        if (org != null && org != ORG_ID) return fail(HttpStatusCode.NotFound, "No such organisation")
        block(user)
    }

    private suspend fun ApplicationCall.serverInfo() = answer(
        buildJsonObject {
            put("version", "homegap-$VERSION")
            put("build", "enterprise")
            put("supporterStatusValid", true)
            put("enterpriseLicenseValid", true)
            put("enterpriseLicenseType", "homegap")
        },
    )

    private fun organisation(): JsonObject = buildJsonObject {
        put("orgId", ORG_ID)
        put("name", ORG_NAME)
    }

    private suspend fun ApplicationCall.organisations() = answer(
        buildJsonObject {
            put(
                "orgs",
                buildJsonArray {
                    add(JsonObject(organisation() + ("isOwner" to JsonPrimitive(false))))
                },
            )
            putJsonObject("pagination") {
                put("total", 1)
                put("page", 1)
                put("pageSize", PAGE_SIZE)
            }
        },
    )

    private suspend fun ApplicationCall.createDevice(user: User) {
        val body = body()
        val name = body.text("name").orEmpty()
        val created = runCatching { devices.create(user.id, name) }
            .getOrElse { return fail(HttpStatusCode.TooManyRequests, it.message ?: "Too many devices") }
        val (device, secret) = created
        val detail = "'${device.name}' (${device.id}) with the address ${device.address}"
        logger.info { "${user.email} added the device $detail" }
        answer(
            buildJsonObject {
                put("olmId", device.id)
                put("secret", secret)
                put("name", device.name)
            },
        )
    }

    private suspend fun ApplicationCall.showDevice(user: User) {
        val device = devices.get(parameters["olm"].orEmpty())?.takeIf { it.userId == user.id }
            ?: return fail(HttpStatusCode.NotFound, "Unknown device")
        answer(
            buildJsonObject {
                put("olmId", device.id)
                put("userId", device.userId)
                put("name", device.name)
            },
        )
    }

    /**
     * Gives a device the token for its control connection. A machine proves itself with its
     * secret. A device of a user also needs a session of that user.
     */
    private suspend fun ApplicationCall.token() {
        val body = body()
        val id = body.text("olmId").orEmpty()
        val secret = body.text("secret").orEmpty()
        val users = signIn.users() ?: return fail(HttpStatusCode.ServiceUnavailable, "The sign-in is not ready")
        val machine = users.machine(id)
        val device = when {
            machine == null -> userDevice(id, secret, body.text("userToken"))
            machine.blocked || !Crypto.constantTimeEquals(machine.secretHash, Keys.fingerprint(secret)) -> null
            else -> devices.machine(machine.id, machine.name)
        }
        if (device == null) {
            logger.warn { "refused a token for the device '${id.take(MAX_LOGGED)}'" }
            return fail(HttpStatusCode.Unauthorized, "Unauthorized")
        }
        answer(
            buildJsonObject {
                put("token", signIn.tunnelToken(device))
                putJsonArray("exitNodes") {}
                put("serverVersion", "homegap-$VERSION")
            },
        )
    }

    private fun userDevice(id: String, secret: String, session: String?): Device? {
        val device = devices.verify(id, secret) ?: return null
        val user = session?.let { signIn.user(it) } ?: return null
        return device.takeIf { it.userId == user.id }
    }

    private suspend fun ApplicationCall.body(): JsonObject {
        val text = receiveText().take(MAX_BODY)
        return runCatching { Signed.JSON.decodeFromString<JsonObject>(text) }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** HomeGAP has one organisation. The apps need an id and a name for it. */
        const val ORG_ID = "homegap"
        const val ORG_NAME = "HomeGAP"
        private const val PAGE_SIZE = 100
        private const val MAX_BODY = 8192
        private const val MAX_LOGGED = 40
    }
}
