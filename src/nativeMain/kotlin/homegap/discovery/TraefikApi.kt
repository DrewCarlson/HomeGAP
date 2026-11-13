package homegap.discovery

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** An HTTP router as Traefik's API reports it (from any of its providers). */
@Serializable
data class TraefikRouter(
    val name: String,
    val rule: String = "",
    /** Each name has its provider, for example `private-only@file`. */
    val middlewares: List<String> = emptyList(),
    val status: String = "enabled",
)

/** Reads the routers a Traefik has loaded, from its API (`api.insecure` or a router to `api@internal`). */
class TraefikApi(http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** A redirect can name any host, so the client does not follow one. */
    private val http = http.config { followRedirects = false }

    suspend fun routers(baseUrl: String): List<TraefikRouter> = withTimeout(REQUEST_TIMEOUT) {
        val routers = mutableListOf<TraefikRouter>()
        var page = 1
        while (true) {
            val response = http.get("${baseUrl.trimEnd('/')}/api/http/routers") {
                parameter("per_page", PAGE_SIZE)
                parameter("page", page)
            }
            // The error has no part of the body: the answer can come from a host that is not Traefik.
            check(response.status.value in 200..299) { "Traefik API answered ${response.status.value}" }
            routers += runCatching { json.decodeFromString<List<TraefikRouter>>(response.bodyAsText()) }
                .getOrElse { error("the answer is not a list of Traefik routers") }
            // Traefik names the next page, or 1 after the last.
            val next = response.headers["X-Next-Page"]?.toIntOrNull() ?: break
            if (next <= page) {
                break
            }
            page = next
        }
        routers
    }

    private companion object {
        val REQUEST_TIMEOUT = 10.seconds
        const val PAGE_SIZE = 500
    }
}
