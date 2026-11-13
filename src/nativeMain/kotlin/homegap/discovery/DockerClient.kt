package homegap.discovery

import homegap.platform.runCatchingCancellable
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * Docker Engine API client that only reads (container list, events, image metadata). It talks to
 * [host] when one is given (`tcp://name:port`, a filtering socket proxy), else to the unix socket.
 * A socket mount gives the whole API whatever its mount mode, so the proxy is what limits it.
 *
 * The requests use plain HTTP, because the Docker socket and the socket proxy have no TLS.
 */
@Suppress("HttpUrlsUsage")
class DockerClient(
    client: HttpClient,
    private val json: Json,
    socketPath: String = "/var/run/docker.sock",
    host: String? = null,
) {
    private val logger = KotlinLogging.logger {}
    private val requestTimeout = 10.seconds
    private val tcp = host?.trim()
        ?.takeIf { it.startsWith("tcp://") || it.startsWith("http://") }
        ?.substringAfter("://")
        ?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }
    private val baseUrl = "http://${tcp ?: "localhost"}/$API_VERSION"

    private val httpClient = when {
        tcp != null -> client
        else -> client.config { defaultRequest { unixSocket(socketPath) } }
    }

    suspend fun listContainers(filters: Map<String, List<String>>): List<DockerContainerSummary> =
        withTimeout(requestTimeout) {
            val response = httpClient.get("$baseUrl/containers/json") {
                parameter("filters", json.encodeToString(filters))
            }
            json.decodeFromString<List<DockerContainerSummary>>(bodyOf(response))
        }

    /** A local image by reference (`repo:tag`, `repo@digest` or id), or null when it isn't here. */
    suspend fun image(ref: String): DockerImage? = withTimeout(requestTimeout) {
        val response = httpClient.get("$baseUrl/images/$ref/json")
        if (response.status.value == NOT_FOUND) {
            return@withTimeout null
        }
        json.decodeFromString<DockerImage>(bodyOf(response))
    }

    /** Returns the body of [response]. Throws with the start of the body if the status is an error. */
    private suspend fun bodyOf(response: HttpResponse): String {
        val status = response.status.value
        check(status in 200..299) { "Docker API $status: ${response.bodyAsText().take(ERROR_BODY_CHARS)}" }
        return response.bodyAsText()
    }

    fun streamEvents(filters: Map<String, List<String>>): Flow<DockerEvent> = callbackFlow {
        httpClient.prepareGet("$baseUrl/events") {
            parameter("filters", json.encodeToString(filters))
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
        }.execute { response ->
            val channel: ByteReadChannel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                if (line.isBlank()) {
                    continue
                }
                runCatchingCancellable { send(json.decodeFromString<DockerEvent>(line)) }
                    .onFailure { e -> logger.warn(e) { "Failed to parse Docker event: $line" } }
            }
        }
        close()
        awaitClose()
    }

    private companion object {
        const val API_VERSION = "v1.43"
        const val NOT_FOUND = 404
        const val ERROR_BODY_CHARS = 200
    }
}

@Serializable
data class DockerContainerSummary(
    @SerialName("Id") val id: String,
    @SerialName("Names") val names: List<String> = emptyList(),
    @SerialName("Labels") val labels: Map<String, String> = emptyMap(),
    @SerialName("State") val state: String = "",
    @SerialName("NetworkSettings") val networkSettings: ContainerNetworkSettings = ContainerNetworkSettings(),
) {
    val name: String get() = names.firstOrNull()?.removePrefix("/") ?: id.take(12)
}

@Serializable
data class ContainerNetworkSettings(
    @SerialName("Networks") val networks: Map<String, NetworkEndpoint> = emptyMap(),
)

@Serializable
data class NetworkEndpoint(
    @SerialName("IPAddress") val ipAddress: String = "",
)

@Serializable
data class DockerEvent(
    @SerialName("Type") val type: String = "",
    @SerialName("Action") val action: String = "",
    @SerialName("Actor") val actor: DockerEventActor = DockerEventActor(),
)

@Serializable
data class DockerImage(
    @SerialName("Id") val id: String = "",
    @SerialName("RepoDigests") val repoDigests: List<String>? = null,
    /**
     * When the image was built. Unlike [id] (the config digest in Docker's classic store, the
     * manifest digest in its containerd store) this is the same on every host.
     */
    @SerialName("Created") val created: String = "",
) {
    /**
     * Returns [ref] with the registry digest of this image (`repo@sha256:…`), so another host pulls
     * exactly this image. Returns [ref] itself for an image that did not come from a registry.
     */
    fun pinned(ref: String): String {
        val name = ref.substringBefore('@')
        val hasTag = name.lastIndexOf(':') > name.lastIndexOf('/')
        val repo = if (hasTag) name.substringBeforeLast(':') else name
        return repoDigests.orEmpty().firstOrNull { it.substringBefore('@') == repo } ?: ref
    }
}

/**
 * Whether a container event can change what HomeGAP exposes: only containers carrying `homegap.*`
 * labels. Docker sends a container's labels with every event about it, its stop and removal
 * included, so a labeled container going away is still seen. Unlabeled containers (a crash-looping
 * app, HomeGAP's own cluster and tunnel containers) never trigger a pass.
 */
fun DockerEvent.concernsHomegap(): Boolean = actor.attributes.keys.any { it.startsWith(Labels.PREFIX) }

@Serializable
data class DockerEventActor(
    @SerialName("ID") val id: String = "",
    @SerialName("Attributes") val attributes: Map<String, String> = emptyMap(),
)
