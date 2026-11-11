package homegap.routes

import homegap.api.ApiJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Sends [value] as JSON with an ETag, and `304 Not Modified` when the browser already holds that
 * version. `no-cache` makes the browser ask every time, so screens that reload a response get it
 * again only when it changed.
 */
internal suspend inline fun <reified T> RoutingContext.respondRevalidated(value: T) {
    respondRevalidated(
        value = value,
        serializer = serializer<T>(),
    )
}

internal suspend fun <T> RoutingContext.respondRevalidated(value: T, serializer: KSerializer<T>) {
    val body = ApiJson.encodeToString(serializer, value)
    val etag = "\"${fingerprint(body.encodeToByteArray())}\""
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.response.header(HttpHeaders.ETag, etag)
    val known = call.request.header(HttpHeaders.IfNoneMatch)
        ?.split(',')
        ?.any { it.trim().removePrefix("W/") == etag } == true
    if (known) {
        call.respond(HttpStatusCode.NotModified)
    } else {
        call.respondText(body, ContentType.Application.Json)
    }
}

/** FNV-1a over [bytes], as hex: a cheap content fingerprint for ETags. */
internal fun fingerprint(bytes: ByteArray): String {
    var hash = 0xcbf29ce484222325uL
    for (byte in bytes) {
        hash = hash xor (byte.toULong() and 0xffuL)
        hash *= 0x100000001b3uL
    }
    return hash.toString(16)
}
