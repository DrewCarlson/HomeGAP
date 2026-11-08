package homegap.presentation.routing

/** Navigation as seen by presenters; the web module backs it with the URL fragment. */
interface Router {
    fun navigate(route: Route)
}
