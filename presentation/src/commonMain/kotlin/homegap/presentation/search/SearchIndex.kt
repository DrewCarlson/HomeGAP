package homegap.presentation.search

import androidx.compose.runtime.Immutable
import homegap.api.LogEntryDto
import homegap.api.SearchHitDto
import homegap.api.StatusSnapshot
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.location

/** What a search result is; results are grouped by kind in this order. */
enum class SearchKind(val title: String) {
    Action("Actions"),
    Node("Nodes"),
    Service("Services"),
    Dns("DNS"),
    Tunnel("Tunnels"),
    Page("Pages"),
    Setting("Settings"),
    Log("Log"),
}

/** One result: it opens [location] or, for actions, runs [run]. */
@Immutable
data class SearchHit(
    val kind: SearchKind,
    val title: String,
    val subtitle: String,
    val location: Location? = null,
    val run: (() -> Unit)? = null,
    /** Extra words that match but aren't shown (a node's city, a service's container). */
    val keywords: String = "",
)

/** Everything search covers that the dashboard already holds: the snapshot, the pages and the log. */
fun searchIndex(
    snapshot: StatusSnapshot,
    log: List<LogEntryDto>,
    actions: List<SearchHit>,
): List<SearchHit> = buildList {
    addAll(actions)
    snapshot.nodes.forEach { node ->
        add(
            SearchHit(
                kind = SearchKind.Node,
                title = node.id,
                subtitle = listOfNotNull(node.location?.name, node.health.name.lowercase()).joinToString(" · "),
                location = Location(Route.Nodes, node.id),
                keywords = "${node.region} ${node.provider} ${node.host}",
            )
        )
    }
    snapshot.services.forEach { service ->
        add(
            SearchHit(
                kind = SearchKind.Service,
                title = service.hostname,
                subtitle = "${service.container} · ${service.access}",
                location = Location(Route.Services, service.hostname),
                keywords = service.key,
            )
        )
    }
    snapshot.dns.distinctBy { it.name }.forEach { record ->
        add(
            SearchHit(
                kind = SearchKind.Dns,
                title = record.name,
                subtitle = "${record.type} ${record.value}",
                location = Location(Route.Dns, record.name),
            )
        )
    }
    snapshot.tunnels.forEach { tunnel ->
        add(
            SearchHit(
                kind = SearchKind.Tunnel,
                title = "${tunnel.nodeId} connector",
                subtitle = if (tunnel.up) "up" else "down",
                location = Location(Route.Tunnels, tunnel.nodeId),
            )
        )
    }
    (Route.NAV + Route.Locations + Route.AddNode).forEach { route ->
        add(
            SearchHit(
                kind = SearchKind.Page,
                title = route.title,
                subtitle = route.subtitle,
                location = Location(route),
            )
        )
    }
    log.take(LOG_ENTRIES_SEARCHED).forEach { entry ->
        add(
            SearchHit(
                kind = SearchKind.Log,
                title = entry.message,
                subtitle = listOfNotNull(entry.domain, entry.target).joinToString(" · "),
                location = Location(Route.Activity, entry.seq.toString()),
            )
        )
    }
}

/** Results for [query], best first, grouped by kind; at most [limit]. */
fun search(index: List<SearchHit>, query: String, limit: Int = 30): List<SearchHit> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    return index
        .mapNotNull { hit ->
            val score = maxOf(
                fuzzyScore(trimmed, hit.title) ?: -1,
                (fuzzyScore(trimmed, hit.subtitle) ?: -1) - 20,
                (fuzzyScore(trimmed, hit.keywords) ?: -1) - 30,
            )
            if (score < 0) null else hit to score
        }
        .sortedWith(compareBy<Pair<SearchHit, Int>> { it.first.kind.ordinal }.thenByDescending { it.second })
        .take(limit)
        .map { it.first }
}

/**
 * How well [query] matches [text], or null when it doesn't: every query character must appear in
 * order. Matches at the start, at word starts and in a row score higher; spread-out ones lower.
 */
fun fuzzyScore(query: String, text: String): Int? {
    if (text.isEmpty()) return null
    val needle = query.lowercase()
    val haystack = text.lowercase()
    if (haystack.startsWith(needle)) return 1000 - haystack.length
    val contained = haystack.indexOf(needle)
    if (contained >= 0) {
        val wordStart = contained == 0 || !haystack[contained - 1].isLetterOrDigit()
        return (if (wordStart) 800 else 600) - contained
    }
    var score = 0
    var position = 0
    var previous = -2
    for (char in needle) {
        val found = haystack.indexOf(char, position)
        if (found < 0) return null
        score += when {
            found == previous + 1 -> 15
            found == 0 || !haystack[found - 1].isLetterOrDigit() -> 10
            else -> 1
        }
        score -= (found - position).coerceAtMost(10)
        previous = found
        position = found + 1
    }
    return score.coerceAtLeast(0)
}

/** Shown before anything is typed: suggested actions, what needs attention, then a few pages. */
fun suggestions(snapshot: StatusSnapshot, actions: List<SearchHit>): List<SearchHit> = buildList {
    addAll(actions.take(2))
    snapshot.attention.take(4).forEach { item ->
        val location = item.location
        add(
            SearchHit(
                kind = when (location.route) {
                    Route.Nodes -> SearchKind.Node
                    Route.Services -> SearchKind.Service
                    Route.Dns -> SearchKind.Dns
                    Route.Tunnels -> SearchKind.Tunnel
                    else -> SearchKind.Page
                },
                title = item.selection ?: item.title,
                subtitle = item.title,
                location = location,
            )
        )
    }
    listOf(Route.Overview, Route.Diagnostics, Route.Locations).forEach { route ->
        add(
            SearchHit(
                kind = SearchKind.Page,
                title = route.title,
                subtitle = route.subtitle,
                location = Location(route),
            )
        )
    }
}

private const val LOG_ENTRIES_SEARCHED = 300

/** A server hit (config key, secret, older log entry) as a search result. */
fun SearchHitDto.toHit(): SearchHit = SearchHit(
    kind = if (kind == "log") SearchKind.Log else SearchKind.Setting,
    title = title,
    subtitle = subtitle,
    location = Location(
        route = Route.entries.firstOrNull { it.path == route } ?: Route.Config,
        selection = selection,
    ),
)
