package homegap.presentation.ui

import homegap.api.AttentionItemDto
import homegap.api.Health
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route

/** Where [this] item is shown in the dashboard. */
val AttentionItemDto.location: Location
    get() = Location(
        route = Route.entries.firstOrNull { it.path == route } ?: Route.Overview,
        selection = selection,
    )

/** The item as a status message: its title, then its detail. */
fun AttentionItemDto.toMessage(): StatusMessage = StatusMessage(
    severity = Severity.of(severity),
    text = if (detail.isEmpty()) "$title." else "$title: $detail.",
    location = location,
)

/** The items shown on [route]'s page (all of them on the Overview). */
fun List<AttentionItemDto>.on(route: Route): List<AttentionItemDto> =
    if (route == Route.Overview) this else filter { it.location.route == route.navSection }

/** A status line of [items], or null when nothing needs attention. */
fun statusLineOf(items: List<AttentionItemDto>): StatusLineModel? =
    items.takeIf { it.isNotEmpty() }?.let { StatusLineModel(it.map(AttentionItemDto::toMessage)) }

/** Whether [items] hold anything down (rather than only degraded). */
fun anyDown(items: List<AttentionItemDto>): Boolean = items.any { it.severity == Health.DOWN }
