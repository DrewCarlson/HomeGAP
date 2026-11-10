package homegap.dashboard

import homegap.platform.Files
import homegap.platform.env
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.RoutingContext

/**
 * The documentation for this version, served at `/docs/` from the site the image build makes
 * (`mkdocs.embedded.yml`). Without it (a local build), `/docs/` says where the pages are.
 */
object DocsFiles {
    const val PREFIX = "/docs"
    private val DIR = env("HOMEGAP_DOCS_DIR") ?: "/usr/share/homegap/docs"

    private val TYPES = mapOf(
        "html" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "json" to "application/json",
        "xml" to "application/xml",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "ico" to "image/x-icon",
        "woff2" to "font/woff2",
        "txt" to "text/plain; charset=utf-8",
        "gz" to "application/gzip",
    )

    /** The file under the docs directory a request path names, or null if it names none. */
    internal fun resolve(requestPath: String, dir: String = DIR): String? {
        val rel = MapFiles.decode(requestPath).removePrefix(PREFIX).trimStart('/')
        if (".." in rel.split('/') || '\\' in rel || '\u0000' in rel) return null
        val path = "$dir/$rel".trimEnd('/')
        return when {
            Files.isDirectory(path) -> "$path/index.html".takeIf { Files.exists(it) }
            Files.exists(path) -> path
            else -> null
        }
    }

    suspend fun RoutingContext.serveDocs() {
        val requestPath = call.request.path()
        if (requestPath == PREFIX) return call.respondRedirect("$PREFIX/")
        // A directory without its slash: relative links on the page need it.
        val file = resolve(requestPath)
        if (file != null && file.endsWith("/index.html") && !requestPath.endsWith("/") && !requestPath.endsWith("index.html")) {
            return call.respondRedirect("$requestPath/")
        }
        if (file == null) {
            val notFound = resolve("$PREFIX/404.html")
            if (notFound == null) return call.respond(HttpStatusCode.NotFound, NO_DOCS)
            return call.respondBytes(Files.readBytes(notFound), ContentType.parse(TYPES.getValue("html")), HttpStatusCode.NotFound)
        }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        val type = TYPES[file.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
        call.respondBytes(Files.readBytes(file), ContentType.parse(type))
    }

    private const val NO_DOCS = "The documentation is not part of this build. It is in the docs/ folder of the repository."
}
