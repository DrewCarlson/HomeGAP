package homegap.web

import androidx.compose.runtime.remember
import dev.zacsweers.metro.createGraph
import homegap.presentation.app.AppProps
import homegap.web.di.WebAppGraph
import homegap.web.routing.HashRouter
import homegap.web.ui.App
import org.jetbrains.compose.web.renderComposable

fun main() {
    val graph = createGraph<WebAppGraph>()
    val router = HashRouter()
    renderComposable(rootElementId = "root") {
        val model = graph.appPresenter.model(remember(router.route) { AppProps(router.route, router) })
        App(model)
    }
}
