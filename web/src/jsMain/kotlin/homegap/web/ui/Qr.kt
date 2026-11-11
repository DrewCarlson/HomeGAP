package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import homegap.presentation.ui.QrCode
import org.jetbrains.compose.web.ExperimentalComposeWebSvgApi
import org.jetbrains.compose.web.svg.Path
import org.jetbrains.compose.web.svg.Rect
import org.jetbrains.compose.web.svg.Svg

/** A QR code drawn as one SVG path, dark on white with a quiet zone, so any phone camera reads it. */
@OptIn(ExperimentalComposeWebSvgApi::class)
@Composable
fun QrImage(code: QrCode, label: String, sizePx: Int = 120) {
    val quiet = 2
    val side = code.size + quiet * 2
    val path = remember(code) {
        buildString {
            for (y in 0 until code.size) {
                for (x in 0 until code.size) {
                    if (code.isDark(x, y)) {
                        append("M${x + quiet} ${y + quiet}h1v1h-1z")
                    }
                }
            }
        }
    }
    Svg(
        viewBox = "0 0 $side $side",
        attrs = {
            classes("qr")
            attr("width", sizePx.toString())
            attr("height", sizePx.toString())
            attr("role", "img")
            attr("aria-label", label)
            attr("shape-rendering", "crispEdges")
        },
    ) {
        Rect(x = 0, y = 0, width = side, height = side, attrs = { attr("fill", "#FFFFFF") })
        Path(d = path, attrs = { attr("fill", "#070A1F") })
    }
}
