package homegap.clients

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

class PangolinApiException(val status: Int, message: String) : Exception(message)

private val lenient = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

/** Decodes Pangolin's `{data, success, error, message, status}` envelope. */
internal suspend fun HttpResponse.pangolinData(): JsonElement {
    val text = bodyAsText()
    val obj = runCatching { lenient.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: throw PangolinApiException(status.value, "HTTP ${status.value}: ${text.take(300)}")
    val success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: status.isSuccess()
    if (!success || !status.isSuccess()) {
        throw PangolinApiException(status.value, obj["message"]?.jsonPrimitive?.contentOrNull ?: "HTTP ${status.value}")
    }
    return obj["data"] ?: JsonNull
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
    /** The site's WireGuard address block inside its exit node's subnet, e.g. 100.89.128.4/30. */
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

@Serializable
data class PangolinDomain(
    val domainId: String,
    val baseDomain: String,
    val verified: Boolean? = null,
    val type: String? = null
)

/**
 * Pangolin Integration API client (`/v1`, Bearer `<apiKeyId>.<secret>`).
 * Node instances are reached through an SSH port-forward to the node's loopback-only API.
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

    open suspend fun ping(): Boolean = runCatching {
        http.get("$baseUrl/") { auth() }.status.isSuccess()
    }.onFailure {
        if (it is CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }.getOrDefault(false)

    open suspend fun getOrg(orgId: String): JsonElement = get("/org/$orgId")

    open suspend fun listDomains(orgId: String): List<PangolinDomain> =
        lenient.decodeFromJsonElement(get("/org/$orgId/domains").jsonObject["domains"] ?: JsonArray(emptyList()))

    open suspend fun listSites(orgId: String): List<PangolinSite> =
        lenient.decodeFromJsonElement(
            get("/org/$orgId/sites?pageSize=1000").jsonObject["sites"] ?: JsonArray(emptyList())
        )

    open suspend fun pickSiteDefaults(orgId: String): SiteDefaults =
        lenient.decodeFromJsonElement(get("/org/$orgId/pick-site-defaults"))

    open suspend fun createNewtSite(orgId: String, name: String, niceId: String, d: SiteDefaults): PangolinSite {
        val body = buildJsonObject {
            put("name", name)
            put("niceId", niceId)
            put("type", "newt")
            put("exitNodeId", d.exitNodeId)
            d.subnet?.let { put("subnet", it) }
            d.clientAddress?.let { put("address", it) }
            put("newtId", d.newtId)
            put("secret", d.newtSecret)
        }
        return lenient.decodeFromJsonElement(send(HttpMethod.Put, "/org/$orgId/site", body))
    }

    /** Rotates a newt site's secret (used when controller state was lost). Returns the newtId. */
    open suspend fun regenerateNewtSecret(siteId: Int, secret: String): String? {
        val data = send(HttpMethod.Post, "/re-key/$siteId/regenerate-site-secret", buildJsonObject {
            put("type", "newt")
            put("secret", secret)
            put("disconnect", true)
        })
        return (data as? JsonObject)?.get("newtId")?.jsonPrimitive?.contentOrNull
    }

    open suspend fun applyBlueprint(orgId: String, blueprint: JsonObject) {
        val b64 = Base64.encode(blueprint.toString().encodeToByteArray())
        send(HttpMethod.Put, "/org/$orgId/blueprint", buildJsonObject { put("blueprint", b64) })
    }

    open suspend fun listResources(orgId: String): List<PangolinResource> =
        lenient.decodeFromJsonElement(
            get("/org/$orgId/resources?pageSize=1000").jsonObject["resources"] ?: JsonArray(
                emptyList()
            )
        )

    open suspend fun deleteResource(resourceId: Int) {
        send(HttpMethod.Delete, "/resource/$resourceId", null)
    }

    open suspend fun listSiteResources(orgId: String): List<PangolinSiteResource> {
        val data = get("/org/$orgId/site-resources?pageSize=1000").jsonObject
        val arr = data["siteResources"] ?: data["resources"] ?: JsonArray(emptyList())
        return lenient.decodeFromJsonElement(arr)
    }

    open suspend fun deleteSite(siteId: Int) {
        send(HttpMethod.Delete, "/site/$siteId", null)
    }

    open suspend fun deleteSiteResource(siteResourceId: Int) {
        send(HttpMethod.Delete, "/site-resource/$siteResourceId", null)
    }

    // ------------------------------------------------------------------ users and devices

    open suspend fun listRoles(orgId: String): List<PangolinRole> =
        lenient.decodeFromJsonElement(
            get("/org/$orgId/roles?pageSize=1000").jsonObject["roles"] ?: JsonArray(emptyList())
        )

    open suspend fun listUsers(orgId: String): List<PangolinUser> =
        lenient.decodeFromJsonElement(
            get("/org/$orgId/users?pageSize=1000").jsonObject["users"] ?: JsonArray(emptyList())
        )

    open suspend fun listInvitations(orgId: String): List<PangolinInvitation> =
        lenient.decodeFromJsonElement(
            get("/org/$orgId/invitations?limit=1000").jsonObject["invitations"] ?: JsonArray(
                emptyList()
            )
        )

    /** Invites [email] (no email is sent) and returns the invite link; [regenerate] replaces an open invite. */
    open suspend fun createInvite(
        orgId: String,
        email: String,
        roleIds: List<Int>,
        validHours: Int,
        regenerate: Boolean
    ): String {
        val data = send(HttpMethod.Post, "/org/$orgId/create-invite", buildJsonObject {
            put("email", email)
            putJsonArray("roleIds") { roleIds.forEach { add(it) } }
            put("validHours", validHours)
            put("sendEmail", false)
            put("regenerate", regenerate)
        })
        return data.jsonObject["inviteLink"]?.jsonPrimitive?.contentOrNull ?: throw PangolinApiException(
            500,
            "the invite has no link"
        )
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
        lenient.decodeFromJsonElement(
            get("/org/$orgId/clients?pageSize=1000").jsonObject["clients"] ?: JsonArray(
                emptyList()
            )
        )

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
            session?.let { header(HttpHeaders.Cookie, "p_session_token=$it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }

    suspend fun initialSetupComplete(): Boolean =
        call(
            HttpMethod.Get,
            "/auth/initial-setup-complete"
        ).pangolinData().jsonObject["complete"]?.jsonPrimitive?.booleanOrNull ?: false

    suspend fun setServerAdmin(email: String, password: String, setupToken: String) {
        call(HttpMethod.Put, "/auth/set-server-admin", buildJsonObject {
            put("email", email)
            put("password", password)
            put("setupToken", setupToken)
        }).pangolinData()
    }

    suspend fun login(email: String, password: String) {
        val resp = call(
            method = HttpMethod.Post,
            path = "/auth/login",
            body = buildJsonObject {
                put("email", email)
                put("password", password)
            }
        )
        resp.pangolinData()
        session = resp.setCookie().firstOrNull { it.name == "p_session_token" }?.value
            ?: resp.headers.getAll(HttpHeaders.SetCookie)?.firstNotNullOfOrNull { h ->
                h.split(';').first().trim().takeIf { it.startsWith("p_session_token=") }?.substringAfter('=')
            }
                    ?: throw PangolinApiException(resp.status.value, "login did not return a session cookie")
    }

    suspend fun orgExists(orgId: String): Boolean = runCatching {
        call(HttpMethod.Get, "/org/$orgId").pangolinData()
        true
    }.onFailure {
        if (it is CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }.getOrDefault(false)

    suspend fun createOrg(orgId: String, name: String) {
        val defaults = call(HttpMethod.Get, "/pick-org-defaults").pangolinData().jsonObject
        call(HttpMethod.Put, "/org", buildJsonObject {
            put("orgId", orgId)
            put("name", name)
            put("subnet", defaults["subnet"]!!)
            put("utilitySubnet", defaults["utilitySubnet"]!!)
        }).pangolinData()
    }

    /** Returns (apiKeyId, secret). */
    suspend fun createRootApiKey(name: String): Pair<String, String> {
        val d = call(HttpMethod.Put, "/api-key", buildJsonObject { put("name", name) }).pangolinData().jsonObject
        return d["apiKeyId"]!!.jsonPrimitive.content to d["apiKey"]!!.jsonPrimitive.content
    }

    suspend fun listRootApiKeys(): List<Pair<String, String>> {
        val d = call(HttpMethod.Get, "/api-keys").pangolinData().jsonObject
        return (d["apiKeys"] as? JsonArray).orEmpty().map {
            val o = it.jsonObject
            o["apiKeyId"]!!.jsonPrimitive.content to (o["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }

    suspend fun deleteRootApiKey(id: String) {
        call(HttpMethod.Delete, "/api-key/$id").pangolinData()
    }

    suspend fun setApiKeyActions(apiKeyId: String, actions: List<String>) {
        call(HttpMethod.Post, "/api-key/$apiKeyId/actions", buildJsonObject {
            put("actionIds", JsonArray(actions.map { JsonPrimitive(it) }))
        }).pangolinData()
    }

    suspend fun activateLicense(key: String) {
        call(HttpMethod.Post, "/license/activate", buildJsonObject { put("licenseKey", key) }).pangolinData()
    }

    /**
     * Completes an invite with a password: creates the user (and a session for it), then accepts
     * the invite so the user joins the org with the invite's roles. [inviteToken] is the invite
     * link's `token` parameter (`<inviteId>-<token>`).
     */
    suspend fun signUpWithInvite(email: String, password: String, inviteToken: String) {
        val inviteId = inviteToken.substringBefore('-')
        val token = inviteToken.substringAfter('-')
        val resp = call(HttpMethod.Put, "/auth/signup", buildJsonObject {
            put("email", email)
            put("password", password)
            put("inviteId", inviteId)
            put("inviteToken", token)
            put("skipVerificationEmail", true)
        })
        resp.pangolinData()
        val userSession = resp.setCookie().firstOrNull { it.name == "p_session_token" }?.value
            ?: throw PangolinApiException(resp.status.value, "signup did not return a session")
        val previous = session
        session = userSession
        try {
            call(
                HttpMethod.Post,
                "/invite/accept",
                buildJsonObject {
                    put("inviteId", inviteId)
                    put("token", token)
                }
            ).pangolinData()
        } finally {
            session = previous
        }
    }

    suspend fun licenseKeys(): JsonElement = call(HttpMethod.Get, "/license/keys").pangolinData()

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
