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

    /** The location before the current one, if this page came from it. */
    private var previous: Location? = null

    init {
        window.addEventListener("hashchange", {
            val next = Location.fromPath(window.location.hash)
            // Opening or closing an item keeps the page where it is; a new section starts at the top
            // (phones scroll the window; wider screens scroll the page element, see Shell).
            if (next.route != location.route) {
                window.scrollTo(0.0, 0.0)
            }
            previous = location
            location = next
        })
    }

    /**
     * Opens [location]. A close of the selected item goes back in the history when the item was
     * opened from its list, so the back button does not open the item again.
     */
    override fun open(location: Location) {
        val current = this.location
        val closesItem = location.selection == null && current.selection != null && location.route == current.route
        if (closesItem && location == previous) {
            window.history.back()
        } else {
            window.location.hash = location.path
        }
    }

    companion object {
        fun href(route: Route): String = href(Location(route))

        fun href(location: Location): String = "#${location.path}"
    }
}
