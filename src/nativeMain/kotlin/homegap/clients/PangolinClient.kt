package homegap.clients

import homegap.platform.runCatchingCancellable
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

class PangolinApiException(val status: Int, message: String) : Exception(message)

private val lenient = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

private const val ERROR_BODY_LENGTH = 300
private const val SESSION_COOKIE = "p_session_token"

/** Decodes Pangolin's `{data, success, error, message, status}` envelope. */
internal suspend fun HttpResponse.pangolinData(): JsonElement {
    val text = bodyAsText()
    val envelope = runCatching { lenient.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: throw PangolinApiException(status.value, "HTTP ${status.value}: ${text.take(ERROR_BODY_LENGTH)}")
    val success = envelope["success"]?.jsonPrimitive?.booleanOrNull ?: status.isSuccess()
    if (!success || !status.isSuccess()) {
        val message = envelope["message"]?.jsonPrimitive?.contentOrNull ?: "HTTP ${status.value}"
        throw PangolinApiException(status.value, message)
    }
    return envelope["data"] ?: JsonNull
}

@Serializable
data class PangolinTarget(
    val targetId: Int? = null,
    val siteId: Int? = null,
    val ip: String? = null,
    val port: Int? = null,
    val enabled: Boolean? = null,
    val healthStatus: String? = null,
    val siteNiceId: String? = null,
    val siteOnline: Boolean? = null,
)

@Serializable
data class PangolinResource(
    val resourceId: Int,
    val niceId: String,
    val name: String? = null,
    val fullDomain: String? = null,
    val mode: String? = null,
    val proxyPort: Int? = null,
    val ssl: Boolean? = null,
    val sso: JsonElement? = null,
    val wildcard: Boolean? = null,
    val enabled: Boolean? = null,
    val health: String? = null,
    val targets: List<PangolinTarget> = emptyList(),
)

@Serializable
data class PangolinSite(
    val siteId: Int,
    val niceId: String,
    val name: String? = null,
    val online: Boolean? = null,
    val type: String? = null,
    val exitNodeId: Int? = null,
    /** The site's WireGuard address block inside its exit node's subnet, for example 100.89.128.4/30. */
    val subnet: String? = null,
    val newtVersion: String? = null,
    val megabytesIn: Double? = null,
    val megabytesOut: Double? = null,
)

@Serializable
data class PangolinSiteResource(
    val siteResourceId: Int,
    val niceId: String,
    val name: String? = null,
    val alias: String? = null,
    val destination: String? = null,
)

@Serializable
data class SiteDefaults(
    val exitNodeId: Int,
    val address: String? = null,
    val subnet: String? = null,
    val clientAddress: String? = null,
    val newtId: String,
    val newtSecret: String,
    val endpoint: String? = null,
)

@Serializable
data class PangolinRole(val roleId: Int, val name: String, val isAdmin: Boolean? = null)

@Serializable
data class PangolinUserRole(val roleId: Int, val roleName: String? = null)

@Serializable
data class PangolinUser(
    val id: String,
    val email: String? = null,
    val username: String,
    val name: String? = null,
    val type: String? = null,
    val isOwner: Boolean? = null,
    val twoFactorEnabled: Boolean? = null,
    val roles: List<PangolinUserRole> = emptyList(),
)

@Serializable
data class PangolinInvitation(
    val inviteId: String,
    val email: String,
    val expiresAt: Long? = null,
    val roles: List<PangolinUserRole> = emptyList(),
)

/** A machine client or a user's device, as Pangolin lists them. */
@Serializable
data class PangolinClientEntry(
    val clientId: Int,
    val name: String? = null,
    val niceId: String? = null,
    val type: String? = null,
    val online: Boolean? = null,
    val archived: Boolean? = null,
    val blocked: Boolean? = null,
    val userId: String? = null,
    val userEmail: String? = null,
    val username: String? = null,
    val agent: String? = null,
    val olmVersion: String? = null,
    val deviceModel: String? = null,
    val platform: String? = null,
    /** Only on a single client's record: the exit node (node) it relays through. */
    val exitNodeId: Int? = null,
    val lastPing: Long? = null,
)

/**
 * Pangolin Integration API client (`/v1`, Bearer `<apiKeyId>.<secret>`). The orchestrator reaches
 * the instance of a node through an SSH port-forward to the loopback-only API of the node.
 */
open class PangolinClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) {
    private fun HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer $apiKey")
        accept(ContentType.Application.Json)
    }

    private suspend fun get(path: String): JsonElement = http.get("$baseUrl$path") { auth() }.pangolinData()

    private suspend fun send(method: HttpMethod, path: String, body: JsonElement?): JsonElement =
        http.request("$baseUrl$path") {
            this.method = method
            auth()
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }.pangolinData()

    /** The array [key] in the answer to a GET of [path], or an empty array. */
    private suspend fun getArray(path: String, key: String): JsonElement =
        get(path).jsonObject[key] ?: JsonArray(emptyList())

    open suspend fun ping(): Boolean = runCatchingCancellable {
        http.get("$baseUrl/") { auth() }.status.isSuccess()
    }.getOrDefault(false)

    open suspend fun getOrg(orgId: String): JsonElement = get("/org/$orgId")

    open suspend fun listSites(orgId: String): List<PangolinSite> =
        lenient.decodeFromJsonElement(getArray("/org/$orgId/sites?pageSize=1000", "sites"))

    open suspend fun pickSiteDefaults(orgId: String): SiteDefaults =
        lenient.decodeFromJsonElement(get("/org/$orgId/pick-site-defaults"))

    open suspend fun createNewtSite(
        orgId: String,
        name: String,
        niceId: String,
        defaults: SiteDefaults,
    ): PangolinSite {
        val body = buildJsonObject {
            put("name", name)
            put("niceId", niceId)
            put("type", "newt")
            put("exitNodeId", defaults.exitNodeId)
            defaults.subnet?.let { put("subnet", it) }
            defaults.clientAddress?.let { put("address", it) }
            put("newtId", defaults.newtId)
            put("secret", defaults.newtSecret)
        }
        return lenient.decodeFromJsonElement(send(HttpMethod.Put, "/org/$orgId/site", body))
    }

    /** Rotates the secret of a newt site, for a controller that lost its state. Returns the newtId. */
    open suspend fun regenerateNewtSecret(siteId: Int, secret: String): String? {
        val body = buildJsonObject {
            put("type", "newt")
            put("secret", secret)
            put("disconnect", true)
        }
        val data = send(HttpMethod.Post, "/re-key/$siteId/regenerate-site-secret", body)
        return (data as? JsonObject)?.get("newtId")?.jsonPrimitive?.contentOrNull
    }

    open suspend fun applyBlueprint(orgId: String, blueprint: JsonObject) {
        val encoded = Base64.encode(blueprint.toString().encodeToByteArray())
        send(HttpMethod.Put, "/org/$orgId/blueprint", buildJsonObject { put("blueprint", encoded) })
    }

    open suspend fun listResources(orgId: String): List<PangolinResource> =
        lenient.decodeFromJsonElement(getArray("/org/$orgId/resources?pageSize=1000", "resources"))

    open suspend fun deleteResource(resourceId: Int) {
        send(HttpMethod.Delete, "/resource/$resourceId", null)
    }

    open suspend fun listSiteResources(orgId: String): List<PangolinSiteResource> {
        val data = get("/org/$orgId/site-resources?pageSize=1000").jsonObject
        val resources = data["siteResources"] ?: data["resources"] ?: JsonArray(emptyList())
        return lenient.decodeFromJsonElement(resources)
    }

    open suspend fun deleteSite(siteId: Int) {
        send(HttpMethod.Delete, "/site/$siteId", null)
    }

    open suspend fun deleteSiteResource(siteResourceId: Int) {
        send(HttpMethod.Delete, "/site-resource/$siteResourceId", null)
    }

    // ------------------------------------------------------------------ users and devices

    open suspend fun listRoles(orgId: String): List<PangolinRole> =
        lenient.decodeFromJsonElement(getArray("/org/$orgId/roles?pageSize=1000", "roles"))

    open suspend fun listUsers(orgId: String): List<PangolinUser> =
        lenient.decodeFromJsonElement(getArray("/org/$orgId/users?pageSize=1000", "users"))

    open suspend fun listInvitations(orgId: String): List<PangolinInvitation> =
        lenient.decodeFromJsonElement(getArray("/org/$orgId/invitations?limit=1000", "invitations"))

    /**
     * Invites [email] and returns the invite link. Pangolin sends no email. [regenerate] replaces
     * an open invite.
     */
    open suspend fun createInvite(
        orgId: String,
        email: String,
        roleIds: List<Int>,
        validHours: Int,
        regenerate: Boolean,
    ): String {
        val body = buildJsonObject {
            put("email", email)
            putJsonArray("roleIds") { roleIds.forEach { add(it) } }
            put("validHours", validHours)
            put("sendEmail", false)
            put("regenerate", regenerate)
        }
        val data = send(HttpMethod.Post, "/org/$orgId/create-invite", body)
        return data.jsonObject["inviteLink"]?.jsonPrimitive?.contentOrNull
            ?: throw PangolinApiException(NO_INVITE_LINK_STATUS, "the invite has no link")
    }

    open suspend fun removeInvitation(orgId: String, inviteId: String) {
        send(HttpMethod.Delete, "/org/$orgId/invitations/$inviteId", null)
    }

    open suspend fun removeUser(orgId: String, userId: String) {
        send(HttpMethod.Delete, "/org/$orgId/user/$userId", null)
    }

    open suspend fun addUserRole(userId: String, roleId: Int) {
        send(HttpMethod.Post, "/user/$userId/add-role/$roleId", null)
    }

    open suspend fun removeUserRole(userId: String, roleId: Int) {
        send(HttpMethod.Delete, "/user/$userId/remove-role/$roleId", null)
    }

    open suspend fun listClients(orgId: String): List<PangolinClientEntry> =
        lenient.decodeFromJsonElement(getArray("/org/$orgId/clients?pageSize=1000", "clients"))

    open suspend fun listUserDevices(orgId: String): List<PangolinClientEntry> {
        val data = get("/org/$orgId/user-devices?pageSize=1000").jsonObject
        return lenient.decodeFromJsonElement(data["devices"] ?: data["clients"] ?: JsonArray(emptyList()))
    }

    open suspend fun getClient(clientId: Int): PangolinClientEntry =
        lenient.decodeFromJsonElement(get("/client/$clientId"))

    /** `archive`, `unarchive`, `block` or `unblock`. */
    open suspend fun clientAction(clientId: Int, action: String) {
        require(action in CLIENT_ACTIONS) { "unknown device action $action" }
        send(HttpMethod.Post, "/client/$clientId/$action", null)
    }

    open suspend fun deleteClient(clientId: Int) {
        send(HttpMethod.Delete, "/client/$clientId", null)
    }

    companion object {
        val CLIENT_ACTIONS = setOf("archive", "unarchive", "block", "unblock")
        private const val NO_INVITE_LINK_STATUS = 500
    }
}

/**
 * The dashboard API (`/api/v1`, session cookie) used only for headless bootstrap of a fresh
 * node: first admin, org, root API key, license activation.
 */
class PangolinBootstrapClient(private val http: HttpClient, private val baseUrl: String) {
    private var session: String? = null

    private suspend fun call(method: HttpMethod, path: String, body: JsonElement? = null): HttpResponse =
        http.request("$baseUrl$path") {
            this.method = method
            header("X-CSRF-Token", "x-csrf-protection")
            session?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }

    suspend fun initialSetupComplete(): Boolean {
        val data = call(HttpMethod.Get, "/auth/initial-setup-complete").pangolinData()
        return data.jsonObject["complete"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    suspend fun setServerAdmin(email: String, password: String, setupToken: String) {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            put("setupToken", setupToken)
        }
        call(HttpMethod.Put, "/auth/set-server-admin", body).pangolinData()
    }

    suspend fun login(email: String, password: String) {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }
        val response = call(HttpMethod.Post, "/auth/login", body)
        response.pangolinData()
        session = response.setCookie().firstOrNull { it.name == SESSION_COOKIE }?.value
            ?: sessionInRawCookies(response)
            ?: throw PangolinApiException(response.status.value, "login did not return a session cookie")
    }

    /** Reads the session from the text of the `Set-Cookie` headers. */
    private fun sessionInRawCookies(response: HttpResponse): String? =
        response.headers.getAll(HttpHeaders.SetCookie)?.firstNotNullOfOrNull { header ->
            header.split(';')
                .first()
                .trim()
                .takeIf { it.startsWith("$SESSION_COOKIE=") }
                ?.substringAfter('=')
        }

    suspend fun orgExists(orgId: String): Boolean = runCatchingCancellable {
        call(HttpMethod.Get, "/org/$orgId").pangolinData()
        true
    }.getOrDefault(false)

    suspend fun createOrg(orgId: String, name: String) {
        val defaults = call(HttpMethod.Get, "/pick-org-defaults").pangolinData().jsonObject
        val body = buildJsonObject {
            put("orgId", orgId)
            put("name", name)
            put("subnet", defaults["subnet"]!!)
            put("utilitySubnet", defaults["utilitySubnet"]!!)
        }
        call(HttpMethod.Put, "/org", body).pangolinData()
    }

    /** Returns (apiKeyId, secret). */
    suspend fun createRootApiKey(name: String): Pair<String, String> {
        val body = buildJsonObject { put("name", name) }
        val key = call(HttpMethod.Put, "/api-key", body).pangolinData().jsonObject
        return key["apiKeyId"]!!.jsonPrimitive.content to key["apiKey"]!!.jsonPrimitive.content
    }

    suspend fun listRootApiKeys(): List<Pair<String, String>> {
        val data = call(HttpMethod.Get, "/api-keys").pangolinData().jsonObject
        return (data["apiKeys"] as? JsonArray).orEmpty().map {
            val key = it.jsonObject
            key["apiKeyId"]!!.jsonPrimitive.content to (key["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }

    suspend fun deleteRootApiKey(id: String) {
        call(HttpMethod.Delete, "/api-key/$id").pangolinData()
    }

    suspend fun setApiKeyActions(apiKeyId: String, actions: List<String>) {
        val body = buildJsonObject {
            put("actionIds", JsonArray(actions.map { JsonPrimitive(it) }))
        }
        call(HttpMethod.Post, "/api-key/$apiKeyId/actions", body).pangolinData()
    }

    suspend fun activateLicense(key: String) {
        call(HttpMethod.Post, "/license/activate", buildJsonObject { put("licenseKey", key) }).pangolinData()
    }

    /**
     * Completes an invite with a password. Creates the user and a session for it. Then accepts
     * the invite, so the user joins the org with the roles of the invite. [inviteToken] is the
     * `token` parameter of the invite link (`<inviteId>-<token>`).
     */
    suspend fun signUpWithInvite(email: String, password: String, inviteToken: String) {
        val inviteId = inviteToken.substringBefore('-')
        val token = inviteToken.substringAfter('-')
        val signup = buildJsonObject {
            put("email", email)
            put("password", password)
            put("inviteId", inviteId)
            put("inviteToken", token)
            put("skipVerificationEmail", true)
        }
        val accept = buildJsonObject {
            put("inviteId", inviteId)
            put("token", token)
        }
        val response = call(HttpMethod.Put, "/auth/signup", signup)
        response.pangolinData()
        val userSession = response.setCookie().firstOrNull { it.name == SESSION_COOKIE }?.value
            ?: throw PangolinApiException(response.status.value, "signup did not return a session")
        val previous = session
        session = userSession
        try {
            call(HttpMethod.Post, "/invite/accept", accept).pangolinData()
        } finally {
            session = previous
        }
    }

    /** Makes a running instance re-read the license (activated through another cluster node). */
    suspend fun recheckLicense() {
        call(HttpMethod.Post, "/license/recheck").pangolinData()
    }

    companion object {
        /** Least-privilege action set the orchestrator's per-node key needs. */
        val ORCHESTRATOR_ACTIONS = listOf(
            "getOrg",
            "listOrgs",
            "listOrgDomains",
            "getDomain",
            "createSite",
            "getSite",
            "listSites",
            "updateSite",
            "deleteSite",
            "reGenerateSecret",
            "createNewt",
            "createResource",
            "deleteResource",
            "getResource",
            "listResources",
            "updateResource",
            "createTarget",
            "deleteTarget",
            "getTarget",
            "listTargets",
            "updateTarget",
            "createSiteResource",
            "deleteSiteResource",
            "getSiteResource",
            "listSiteResources",
            "updateSiteResource",
            "listRoles",
            "getRole",
            "setResourceRoles",
            "listResourceRoles",
            "setResourceUsers",
            "listResourceUsers",
            "setResourcePincode",
            "applyBlueprint",
            "listBlueprints",
            "getBlueprint",
            "listHealthChecks",
            "getCertificate",
            "restartCertificate",
            // Users and devices, managed from the dashboard.
            "listUsers",
            "getOrgUser",
            "inviteUser",
            "listInvitations",
            "removeInvitation",
            "removeUser",
            "addUserRole",
            "removeUserRole",
            "listClients",
            "getClient",
            "archiveClient",
            "unarchiveClient",
            "blockClient",
            "unblockClient",
            "deleteClient",
        )
    }
}
