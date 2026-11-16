package homegap.identity

import homegap.api.ActionResponse
import homegap.api.ApiJson
import homegap.config.AppConfig
import homegap.dashboard.DashboardAuth
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/** The API of the dashboard under [prefix], for a command in the orchestrator container. */
class DashboardApi(private val config: AppConfig, private val prefix: String) {
    private val base: String
        @Suppress("HttpUrlsUsage") // The dashboard is on the loopback of this container.
        get() {
            val host = config.dashboard.host.let { if (it == "0.0.0.0" || it.isEmpty()) "127.0.0.1" else it }
            return "http://$host:${config.dashboard.port}$prefix"
        }

    /** Returns the body of the answer. Throws with the message of the API if the call fails. */
    suspend fun call(http: HttpClient, method: HttpMethod, path: String, body: String? = null): String {
        val token = DashboardAuth.current(config)
        val response = http.request(base + path) {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        val text = response.bodyAsText()
        check(response.status.isSuccess()) {
            runCatching { ApiJson.decodeFromString<ActionResponse>(text).message }.getOrDefault(text)
        }
        return text
    }
}
