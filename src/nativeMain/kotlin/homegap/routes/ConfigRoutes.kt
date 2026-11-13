package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ConfigResponse
import homegap.api.DiffResponse
import homegap.api.SecretRefDto
import homegap.api.SecretsResponse
import homegap.config.ConfigLoader
import homegap.config.referencedSecrets
import homegap.di.RunOptions
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.reconcile.Controller
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get

/** The config file, the desired-vs-actual diff and the referenced secrets (presence only). */
@Suppress("unused") // The dependency graph creates this class.
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class ConfigRoutes(
    private val controller: Controller,
    private val options: RunOptions,
) : RoutingController {
    override fun init(parent: Route) {
        parent.get("/config") { getConfig() }
        parent.get("/diff") { getDiff() }
        parent.get("/secrets") { getSecrets() }
    }

    suspend fun RoutingContext.getConfig() {
        val config = controller.config
        respondRevalidated(
            ConfigResponse(
                path = options.configPath,
                writable = config.dashboard.allowConfigEdits,
                yaml = Files.readTextOrNull(options.configPath) ?: ConfigLoader.encode(config),
                nodes = config.nodes.map { it.toDto() },
            ),
        )
    }

    suspend fun RoutingContext.getDiff() {
        call.respond(DiffResponse(nowMillis(), controller.lastDiff, controller.desiredStateJson()))
    }

    /** Never returns secret values: only where each one comes from and whether it resolves. */
    suspend fun RoutingContext.getSecrets() {
        val secrets = controller.config.referencedSecrets().map { (ref, usedBy) ->
            SecretRefDto(ref.name, usedBy, ref.describe(), present = ref.resolve() != null)
        }
        respondRevalidated(SecretsResponse(secrets))
    }
}
