package homegap.brand

import kotlin.io.encoding.Base64

/**
 * The HomeGAP emblem for the pages that the orchestrator and the nodes render themselves (status,
 * speed test, maintenance). The markup is inline, so each page stays one file and needs no asset
 * route. This is the cut of the emblem for small sizes (heavier lines, no window or glow), which
 * is clear at 14–32 px.
 */
object Brand {
    /**
     * The emblem as inline markup. Set its size with CSS on `.hg-mark`. It is decoration, so
     * screen readers do not read it.
     */
    const val MARK_SVG =
        """<svg class="hg-mark" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" aria-hidden="true" focusable="false">""" +
            """<defs><linearGradient id="hg-mark-tile" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#303f9f"/><stop offset="1" stop-color="#1a237e"/></linearGradient>""" +
            """<linearGradient id="hg-mark-ring" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#b388ff"/><stop offset="1" stop-color="#18ffff"/></linearGradient></defs>""" +
            """<rect width="64" height="64" rx="14" fill="url(#hg-mark-tile)"/>""" +
            """<circle cx="32" cy="31" r="21" fill="none" stroke="url(#hg-mark-ring)" stroke-width="4.5"/>""" +
            """<path d="M21 41V30L32 20 43 30V41" fill="none" stroke="#fff" stroke-width="7.5" stroke-linecap="round" stroke-linejoin="round"/>""" +
            """<circle cx="14" cy="44" r="6.5" fill="#18ffff"/><circle cx="32" cy="53" r="6.5" fill="#18ffff"/><circle cx="50" cy="44" r="6.5" fill="#18ffff"/></svg>"""

    /** The repository of HomeGAP, for the "Powered by HomeGAP" line of a public page. */
    const val REPOSITORY_URL = "https://github.com/DrewCarlson/HomeGAP"

    /** A `<link rel="icon">` for the page, as a data URL so the browser fetches nothing. */
    val FAVICON_LINK: String =
        """<link rel="icon" type="image/svg+xml" href="data:image/svg+xml;base64,${Base64.encode(MARK_SVG.encodeToByteArray())}">"""
}
