package homegap.discovery

import homegap.platform.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The containers of the last discovery, kept in [path].
 *
 * A container that is gone stays in the plan for a grace period. After a restart of the homelab,
 * the orchestrator can start before the app containers. Without the grace period, HomeGAP would
 * delete their resources and DNS records and create them again a moment later.
 *
 * If a discovery fails, the plan comes from the containers of the last one.
 */
class DiscoveryMemory(private val path: String) {
    @Serializable
    private data class Kept(val container: DockerContainerSummary, val missingSince: Long? = null)

    /** [held] maps the name of each container that is gone to the time HomeGAP still keeps it, in ms. */
    data class Result(
        val containers: List<DockerContainerSummary>,
        val held: Map<String, Long>,
        val dropped: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var kept: List<Kept> = Files.readTextOrNull(path)
        ?.let { runCatching { json.decodeFromString<List<Kept>>(it) }.getOrNull() }
        .orEmpty()

    /**
     * Returns the containers to plan with. [seen] is this discovery, or null if it failed. Null
     * comes back only when the discovery failed and no earlier one is known. With [save] false
     * (a dry run), the file stays as it is.
     */
    fun merge(seen: List<DockerContainerSummary>?, now: Long, graceMillis: Long, save: Boolean = true): Result? {
        if (seen == null) {
            return lastKnown()
        }
        val names = seen.map { it.name }.toSet()
        // A container that takes over the hostname of a container that is gone replaces it at once.
        val hostnames = seen.mapNotNull { exposedHostname(it) }.toSet()
        val dropped = mutableListOf<String>()
        val held = kept.filter { it.container.name !in names }.mapNotNull { missing ->
            val since = missing.missingSince ?: now
            val replaced = exposedHostname(missing.container) in hostnames
            if (replaced || now - since >= graceMillis) {
                dropped += missing.container.name
                null
            } else {
                missing.copy(missingSince = since)
            }
        }
        val next = seen.map { Kept(it) } + held
        if (next != kept) {
            kept = next
            if (save) {
                runCatching { Files.writeAtomic(path, json.encodeToString(next), FILE_MODE) }
            }
        }
        return Result(
            containers = next.map { it.container },
            held = held.associate { it.container.name to graceMillis - (now - it.missingSince!!) },
            dropped = dropped,
        )
    }

    /** The containers of the last discovery, or null if no discovery is known. */
    private fun lastKnown(): Result? {
        if (kept.isEmpty()) {
            return null
        }
        return Result(containers = kept.map { it.container }, held = emptyMap(), dropped = emptyList())
    }

    private fun exposedHostname(container: DockerContainerSummary): String? =
        container.labels[Labels.EXPOSE]?.trim()?.lowercase()

    private companion object {
        val FILE_MODE = "600".toInt(8)
    }
}
