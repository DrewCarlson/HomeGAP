package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * The width class the layout is in. CSS media queries do the layout; this is read only where the
 * markup itself differs (the Inspector as a column, drawer or sheet; tables or cards).
 */
enum class Viewport {
    /** Below 600 px. */
    Phone,

    /** 600 to 1199 px. */
    Tablet,

    /** 1200 to 1365 px: the side navigation, with the Inspector as a drawer. */
    NarrowDesktop,

    /** 1366 px and wider. */
    Desktop,
}

/** The current [Viewport], following the window through three media queries (not resize events). */
@Composable
fun rememberViewport(): Viewport {
    var viewport by remember { mutableStateOf(currentViewport()) }
    DisposableEffect(Unit) {
        val queries = BREAKPOINTS.map { window.matchMedia("(min-width: ${it}px)") }
        val listener: (Event) -> Unit = { viewport = currentViewport() }
        queries.forEach { it.addEventListener("change", listener) }
        onDispose {
            queries.forEach { it.removeEventListener("change", listener) }
        }
    }
    return viewport
}

private val BREAKPOINTS = listOf(600, 1200, 1366)

private fun currentViewport(): Viewport {
    val atLeast = { width: Int -> window.matchMedia("(min-width: ${width}px)").matches }
    return when {
        atLeast(1366) -> Viewport.Desktop
        atLeast(1200) -> Viewport.NarrowDesktop
        atLeast(600) -> Viewport.Tablet
        else -> Viewport.Phone
    }
}
