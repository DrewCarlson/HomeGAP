package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.presentation.routing.Route
import org.jetbrains.compose.web.ExperimentalComposeWebSvgApi
import org.jetbrains.compose.web.svg.Path
import org.jetbrains.compose.web.svg.Svg

/** A 24-unit line icon drawn in the current text color. */
@OptIn(ExperimentalComposeWebSvgApi::class)
@Composable
fun Icon(path: String, size: Int = 16) {
    Svg(
        viewBox = "0 0 24 24",
        attrs = {
            attr("width", size.toString())
            attr("height", size.toString())
            attr("fill", "none")
            attr("stroke", "currentColor")
            attr("stroke-width", "1.8")
            attr("stroke-linecap", "round")
            attr("stroke-linejoin", "round")
            attr("aria-hidden", "true")
        },
    ) {
        Path(path)
    }
}

/** Line icon paths, 24 × 24. */
object Icons {
    const val SEARCH = "M11 18a7 7 0 1 0 0-14a7 7 0 0 0 0 14zM16 16l4 4"
    const val PAUSE = "M9 5v14M15 5v14"
    const val PLAY = "M8 5l11 7-11 7z"
    const val RESYNC = "M20 11a8 8 0 0 0-14.9-4M4 4v4h4M4 13a8 8 0 0 0 14.9 4M20 20v-4h-4"
    const val CLOSE = "M6 6l12 12M18 6L6 18"
    const val CHECK = "M5 12.5l4.5 4.5L19 7"
    const val COPY = "M8 8h11v11H8zM5 16V5h11"
    const val ALERT = "M12 6.5v7M12 17.5v.01"
    /** ⋮ for a [KebabMenu]. */
    const val MORE = "M12 4a1 1 0 1 0 0 2a1 1 0 1 0 0-2zM12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2zM12 18a1 1 0 1 0 0 2a1 1 0 1 0 0-2z"
    const val EYE = "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6a3 3 0 0 0 0 6z"
}

/** The section's icon in the navigation. */
val Route.iconPath: String
    get() = when (navSection) {
        Route.Overview -> "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"
        Route.Nodes -> "M7 12a5 5 0 1 0 10 0a5 5 0 1 0-10 0M12 3v4M12 17v4M3 12h4M17 12h4"
        Route.Services -> "M4 6h16M4 12h16M4 18h16"
        Route.Access -> "M12 12a4 4 0 1 0 0-8a4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0"
        Route.SpeedTest -> "M12 14l4-4M4 18a8 8 0 1 1 16 0"
        Route.Dns -> "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"
        Route.Tunnels -> "M3 12h4M17 12h4M7 8h10v8H7z"
        Route.Activity -> "M3 12h4l3-8 4 16 3-8h4"
        Route.Diagnostics -> "M14 4l6 6-10 10H4v-6z"
        else -> "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M14 4v4M8 10v4M16 16v4"
    }
