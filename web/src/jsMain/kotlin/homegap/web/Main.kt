package homegap.web

import androidx.compose.runtime.remember
import dev.zacsweers.metro.createGraph
import homegap.presentation.app.AppProps
import homegap.web.di.WebAppGraph
import homegap.web.routing.HashRouter
import homegap.web.ui.AppShell
import org.jetbrains.compose.web.renderComposable

fun main() {
    val graph = createGraph<WebAppGraph>()
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
