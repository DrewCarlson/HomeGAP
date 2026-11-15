package homegap.mesh

import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import homegap.platform.runCatchingCancellable
import homegap.provision.Mesh
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the route of the orchestrator to the mesh subnet: `homegap mesh-route`. The orchestrator
 * runs as an ordinary user, and such a user cannot change a route. This helper runs as root with
 * only `NET_ADMIN`, in the network namespace of the orchestrator. It reads the subnet from
 * [Mesh.SPEC_FILE] in [dir] and sends the subnet through the mesh namespace ([gateway]).
 *
 * A container that starts again gets a new network namespace. This helper then stays in the old
 * one, which has no network. [run] ends in that case, so that Docker starts the helper again, in
 * the namespace that the orchestrator has now.
 */
class MeshRoute(private val dir: String, private val gateway: String) {
    private val logger = KotlinLogging.logger("homegap.mesh-route")
    private var reported: String? = null

    /** Keeps the route until the namespace of this helper has no network. */
    suspend fun run() {
        var withoutNetwork = 0
        while (withoutNetwork < STALE_CHECKS) {
            withoutNetwork = if (hasNetwork()) 0 else withoutNetwork + 1
            val problem = runCatchingCancellable { keep() }.exceptionOrNull()?.message
            if (problem != null && problem != reported) logger.error { problem }
            reported = problem
            delay(INTERVAL)
        }
        logger.warn { "this network namespace has no network: the orchestrator started again. The helper stops." }
    }

    /** Whether the namespace has an address that is not loopback. The orchestrator always has one. */
    private suspend fun hasNetwork(): Boolean =
        run("ip", "-o", "-4", "addr", "show", "scope", "global").stdout.isNotBlank()

    private suspend fun keep() {
        val text = Files.readTextOrNull("$dir/${Mesh.SPEC_FILE}") ?: return
        val subnet = json.decodeFromString<Mesh.AgentSpec>(text).subnet
        val address = gatewayAddress(gateway) ?: error("the mesh namespace ($gateway) has no address here")
        if (present(subnet, address)) return
        val result = replace(subnet, address)
        check(result.ok) { "could not route $subnet through $gateway: ${failure(result)}" }
        logger.info { "the route to $subnet goes through $gateway ($address)" }
    }

    companion object {
        private val INTERVAL = 5.seconds

        /** After this number of checks in a row with no network, the namespace is the old one. */
        private const val STALE_CHECKS = 3
        private val COMMAND_TIMEOUT = 15.seconds
        private const val COMMAND_NOT_FOUND = 127
        private val json = Json { ignoreUnknownKeys = true }

        /** The IPv4 address of [gateway], or null if the name does not resolve. */
        suspend fun gatewayAddress(gateway: String): String? =
            run("getent", "ahostsv4", gateway).stdout.lineSequence()
                .map { it.substringBefore(' ').trim() }
                .firstOrNull { it.isNotEmpty() }

        /** Whether the route to [subnet] goes through [address]. */
        suspend fun present(subnet: String, address: String): Boolean =
            run("ip", "-4", "route", "show", subnet).stdout.contains("via $address ")

        suspend fun replace(subnet: String, address: String): ProcessResult =
            run("ip", "-4", "route", "replace", subnet, "via", address)

        /** Why [result] failed, in one line. */
        fun failure(result: ProcessResult): String = result.stderr.trim().ifEmpty { "exit ${result.exitCode}" }

        /** Subprocess reports a missing binary as an [IllegalStateException]. The result is exit code 127. */
        private suspend fun run(vararg argv: String): ProcessResult =
            runCatchingCancellable { Subprocess.run(argv.toList(), "", COMMAND_TIMEOUT) }.getOrElse { e ->
                if (e !is IllegalStateException) throw e
                ProcessResult(COMMAND_NOT_FOUND, "", e.message ?: "not found")
            }
    }
}
