package homegap.web.routing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import homegap.presentation.routing.Route
import homegap.presentation.routing.Router
import kotlinx.browser.window

/**
 * [Router] over the URL fragment (`#nodes`): links and the back button change the hash, and
 * [route] follows it as Compose state.
 */
class HashRouter : Router {
    var route by mutableStateOf(Route.fromPath(window.location.hash))
        private set

    init {
        window.addEventListener("hashchange", {
            route = Route.fromPath(window.location.hash)
            window.scrollTo(0.0, 0.0)
        })
    }

    override fun navigate(route: Route) {
        window.location.hash = route.path
    }

    companion object {
        fun href(route: Route): String = "#${route.path}"
    }
}
