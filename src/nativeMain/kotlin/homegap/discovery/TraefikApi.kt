package homegap.discovery

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An HTTP router as Traefik's API reports it (from any of its providers). */
@Serializable
data class TraefikRouter(
    val name: String,
    val rule: String = "",
    /** Qualified with their provider, e.g. `private-only@file`. */
    val middlewares: List<String> = emptyList(),
    val status: String = "enabled",
)

/** Reads the routers a Traefik has loaded, from its API (`api.insecure` or a router to `api@internal`). */
class TraefikApi(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun routers(baseUrl: String): List<TraefikRouter> = withTimeout(10_000) {
        val out = mutableListOf<TraefikRouter>()
        var page = 1
        while (true) {
            val resp = http.get("${baseUrl.trimEnd('/')}/api/http/routers") {
                parameter("per_page", 500)
                parameter("page", page)
            }
            check(resp.status.value in 200..299) { "Traefik API ${resp.status.value}: ${resp.bodyAsText().take(200)}" }
            out += json.decodeFromString<List<TraefikRouter>>(resp.bodyAsText())
            // Traefik names the next page, or 1 after the last.
            val next = resp.headers["X-Next-Page"]?.toIntOrNull() ?: break
            if (next <= page) break
            page = next
        }
        out
    }
}
