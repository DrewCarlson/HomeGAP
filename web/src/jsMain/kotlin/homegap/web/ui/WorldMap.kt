package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import homegap.presentation.map.MapModel
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

/** Loads the shared map module (`/map/homegap-map.js`) once; kept out of the main bundle. */
private val mapModule: Promise<dynamic> by lazy {
    val url = "/map/homegap-map.js"
    js("import(url)").unsafeCast<Promise<dynamic>>()
}

/**
 * The self-hosted map. [onMarkerClick] makes markers clickable; [onPick] turns clicks on the map
 * into picked coordinates (latitude, longitude), shown at [pick].
 */
@Composable
fun WorldMap(
    model: MapModel,
    heightPx: Int = 360,
    onMarkerClick: ((String) -> Unit)? = null,
    onPick: ((Double, Double) -> Unit)? = null,
    pick: Pair<Double, Double>? = null,
    fitKey: Any? = Unit,
) {
    var handle by remember { mutableStateOf<dynamic>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val markerClick by rememberUpdatedState(onMarkerClick)
    val pickClick by rememberUpdatedState(onPick)
    Div({
        classes("map-box")
        style { height(heightPx.px) }
    }) {
        DisposableEffect(Unit) {
            val element = scopeElement as HTMLElement
            var created: dynamic = null
            var disposed = false
            val options: dynamic = js("({})")
            if (onPick != null) options.onClick = { lng: Double, lat: Double -> pickClick?.invoke(lat, lng) }
            mapModule.then { module: dynamic ->
                module.createMap(element, options).unsafeCast<Promise<dynamic>>().then { h: dynamic ->
                    if (disposed) h.destroy() else { created = h; handle = h }
                    Unit
                }.catch { failed = "The map could not be loaded." }
                Unit
            }.catch { failed = "The map could not be loaded." }
            onDispose {
                disposed = true
                created?.destroy()
                handle = null
            }
        }
        failed?.let { Div({ classes("map-error") }) { org.jetbrains.compose.web.dom.Text(it) } }
    }
    val h = handle
    LaunchedEffect(h, model) {
        if (h == null) return@LaunchedEffect
        h.setMarkers(model.markers.map { m ->
            val o: dynamic = js("({})")
            o.id = m.id; o.lat = m.latitude; o.lng = m.longitude; o.health = m.health.name; o.kind = m.kind.wire
            o.label = m.label; o.highlight = m.highlight
            m.info?.let { info ->
                val i: dynamic = js("({})")
                i.title = info.title; i.subtitle = info.subtitle
                i.rows = info.rows.map { r -> arrayOf(r.label, r.value, r.health?.name) }.toTypedArray()
                o.info = i
            }
            if (markerClick != null) o.onClick = { id: String -> markerClick?.invoke(id) }
            o
        }.toTypedArray())
        h.setLines(model.lines.map { l ->
            val o: dynamic = js("({})")
            o.id = l.id; o.from = arrayOf(l.from.longitude, l.from.latitude); o.to = arrayOf(l.to.longitude, l.to.latitude)
            o.health = l.health.name; o.label = l.label
            o
        }.toTypedArray())
        h.setAreas(model.areas.map { a ->
            val o: dynamic = js("({})")
            o.id = a.id; o.lng = a.center.longitude; o.lat = a.center.latitude; o.radiusKm = a.radiusKm; o.label = a.label
            o.health = a.health?.name
            o
        }.toTypedArray())
    }
    // Fit once per key (not on every status refresh, so a user's pan and zoom stay put).
    LaunchedEffect(h, fitKey) {
        if (h == null) return@LaunchedEffect
        val points = (model.markers.map { arrayOf(it.longitude, it.latitude) } + model.areas.map { arrayOf(it.center.longitude, it.center.latitude) })
        if (points.isNotEmpty()) h.fit(points.toTypedArray(), model.fitMaxZoom)
    }
    LaunchedEffect(h, pick) {
        if (h == null) return@LaunchedEffect
        if (pick == null) h.setPick(null, null) else h.setPick(pick.second, pick.first)
    }
}
