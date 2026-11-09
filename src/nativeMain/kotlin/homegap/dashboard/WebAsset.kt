package homegap.dashboard

import kotlin.io.encoding.Base64

internal class WebAsset(val path: String, private val base64: String, private val gzipBase64: String? = null) {
    val bytes: ByteArray by lazy { Base64.decode(base64) }
    /** Pre-compressed body for clients sending `Accept-Encoding: gzip` (text assets only). */
    val gzip: ByteArray? by lazy { gzipBase64?.let { Base64.decode(it) } }
    val contentType: String
        get() = when (path.substringAfterLast('.', "")) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "ico" -> "image/x-icon"
            "json", "map" -> "application/json"
            "woff2" -> "font/woff2"
            "wasm" -> "application/wasm"
            "pbf" -> "application/x-protobuf"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
}
