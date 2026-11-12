package homegap.acme

import homegap.platform.Crypto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

/**
 * The endpoint that Lego's `httpreq` DNS provider calls. It listens on the loopback address for
 * the time of one order and accepts only the password of that order.
 */
class ChallengeServer(private val dns: DnsChallenge) {
    val user = "homegap"
    val password = Crypto.token(32)
    private val expected = "Basic " + Base64.encode("$user:$password".encodeToByteArray())

    /** Runs [block] with the address of the endpoint, then stops the endpoint. */
    suspend fun <T> use(block: suspend (endpoint: String) -> T): T {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                post("/present") { handle(call) { fqdn, value -> dns.present(fqdn, value) } }
                post("/cleanup") { handle(call) { fqdn, value -> dns.cleanup(fqdn, value) } }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            return block("http://127.0.0.1:$port")
        } finally {
            server.stop(500, 2000)
        }
    }

    private suspend fun handle(call: ApplicationCall, action: suspend (String, String) -> Unit) {
        if (!Crypto.constantTimeEquals(call.request.headers[HttpHeaders.Authorization].orEmpty(), expected)) {
            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
            return
        }
        try {
            val body = Json.parseToJsonElement(call.receiveText()).jsonObject
            action(body.getValue("fqdn").jsonPrimitive.content, body.getValue("value").jsonPrimitive.content)
            call.respondText("ok")
        } catch (e: Exception) {
            call.respondText(e.message ?: "failed", status = HttpStatusCode.BadRequest)
        }
    }
}
