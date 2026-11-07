package homegap.clients

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

class PangolinApiException(val status: Int, message: String) : Exception(message)

private val lenient = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

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
data class PangolinDomain(val domainId: String, val baseDomain: String, val verified: Boolean? = null, val type: String? = null)

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

    open suspend fun ping(): Boolean = runCatching { http.get("$baseUrl/") { auth() }.status.isSuccess() }.getOrDefault(false)

    open suspend fun getOrg(orgId: String): JsonElement = get("/org/$orgId")

    open suspend fun listDomains(orgId: String): List<PangolinDomain> =
        lenient.decodeFromJsonElement(get("/org/$orgId/domains").jsonObject["domains"] ?: JsonArray(emptyList()))

    open suspend fun listSites(orgId: String): List<PangolinSite> =
        lenient.decodeFromJsonElement(get("/org/$orgId/sites?pageSize=1000").jsonObject["sites"] ?: JsonArray(emptyList()))

    open suspend fun pickSiteDefaults(orgId: String): SiteDefaults = lenient.decodeFromJsonElement(get("/org/$orgId/pick-site-defaults"))

    open suspend fun createNewtSite(orgId: String, name: String, niceId: String, d: SiteDefaults): PangolinSite {
        val body = buildJsonObject {
            put("name", name); put("niceId", niceId); put("type", "newt"); put("exitNodeId", d.exitNodeId)
            d.subnet?.let { put("subnet", it) }
            d.clientAddress?.let { put("address", it) }
            put("newtId", d.newtId); put("secret", d.newtSecret)
        }
        return lenient.decodeFromJsonElement(send(HttpMethod.Put, "/org/$orgId/site", body))
    }

    /** Rotates a newt site's secret (used when controller state was lost). Returns the newtId. */
    open suspend fun regenerateNewtSecret(siteId: Int, secret: String): String? {
        val data = send(HttpMethod.Post, "/re-key/$siteId/regenerate-site-secret", buildJsonObject {
            put("type", "newt"); put("secret", secret); put("disconnect", true)
        })
        return (data as? JsonObject)?.get("newtId")?.jsonPrimitive?.contentOrNull
    }

    open suspend fun applyBlueprint(orgId: String, blueprint: JsonObject) {
        val b64 = Base64.encode(blueprint.toString().encodeToByteArray())
        send(HttpMethod.Put, "/org/$orgId/blueprint", buildJsonObject { put("blueprint", b64) })
    }

    open suspend fun listResources(orgId: String): List<PangolinResource> =
        lenient.decodeFromJsonElement(get("/org/$orgId/resources?pageSize=1000").jsonObject["resources"] ?: JsonArray(emptyList()))

    open suspend fun deleteResource(resourceId: Int) {
        send(HttpMethod.Delete, "/resource/$resourceId", null)
    }

    open suspend fun listSiteResources(orgId: String): List<PangolinSiteResource> {
        val data = get("/org/$orgId/site-resources?pageSize=1000").jsonObject
        val arr = data["siteResources"] ?: data["resources"] ?: JsonArray(emptyList())
        return lenient.decodeFromJsonElement(arr)
    }

    open suspend fun deleteSiteResource(siteResourceId: Int) {
        send(HttpMethod.Delete, "/site-resource/$siteResourceId", null)
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
        call(HttpMethod.Get, "/auth/initial-setup-complete").pangolinData().jsonObject["complete"]?.jsonPrimitive?.booleanOrNull ?: false

    suspend fun setServerAdmin(email: String, password: String, setupToken: String) {
        call(HttpMethod.Put, "/auth/set-server-admin", buildJsonObject {
            put("email", email); put("password", password); put("setupToken", setupToken)
        }).pangolinData()
    }

    suspend fun login(email: String, password: String) {
        val resp = call(HttpMethod.Post, "/auth/login", buildJsonObject { put("email", email); put("password", password) })
        resp.pangolinData()
        session = resp.setCookie().firstOrNull { it.name == "p_session_token" }?.value
            ?: resp.headers.getAll(HttpHeaders.SetCookie)?.firstNotNullOfOrNull { h ->
                h.split(';').first().trim().takeIf { it.startsWith("p_session_token=") }?.substringAfter('=')
            }
            ?: throw PangolinApiException(resp.status.value, "login did not return a session cookie")
    }

    suspend fun orgExists(orgId: String): Boolean = runCatching { call(HttpMethod.Get, "/org/$orgId").pangolinData(); true }.getOrDefault(false)

    suspend fun createOrg(orgId: String, name: String) {
        val defaults = call(HttpMethod.Get, "/pick-org-defaults").pangolinData().jsonObject
        call(HttpMethod.Put, "/org", buildJsonObject {
            put("orgId", orgId); put("name", name)
            put("subnet", defaults["subnet"]!!); put("utilitySubnet", defaults["utilitySubnet"]!!)
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
            o["apiKeyId"]!!.jsonPrimitive.content to (o["name"]?.jsonPrimitive?.contentOrNull ?: "")
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

    suspend fun licenseKeys(): JsonElement = call(HttpMethod.Get, "/license/keys").pangolinData()

    companion object {
        /** Least-privilege action set the orchestrator's per-node key needs. */
        val ORCHESTRATOR_ACTIONS = listOf(
            "getOrg", "listOrgs", "listOrgDomains", "getDomain",
            "createSite", "getSite", "listSites", "updateSite", "deleteSite", "reGenerateSecret", "createNewt",
            "createResource", "deleteResource", "getResource", "listResources", "updateResource",
            "createTarget", "deleteTarget", "getTarget", "listTargets", "updateTarget",
            "createSiteResource", "deleteSiteResource", "getSiteResource", "listSiteResources", "updateSiteResource",
            "listRoles", "getRole", "setResourceRoles", "listResourceRoles", "setResourceUsers", "listResourceUsers",
            "setResourcePincode", "applyBlueprint", "listBlueprints", "getBlueprint",
            "listHealthChecks", "getCertificate", "restartCertificate",
        )
    }
}
