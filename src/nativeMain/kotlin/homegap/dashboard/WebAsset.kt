package homegap.dashboard

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingContext
import kotlin.io.encoding.Base64

internal class WebAsset(
    val path: String,
    private val base64: String,
    private val gzipBase64: String? = null,
    private val brotliBase64: String? = null,
) {
    val bytes: ByteArray by lazy { Base64.decode(base64) }

    /** The compressed body for a client that sends `Accept-Encoding: gzip` (text assets only). */
    val gzip: ByteArray? by lazy { gzipBase64?.let { Base64.decode(it) } }

    /** The compressed body for a client that sends `Accept-Encoding: br` (text assets only). */
    val brotli: ByteArray? by lazy { brotliBase64?.let { Base64.decode(it) } }

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

/** Sends [asset] in the smallest encoding the client accepts: brotli, then gzip, then as-is. */
internal suspend fun RoutingContext.respondAsset(asset: WebAsset) {
    val accepted = call.request.header(HttpHeaders.AcceptEncoding).orEmpty()
    val contentType = ContentType.parse(asset.contentType)
    call.response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
    val brotli = asset.brotli
    val gzip = asset.gzip
    when {
        brotli != null && "br" in accepted -> {
            call.response.header(HttpHeaders.ContentEncoding, "br")
            call.respondBytes(brotli, contentType)
        }

        gzip != null && "gzip" in accepted -> {
            call.response.header(HttpHeaders.ContentEncoding, "gzip")
            call.respondBytes(gzip, contentType)
        }

        else -> call.respondBytes(asset.bytes, contentType)
    }
}
