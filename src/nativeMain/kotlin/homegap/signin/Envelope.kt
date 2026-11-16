package homegap.signin

import homegap.VERSION
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The cookie of the Pangolin apps that holds the session. */
const val SESSION_COOKIE = "p_session_token"

suspend fun ApplicationCall.answer(data: JsonElement) = envelope(HttpStatusCode.OK, data, "ok")

suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String) = envelope(status, JsonNull, message)

fun ApplicationCall.sessionToken(): String? = request.cookies[SESSION_COOKIE]?.takeIf { it.isNotBlank() }

/** The form of each answer of the Pangolin API. */
private suspend fun ApplicationCall.envelope(status: HttpStatusCode, data: JsonElement, message: String) {
    val ok = status.value < HttpStatusCode.BadRequest.value
    val body = buildJsonObject {
        put("data", data)
        put("success", ok)
        put("error", !ok)
        put("message", message)
        put("status", status.value)
    }
    response.header("Cache-Control", "no-store")
    response.header("X-HomeGAP-Version", VERSION)
    respondText(body.toString(), ContentType.Application.Json, status)
}
