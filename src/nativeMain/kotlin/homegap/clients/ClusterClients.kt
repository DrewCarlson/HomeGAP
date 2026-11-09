package homegap.clients

import homegap.platform.Subprocess
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

private val lenient = Json { ignoreUnknownKeys = true }

/** etcd's v3 HTTP gateway: cluster membership (learners join first, then are promoted). */
class EtcdClient(private val http: HttpClient, private val base: String) {
    data class Member(val id: String, val name: String, val peerUrls: List<String>, val learner: Boolean)

    suspend fun healthy(): Boolean = runCatching {
        val r = http.get("$base/health") { timeout { requestTimeoutMillis = 5_000 } }
        r.status.isSuccess() && "\"true\"" in r.bodyAsText()
    }.getOrDefault(false)

    suspend fun members(): List<Member> =
        call("/v3/cluster/member/list", buildJsonObject {})["members"]?.jsonArray.orEmpty().map { it.jsonObject.toMember() }

    /** Adds a learner (it does not count towards quorum until promoted); returns its id. */
    suspend fun addLearner(peerUrl: String): String =
        call("/v3/cluster/member/add", buildJsonObject {
            put("peerURLs", JsonArray(listOf(JsonPrimitive(peerUrl))))
            put("isLearner", true)
        })["member"]!!.jsonObject.toMember().id

    /** Fails while the learner is still catching up; callers retry on a later pass. */
    suspend fun promote(id: String) {
        call("/v3/cluster/member/promote", buildJsonObject { put("ID", id) })
    }

    suspend fun remove(id: String) {
        call("/v3/cluster/member/remove", buildJsonObject { put("ID", id) })
    }

    private suspend fun call(path: String, body: JsonObject): JsonObject {
        val r = http.post("$base$path") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
            timeout { requestTimeoutMillis = 10_000 }
        }
        val text = r.bodyAsText()
        if (!r.status.isSuccess()) {
            val message = runCatching { lenient.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
            throw IllegalStateException("etcd ${path.substringAfterLast('/')}: ${message ?: "HTTP ${r.status.value}"}")
        }
        return lenient.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.toMember() = Member(
        id = this["ID"]?.jsonPrimitive?.content.orEmpty(),
        name = this["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        peerUrls = this["peerURLs"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        learner = this["isLearner"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
}

/** Patroni's REST API on each member (`http://<mesh ip>:8008`). */
class PatroniClient(private val http: HttpClient, user: String, password: String) {
    private val auth = "Basic " + Base64.encode("$user:$password".encodeToByteArray())

    data class Member(val name: String, val role: String, val state: String, val lagBytes: Long?, val timeline: Int?)

    /** The whole cluster as [baseUrl]'s member sees it. */
    suspend fun cluster(baseUrl: String): List<Member> {
        val r = http.get("$baseUrl/cluster") { timeout { requestTimeoutMillis = 5_000 } }
        check(r.status.isSuccess()) { "Patroni at $baseUrl: HTTP ${r.status.value}" }
        return lenient.parseToJsonElement(r.bodyAsText()).jsonObject["members"]?.jsonArray.orEmpty().map {
            val m = it.jsonObject
            Member(
                name = m["name"]?.jsonPrimitive?.content.orEmpty(),
                role = m["role"]?.jsonPrimitive?.content.orEmpty(),
                state = m["state"]?.jsonPrimitive?.content.orEmpty(),
                lagBytes = m["lag"]?.jsonPrimitive?.longOrNull,
                timeline = m["timeline"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    /** A planned switchover: no data loss, writes pause for a moment. */
    suspend fun switchover(baseUrl: String, leader: String, candidate: String) =
        post("$baseUrl/switchover", buildJsonObject { put("leader", leader); put("candidate", candidate) })

    /** Re-reads the member's config file. */
    suspend fun reload(baseUrl: String) = post("$baseUrl/reload", null)

    /** The cluster's dynamic configuration (kept in etcd; every member applies it). */
    suspend fun dynamicConfig(baseUrl: String): JsonObject {
        val r = http.get("$baseUrl/config") { header(HttpHeaders.Authorization, auth); timeout { requestTimeoutMillis = 5_000 } }
        check(r.status.isSuccess()) { "Patroni config: HTTP ${r.status.value}" }
        return lenient.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    /** Merges [patch] into the dynamic configuration; members reload it themselves. */
    suspend fun patchConfig(baseUrl: String, patch: JsonObject) = post(baseUrl + "/config", patch, HttpMethod.Patch)

    private suspend fun post(url: String, body: JsonObject?, method: HttpMethod = HttpMethod.Post) {
        val r = http.request(url) {
            this.method = method
            header(HttpHeaders.Authorization, auth)
            body?.let { contentType(ContentType.Application.Json); setBody(it.toString()) }
            timeout { requestTimeoutMillis = 60_000 }
        }
        check(r.status.isSuccess()) { "Patroni ${url.substringAfterLast('/')}: HTTP ${r.status.value} ${r.bodyAsText().take(200)}" }
    }
}

/** Valkey and its Sentinels, through `redis-cli` (the password travels in the environment). */
class ValkeyCli(private val password: String) {
    suspend fun sentinelMaster(host: String, name: String): String? =
        cli(host, 26379, "SENTINEL", "get-master-addr-by-name", name, auth = false)?.lines()?.firstOrNull()?.takeIf { it.isNotBlank() }

    suspend fun sentinelFailover(host: String, name: String): Boolean =
        cli(host, 26379, "SENTINEL", "FAILOVER", name, auth = false)?.trim() == "OK"

    /** `INFO replication` as key/value pairs, or null when unreachable. */
    suspend fun replication(host: String): Map<String, String>? =
        cli(host, 6379, "INFO", "replication", auth = true)?.lines()?.mapNotNull { l ->
            l.trim().indexOf(':').takeIf { it > 0 }?.let { l.trim().substring(0, it) to l.trim().substring(it + 1) }
        }?.toMap()

    private suspend fun cli(host: String, port: Int, vararg args: String, auth: Boolean): String? {
        val argv = listOf("redis-cli", "-h", host, "-p", port.toString(), "--raw") + args
        val env = if (auth) mapOf("REDISCLI_AUTH" to password) else emptyMap()
        val r = runCatching { Subprocess.run(argv, timeout = 5.seconds, env = env) }.getOrNull() ?: return null
        return r.stdout.takeIf { r.ok && !r.stdout.startsWith("ERR") && !r.stdout.contains("Could not connect") }
    }
}
