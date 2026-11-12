package homegap.dashboard

import homegap.platform.Files
import homegap.routes.fingerprint
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingContext

/**
 * The self-hosted map: the vector tiles (one PMTiles archive on disk, read by range requests) and
 * the map's scripts, fonts and icons (embedded with the dashboard under `/map/`). Served by the
 * dashboard and by the public status listener; nothing else is reachable through these routes.
 */
object MapFiles {
    const val TILES_PATH = "/map/world.pmtiles"
    /**
     * The largest range one request may read. PMTiles asks for directories and single tiles, and
     * the largest tile of the map to zoom 6 is below 150 KiB.
     */
    private const val MAX_RANGE = 512 * 1024
    private val RANGE = Regex("^bytes=(\\d+)-(\\d*)$")
    /** Content fingerprints of the embedded map files, computed once each. */
    private val etags = mutableMapOf<String, String>()

    /** Serves [TILES_PATH] from [file], honouring `Range: bytes=a-b`. */
    suspend fun RoutingContext.serveTiles(file: String) {
        val size = Files.size(file)
        if (size <= 0) return call.respond(HttpStatusCode.NotFound, "map tiles not installed")
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        val range = call.request.header(HttpHeaders.Range)?.let { RANGE.find(it.trim()) }
            ?: return call.respond(HttpStatusCode.RequestedRangeNotSatisfiable, "range requests only")
        val start = range.groupValues[1].toLong()
        val end = (range.groupValues[2].toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
        if (start > end || start >= size || end - start + 1 > MAX_RANGE) {
            call.response.header(HttpHeaders.ContentRange, "bytes */$size")
            return call.respond(HttpStatusCode.RequestedRangeNotSatisfiable, "bad range")
        }
        val bytes = Files.readRange(file, start, (end - start + 1).toInt())
        call.response.header(HttpHeaders.ContentRange, "bytes $start-${start + bytes.size - 1}/$size")
        call.respondBytes(bytes, ContentType.Application.OctetStream, HttpStatusCode.PartialContent)
    }

    /** An embedded `/map/...` file (fonts live in folders whose names have spaces). */
    internal fun asset(requestPath: String): WebAsset? {
        val path = decode(requestPath)
        if (!path.startsWith("/map/") || ".." in path) return null
        return WebAssets.files[path]
    }

    /** Serves an embedded `/map/...` file, or 404. */
    suspend fun RoutingContext.serveMapAsset() {
        val asset = asset(call.request.path()) ?: return call.respond(HttpStatusCode.NotFound)
        // The names carry no version: browsers revalidate on each load (a 304 while unchanged), so
        // an upgrade's map code is used at once.
        val etag = etags.getOrPut(asset.path) { "\"${fingerprint(asset.bytes)}\"" }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.ETag, etag)
        if (call.request.header(HttpHeaders.IfNoneMatch)?.split(',')?.any { it.trim().removePrefix("W/") == etag } == true) {
            return call.respond(HttpStatusCode.NotModified)
        }
        respondAsset(asset)
    }


    internal fun decode(path: String): String =
        if ('%' !in path) path else buildString {
            var i = 0
            val bytes = mutableListOf<Byte>()
            fun flush() { if (bytes.isNotEmpty()) { append(bytes.toByteArray().decodeToString()); bytes.clear() } }
            while (i < path.length) {
                val c = path[i]
                if (c == '%' && i + 2 < path.length) {
                    val v = path.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { bytes += v.toByte(); i += 3; continue }
                }
                flush()
                append(c)
                i++
            }
            flush()
        }
}
