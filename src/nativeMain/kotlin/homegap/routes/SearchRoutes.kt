package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.SearchHitDto
import homegap.api.SearchResponse
import homegap.config.ConfigLoader
import homegap.config.referencedSecrets
import homegap.reconcile.Controller
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get

/**
 * `GET /search?q=`: what the dashboard can't search on its own. Config keys (never their values),
 * referenced secrets (name and whether they resolve), and log entries older than it holds.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class SearchRoutes(private val controller: Controller) : RoutingController {
    override fun init(parent: Route) {
        parent.get("/search") { search() }
    }

    suspend fun RoutingContext.search() {
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        if (query.length < 2) {
            call.respond(SearchResponse(emptyList()))
            return
        }
        val needle = query.lowercase()
        val config = controller.config
        val keys = configKeys(ConfigLoader.encode(config))
            .filter { needle in it.lowercase() }
            .take(LIMIT)
            .map { key ->
                SearchHitDto(
                    kind = "setting",
                    title = key,
                    subtitle = "config key",
                    route = "config",
                )
            }
        val secrets = config.referencedSecrets()
            .filter { (ref, usedBy) -> needle in ref.name.lowercase() || needle in usedBy.lowercase() }
            .take(LIMIT)
            .map { (ref, usedBy) ->
                SearchHitDto(
                    kind = "setting",
                    title = ref.name,
                    subtitle = "secret · ${if (ref.resolve() != null) "present" else "missing"} · $usedBy",
                    route = "config",
                )
            }
        val entries = controller.log.recent(limit = LIMIT, query = query)
        val log = entries.map { entry ->
            SearchHitDto(
                kind = "log",
                title = entry.message,
                subtitle = listOfNotNull(entry.domain, entry.target).joinToString(" · "),
                route = "log",
                selection = entry.seq.toString(),
            )
        }
        call.respond(SearchResponse(keys + secrets + log))
    }

    private companion object {
        const val LIMIT = 10
    }
}

/** The dotted paths of every key in a YAML document (`dns.bunny.zone`), from its indentation. */
internal fun configKeys(yaml: String): List<String> {
    val stack = mutableListOf<Pair<Int, String>>()
    val keys = mutableListOf<String>()
    for (line in yaml.lines()) {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val indent = line.length - trimmed.length
        val item = trimmed.removePrefix("- ")
        val key = item.substringBefore(':', missingDelimiterValue = "").trim()
        if (key.isEmpty() || key.contains(' ') || !item.contains(':')) continue
        while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
        stack += indent to key
        keys += stack.joinToString(".") { it.second }
    }
    return keys.distinct()
}
