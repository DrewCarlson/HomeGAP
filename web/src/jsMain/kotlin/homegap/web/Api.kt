package homegap.web

import homegap.api.*
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.json

object Api {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Optional bearer token (when the controller sets dashboard.auth_token_*), kept in localStorage. */
    var token: String?
        get() = localStorage.getItem("homegap.token")
        set(v) = if (v.isNullOrBlank()) localStorage.removeItem("homegap.token") else localStorage.setItem("homegap.token", v)

    private fun headers(body: Boolean) = json().apply {
        this["Accept"] = "application/json"
        if (body) this["Content-Type"] = "application/json"
        token?.let { this["Authorization"] = "Bearer $it" }
    }

    class ApiError(val status: Int, message: String) : Exception(message)

    private suspend fun request(method: String, path: String, body: String? = null): String {
        val init = RequestInit(method = method, headers = headers(body != null), body = body)
        val resp: Response = window.fetch("/api$path", init).await()
        val text = resp.text().await()
        if (!resp.ok) {
            val msg = runCatching { json.decodeFromString(ActionResponse.serializer(), text).message }.getOrNull() ?: "HTTP ${resp.status}"
            throw ApiError(resp.status.toInt(), msg)
        }
        return text
    }

    suspend fun status(): StatusSnapshot = json.decodeFromString(StatusSnapshot.serializer(), request("GET", "/status"))
    suspend fun log(domain: String?): LogResponse =
        json.decodeFromString(LogResponse.serializer(), request("GET", "/log?limit=500" + (domain?.let { "&domain=$it" } ?: "")))
    suspend fun diff(): DiffResponse = json.decodeFromString(DiffResponse.serializer(), request("GET", "/diff"))
    suspend fun secrets(): SecretsResponse = json.decodeFromString(SecretsResponse.serializer(), request("GET", "/secrets"))
    suspend fun config(): ConfigResponse = json.decodeFromString(ConfigResponse.serializer(), request("GET", "/config"))

    private suspend fun action(method: String, path: String, body: String? = null): ActionResponse =
        json.decodeFromString(ActionResponse.serializer(), request(method, path, body))

    suspend fun resync() = action("POST", "/resync")
    suspend fun provision(node: String) = action("POST", "/nodes/$node/provision")
    suspend fun pause(scope: String, id: String?, paused: Boolean) =
        action("POST", "/pause", json.encodeToString(PauseRequest.serializer(), PauseRequest(scope, id, paused)))
    suspend fun addNode(n: NodeConfigDto) = action("POST", "/nodes", json.encodeToString(NodeConfigDto.serializer(), n))
    suspend fun removeNode(id: String) = action("DELETE", "/nodes/$id")
    suspend fun decommission(id: String, confirm: String) = action("POST", "/nodes/$id/decommission?confirm=$confirm")
}
