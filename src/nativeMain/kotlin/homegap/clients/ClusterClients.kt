package homegap.clients

import homegap.platform.Subprocess
import homegap.platform.runCatchingCancellable
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val lenient = Json { ignoreUnknownKeys = true }

private const val SENTINEL_PORT = 26379
private const val VALKEY_PORT = 6379
private val CLI_TIMEOUT = 5.seconds

/**
 * The v3 HTTP gateway of etcd: cluster membership and authentication. A new member joins as a
 * learner, and a later call promotes it. With [user] and [password], a call signs in when etcd
 * asks for it. The client works with authentication off and on.
 */
class EtcdClient(
    private val http: HttpClient,
    private val base: String,
    private val user: String? = null,
    private val password: String? = null,
) {
    data class Member(val id: String, val name: String, val peerUrls: List<String>, val learner: Boolean)

    private var token: String? = null

    /** True if etcd asks for a user. Signs in as a side effect. */
    suspend fun authEnabled(): Boolean = signIn()

    /** Turns authentication on, with [user] as the only user. It has the `root` role. */
    suspend fun enableAuth() {
        val name = requireNotNull(user)
        val secret = requireNotNull(password)
        val userWithPassword = buildJsonObject {
            put("name", name)
            put("password", secret)
        }
        val rootRole = buildJsonObject { put("name", ROOT_ROLE) }
        val grant = buildJsonObject {
            put("user", name)
            put("role", ROOT_ROLE)
        }
        createIfMissing("/v3/auth/user/add", userWithPassword)
        createIfMissing("/v3/auth/role/add", rootRole)
        post("/v3/auth/user/grant", grant)
        post("/v3/auth/enable", buildJsonObject {})
        check(signIn()) { "etcd did not turn authentication on" }
    }

    /** Creates a user or a role. An answer that it exists already is not an error. */
    private suspend fun createIfMissing(path: String, body: JsonObject) {
        runCatching { post(path, body) }.onFailure { if ("already exists" !in it.message.orEmpty()) throw it }
    }

    /** Gets a token. False if authentication is off. */
    private suspend fun signIn(): Boolean {
        val name = user ?: return false
        val secret = password?.takeIf { it.isNotEmpty() } ?: return false
        val credentials = buildJsonObject {
            put("name", name)
            put("password", secret)
        }
        token = null
        return try {
            token = post("/v3/auth/authenticate", credentials)["token"]?.jsonPrimitive?.content
            token != null
        } catch (e: IllegalStateException) {
            if (AUTH_OFF in e.message.orEmpty()) false else throw e
        }
    }

    suspend fun healthy(): Boolean = runCatchingCancellable {
        val response = http.get("$base/health") { timeout { requestTimeoutMillis = HEALTH_TIMEOUT_MS } }
        response.status.isSuccess() && "\"true\"" in response.bodyAsText()
    }.getOrDefault(false)

    suspend fun members(): List<Member> =
        call("/v3/cluster/member/list", buildJsonObject {})["members"]
            ?.jsonArray
            .orEmpty()
            .map { it.jsonObject.toMember() }

    /** Adds a learner and returns its id. A learner does not count towards quorum before its promotion. */
    suspend fun addLearner(peerUrl: String): String {
        val learner = buildJsonObject {
            put("peerURLs", JsonArray(listOf(JsonPrimitive(peerUrl))))
            put("isLearner", true)
        }
        return call("/v3/cluster/member/add", learner)["member"]!!.jsonObject.toMember().id
    }

    /** Fails while the learner is not in sync yet. The caller tries again in a later pass. */
    suspend fun promote(id: String) {
        call("/v3/cluster/member/promote", buildJsonObject { put("ID", id) })
    }

    suspend fun remove(id: String) {
        call("/v3/cluster/member/remove", buildJsonObject { put("ID", id) })
    }

    /** A call that signs in again when etcd refuses the token, or has none. */
    private suspend fun call(path: String, body: JsonObject): JsonObject = try {
        post(path, body)
    } catch (e: IllegalStateException) {
        if (AUTH_ERRORS.none { it in e.message.orEmpty() } || !signIn()) throw e
        post(path, body)
    }

    private suspend fun post(path: String, body: JsonObject): JsonObject {
        val response = http.post("$base$path") {
            contentType(ContentType.Application.Json)
            token?.let { header(HttpHeaders.Authorization, it) }
            setBody(body.toString())
            timeout { requestTimeoutMillis = CALL_TIMEOUT_MS }
        }
        val text = response.bodyAsText()
        check(response.status.isSuccess()) {
            val message = runCatching {
                lenient.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull()
            "etcd ${path.substringAfterLast('/')}: ${message ?: "HTTP ${response.status.value}"}"
        }
        return lenient.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.toMember() = Member(
        id = this["ID"]?.jsonPrimitive?.content.orEmpty(),
        name = this["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        peerUrls = this["peerURLs"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        learner = this["isLearner"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    private companion object {
        const val ROOT_ROLE = "root"
        const val AUTH_OFF = "authentication is not enabled"
        const val HEALTH_TIMEOUT_MS = 5_000L
        const val CALL_TIMEOUT_MS = 10_000L
        val AUTH_ERRORS = listOf(
            "user name is empty",
            "invalid auth token",
            "permission denied",
            "revision of auth store is old",
        )
    }
}

/** The REST API of Patroni on each member (port 8008 of its mesh address). */
class PatroniClient(private val http: HttpClient, user: String, password: String) {
    private val auth = "Basic " + Base64.encode("$user:$password".encodeToByteArray())

    data class Member(val name: String, val role: String, val state: String, val lagBytes: Long?, val timeline: Int?)

    /** The whole cluster as [baseUrl]'s member sees it. */
    suspend fun cluster(baseUrl: String): List<Member> {
        val response = http.get("$baseUrl/cluster") { timeout { requestTimeoutMillis = CLUSTER_TIMEOUT_MS } }
        check(response.status.isSuccess()) { "Patroni at $baseUrl: HTTP ${response.status.value}" }
        val members = lenient.parseToJsonElement(response.bodyAsText()).jsonObject["members"]?.jsonArray
        return members.orEmpty().map {
            val member = it.jsonObject
            Member(
                name = member["name"]?.jsonPrimitive?.content.orEmpty(),
                role = member["role"]?.jsonPrimitive?.content.orEmpty(),
                state = member["state"]?.jsonPrimitive?.content.orEmpty(),
                lagBytes = member["lag"]?.jsonPrimitive?.longOrNull,
                timeline = member["timeline"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    /**
     * When the replica at [baseUrl] last replayed a transaction (epoch ms), from its `/patroni`
     * page. Null on a primary, when there is no answer, or before the first replayed transaction.
     */
    suspend fun replayedAt(baseUrl: String): Long? = runCatchingCancellable {
        val response = http.get("$baseUrl/patroni") { timeout { requestTimeoutMillis = REPLAY_TIMEOUT_MS } }
        if (!response.status.isSuccess()) return null
        val stamp = lenient.parseToJsonElement(response.bodyAsText())
            .jsonObject["xlog"]
            ?.jsonObject
            ?.get("replayed_timestamp")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null
        // Patroni writes "2026-11-11 12:34:56.123+00:00".
        Instant.parse(stamp.replaceFirst(' ', 'T')).toEpochMilliseconds()
    }.getOrNull()

    /** A planned switchover: no data loss, writes pause for a moment. */
    suspend fun switchover(baseUrl: String, leader: String, candidate: String) =
        post(
            url = "$baseUrl/switchover",
            body = buildJsonObject {
                put("leader", leader)
                put("candidate", candidate)
            },
        )

    private suspend fun post(url: String, body: JsonObject?) {
        val response = http.post(url) {
            header(HttpHeaders.Authorization, auth)
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it.toString())
            }
            timeout { requestTimeoutMillis = POST_TIMEOUT_MS }
        }
        check(response.status.isSuccess()) {
            val detail = response.bodyAsText().take(ERROR_BODY_LENGTH)
            "Patroni ${url.substringAfterLast('/')}: HTTP ${response.status.value} $detail"
        }
    }

    private companion object {
        const val CLUSTER_TIMEOUT_MS = 5_000L
        const val REPLAY_TIMEOUT_MS = 3_000L
        const val POST_TIMEOUT_MS = 60_000L
        const val ERROR_BODY_LENGTH = 200
    }
}

/**
 * Valkey and its Sentinels, through `redis-cli` (the passwords travel in the environment).
 * [sentinelPassword] is Sentinel's own password.
 */
class ValkeyCli(private val password: String, private val sentinelPassword: String) {
    suspend fun sentinelMaster(host: String, name: String): String? =
        sentinel(host, "get-master-addr-by-name", name)
            ?.lines()
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    suspend fun sentinelFailover(host: String, name: String): Boolean =
        sentinel(host, "FAILOVER", name)?.trim() == "OK"

    /**
     * The Sentinel's view of [name]: its master's flags and the addresses of the replicas and
     * other Sentinels it knows (it never forgets one by itself). Null when there is no answer.
     */
    suspend fun sentinelView(host: String, name: String): SentinelView? {
        val master = sentinel(host, "MASTER", name)?.let(::fields)?.firstOrNull() ?: return null
        val replicas = sentinel(host, "REPLICAS", name)?.let(::fields) ?: return null
        val sentinels = sentinel(host, "SENTINELS", name)?.let(::fields) ?: return null
        return SentinelView(
            flags = master["flags"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet(),
            known = (replicas + sentinels).mapNotNull { it["ip"] }.toSet(),
        )
    }

    /** `SENTINEL RESET`: forgets every replica and Sentinel, then rediscovers the live ones. */
    suspend fun sentinelReset(host: String, name: String): Boolean =
        sentinel(host, "RESET", name)?.trim() == "1"

    data class SentinelView(val flags: Set<String>, val known: Set<String>)

    /** `INFO replication` as key/value pairs, or null when there is no answer. */
    suspend fun replication(host: String): Map<String, String>? =
        cli(host, VALKEY_PORT, "INFO", "replication", auth = true)
            ?.lines()
            ?.mapNotNull { keyValue(it.trim()) }
            ?.toMap()

    /** Splits `key:value` at the first colon. Null for a line with no key. */
    private fun keyValue(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        return if (colon > 0) line.substring(0, colon) to line.substring(colon + 1) else null
    }

    private suspend fun sentinel(host: String, command: String, name: String): String? =
        cli(host, SENTINEL_PORT, "SENTINEL", command, name, auth = false)

    /** With [auth], the call uses the Valkey password. Without it, the Sentinel password. */
    private suspend fun cli(host: String, port: Int, vararg args: String, auth: Boolean): String? {
        val argv = listOf("redis-cli", "-h", host, "-p", port.toString(), "--raw", "--no-auth-warning") + args
        val secret = if (auth) password else sentinelPassword
        val result = runCatchingCancellable {
            Subprocess.run(argv, timeout = CLI_TIMEOUT, env = mapOf("REDISCLI_AUTH" to secret))
        }.getOrNull() ?: return null
        val output = result.stdout
        return output.takeIf { result.ok && !output.startsWith("ERR") && !output.contains("Could not connect") }
    }

    companion object {
        /**
         * `redis-cli --raw` prints a list of maps as alternating key and value lines. A new map
         * starts at each `name` key (always the first). Values never contain newlines here.
         */
        fun fields(raw: String): List<Map<String, String>> {
            val lines = raw.lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
            val maps = mutableListOf<MutableMap<String, String>>()
            for (i in 0 until lines.size - 1 step 2) {
                if (lines[i] == "name" || maps.isEmpty()) {
                    maps += mutableMapOf()
                }
                maps.last()[lines[i]] = lines[i + 1]
            }
            return maps
        }
    }
}
