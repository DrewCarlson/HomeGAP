package homegap.discovery

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** Read-only Docker Engine API client over the unix socket (the socket is mounted :ro). */
class DockerClient(
    client: HttpClient,
    private val json: Json,
    socketPath: String = "/var/run/docker.sock",
) {
    private val logger = KotlinLogging.logger {}
    private val requestTimeout = 10.seconds
    private val baseUrl = "http://localhost/v1.43"

    private val httpClient = client.config {
        defaultRequest { unixSocket(socketPath) }
    }

    suspend fun listContainers(filters: Map<String, List<String>>): List<DockerContainerSummary> =
        withTimeout(requestTimeout) {
            val resp = httpClient.get("$baseUrl/containers/json") { parameter("filters", json.encodeToString(filters)) }
            check(resp.status.value in 200..299) { "Docker API ${resp.status.value}: ${resp.bodyAsText().take(200)}" }
            json.decodeFromString<List<DockerContainerSummary>>(resp.bodyAsText())
        }

    fun streamEvents(filters: Map<String, List<String>>): Flow<DockerEvent> = callbackFlow {
        httpClient.prepareGet("$baseUrl/events") {
            parameter("filters", json.encodeToString(filters))
        }.execute { response ->
            val channel: ByteReadChannel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    send(json.decodeFromString<DockerEvent>(line))
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                    logger.warn(e) { "Failed to parse Docker event: $line" }
                }
            }
        }
        close()
        awaitClose()
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
data class DockerEventActor(
    @SerialName("ID") val id: String = "",
    @SerialName("Attributes") val attributes: Map<String, String> = emptyMap(),
)
