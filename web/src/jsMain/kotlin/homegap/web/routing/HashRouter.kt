package homegap.web.routing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.routing.Router
import kotlinx.browser.window

/**
 * [Router] over the URL fragment (`#nodes/sg1`): links and the back button change the hash, and
 * [location] follows it as Compose state.
 */
class HashRouter : Router {
    var location by mutableStateOf(Location.fromPath(window.location.hash))
        private set

    init {
        window.addEventListener("hashchange", {
            val next = Location.fromPath(window.location.hash)
            // Opening or closing an item keeps the page where it is; a new section starts at the top.
            if (next.route != location.route) {
                window.scrollTo(0.0, 0.0)
            }
            location = next
        })
    }

    override fun open(location: Location) {
        window.location.hash = location.path
    }

    companion object {
        fun href(route: Route): String = href(Location(route))

        fun href(location: Location): String = "#${location.path}"
    }
}
