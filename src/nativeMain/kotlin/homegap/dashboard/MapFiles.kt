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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The self-hosted map: the vector tiles (one PMTiles archive on disk, read by range requests) and
 * the map's scripts, fonts and icons (embedded with the dashboard under `/map/`). The dashboard
 * and the public status listener serve them. These routes give access to nothing else.
 */
object MapFiles {
    const val TILES_PATH = "/map/world.pmtiles"

    /**
     * The largest range that one request can read. PMTiles asks for directories and single tiles,
     * and the largest tile of the map to zoom 6 is below 150 KiB.
     */
    private const val MAX_RANGE = 512 * 1024
    private val RANGE = Regex("^bytes=(\\d+)-(\\d*)$")

    /** The content fingerprints of the embedded map files. Each one is the result of one calculation. */
    private val etags = mutableMapOf<String, String>()
    private val etagsLock = SynchronizedObject()

    /** Serves [TILES_PATH] from [file]. It obeys `Range: bytes=a-b`. */
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
        // The names have no version. A browser checks on each load (a 304 while unchanged), so it
        // uses the map code of an upgrade at once.
        // Requests run on more than one thread.
        val etag = synchronized(etagsLock) { etags.getOrPut(asset.path) { "\"${fingerprint(asset.bytes)}\"" } }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.ETag, etag)
        val known = call.request.header(HttpHeaders.IfNoneMatch)
            ?.split(',')
            ?.any { it.trim().removePrefix("W/") == etag } == true
        if (known) {
            return call.respond(HttpStatusCode.NotModified)
        }
        respondAsset(asset)
    }

    /** Decodes the percent escapes in [path]. An escape that is not valid stays as it is. */
    internal fun decode(path: String): String {
        if ('%' !in path) return path
        return buildString {
            var i = 0
            val bytes = mutableListOf<Byte>()
            fun flush() {
                if (bytes.isNotEmpty()) {
                    append(bytes.toByteArray().decodeToString())
                    bytes.clear()
                }
            }
            while (i < path.length) {
                val char = path[i]
                if (char == '%' && i + 2 < path.length) {
                    val value = path.substring(i + 1, i + 3).toIntOrNull(16)
                    if (value != null) {
                        bytes += value.toByte()
                        i += 3
                        continue
                    }
                }
                flush()
                append(char)
                i++
            }
            flush()
        }
    }
}
