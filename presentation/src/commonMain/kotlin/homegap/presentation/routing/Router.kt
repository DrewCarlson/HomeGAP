package homegap.presentation.routing

/** Navigation as seen by presenters; the web module backs it with the URL fragment. */
interface Router {
    /** Opens [location]: a route and, optionally, the item selected on it. */
    fun open(location: Location)

    fun navigate(route: Route) {
        open(Location(route))
    }
}
