package homegap.web

import androidx.compose.runtime.remember
import dev.zacsweers.metro.createGraph
import homegap.presentation.app.AppProps
import homegap.presentation.data.TokenStore
import homegap.presentation.data.VIEW_FRAGMENT
import homegap.web.di.WebAppGraph
import homegap.web.routing.HashRouter
import homegap.web.ui.AppShell
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable

fun main() {
    val graph = createGraph<WebAppGraph>()
    openShareLink(graph.tokenStore)
    val router = HashRouter()
    renderComposable(rootElementId = "root") {
        val location = router.location
        val props = remember(location) {
            AppProps(
                route = location.route,
                router = router,
                selection = location.selection,
            )
        }
        val model = graph.appPresenter.model(props)
        AppShell(model)
    }
}

/**
 * A share link (`#view=<token>`) opens a read-only view in this tab: its token is kept for the tab
 * and leaves the address bar, so it is not copied along with a page link.
 */
private fun openShareLink(tokens: TokenStore) {
    val fragment = window.location.hash.removePrefix("#")
    if (!fragment.startsWith(VIEW_FRAGMENT)) return
    tokens.viewToken = fragment.removePrefix(VIEW_FRAGMENT).ifBlank { null }
    window.history.replaceState(null, "", window.location.pathname + window.location.search)
}
