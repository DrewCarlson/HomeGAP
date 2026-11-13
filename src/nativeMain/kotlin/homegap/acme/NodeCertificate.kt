package homegap.acme

import homegap.clients.BunnyClient
import homegap.config.AppConfig
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.Subprocess
import homegap.platform.env
import homegap.platform.nowMillis
import homegap.platform.runCatchingCancellable
import homegap.provision.NodeTemplates
import homegap.reconcile.ReconcileContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes

/** The certificate and key that every node serves, with the names on the certificate. */
data class CertMaterial(val certPem: String, val keyPem: String, val names: List<String>, val notAfter: Long) {
    val hash: String get() = Crypto.fnv64(certPem + keyPem)
}

/**
 * The one certificate of the nodes. The homelab orders it with the DNS challenge and keeps it in
 * [dir]. A node never orders a certificate.
 *
 * Each order makes a new key. HomeGAP orders again when the list of names changes, when the ACME
 * directory changes, and when one third of the lifetime remains. If a name leaves the list,
 * HomeGAP revokes the old certificate, because the nodes held its key.
 */
class NodeCertificate(private val dir: String, private val lego: String = env("HOMEGAP_LEGO") ?: "lego") {
    @Serializable
    data class Meta(
        val names: List<String>,
        val directory: String,
        val certName: String,
        val notBefore: Long,
        val notAfter: Long,
    )

    /** What one order needs. [now] is the time of the pass in epoch ms, and [old] the certificate before the order. */
    private class OrderRequest(
        val ctx: ReconcileContext,
        val names: List<String>,
        val directory: String,
        val bunny: BunnyClient?,
        val now: Long,
        val old: Meta?,
        val reason: String,
        val replaceKey: Boolean,
    ) {
        val config = ctx.config
    }

    private val metaFile = "$dir/node-cert.json"
    private val certFile = "$dir/node.crt"
    private val keyFile = "$dir/node.key"
    private val store = "$dir/lego"
    private val domain = "certificates"

    /** The state of the order and the last orders. */
    val orders = CertOrderStore(dir)

    /** The class calls this when the state of the order changes, so the dashboard shows each step. */
    @Volatile
    var onChange: () -> Unit = {}

    /** True while an order runs outside the pass. */
    @Volatile
    private var ordering = false

    /** An order that the dashboard asked for: its reason, and whether to revoke the old certificate after it. */
    @Volatile
    private var requested: Pair<String, Boolean>? = null

    /** An order writes three files. A reader gets all three from the same order. */
    private val files = SynchronizedObject()

    @Volatile
    private var infoCache: Pair<String, X509.Info?>? = null

    fun meta(): Meta? =
        Files.readTextOrNull(metaFile)?.let { runCatching { Json.decodeFromString<Meta>(it) }.getOrNull() }

    fun current(): CertMaterial? = synchronized(files) {
        val meta = meta() ?: return null
        val cert = Files.readTextOrNull(certFile) ?: return null
        val key = Files.readTextOrNull(keyFile) ?: return null
        CertMaterial(cert, key, meta.names, meta.notAfter)
    }

    /** The public facts of the current certificate, read once for each certificate. */
    fun info(): X509.Info? {
        val cert = synchronized(files) { Files.readTextOrNull(certFile) } ?: return null
        val hash = Crypto.fnv64(cert)
        infoCache?.takeIf { it.first == hash }?.let { return it.second }
        return X509.info(cert).also { infoCache = hash to it }
    }

    /** Why a new order is due, or null. */
    fun due(meta: Meta?, names: List<String>, directory: String, nowSeconds: Long): String? = when {
        meta == null -> "there is no certificate"
        meta.names != names -> "the names changed"
        meta.directory != directory -> "the ACME directory changed"
        nowSeconds >= renewAt(meta) -> "it expires soon"
        else -> null
    }

    fun renewAt(meta: Meta): Long = meta.notAfter - (meta.notAfter - meta.notBefore) / 3

    /** True while an order runs. */
    val running: Boolean get() = ordering

    /**
     * Asks for an order in the next pass, even if none is due, and without the wait after a
     * failure. With [replaceKey], HomeGAP revokes the old certificate after the new one is out.
     * Returns why it cannot start, or null.
     */
    fun request(replaceKey: Boolean): String? {
        if (ordering) return "an order runs already"
        val failures = orders.state().failuresSince(nowMillis() - HOUR_MS)
        if (failures >= RATE_LIMIT) {
            return "$failures orders failed in the last hour, and Let's Encrypt allows $RATE_LIMIT for each name. " +
                "Wait for the next hour."
        }
        val reason = if (replaceKey) "the key was replaced from the dashboard" else "ordered from the dashboard"
        requested = reason to replaceKey
        return null
    }

    /**
     * Returns the certificate for [names], and starts an order if one is due. If the order fails,
     * the nodes keep the certificate that they have.
     *
     * An order can take minutes. With [background], the order runs there and the pass continues
     * with the certificate that is there now. [background] gets the order and runs it outside the
     * pass. Without it, the order runs before this function returns.
     */
    suspend fun ensure(
        ctx: ReconcileContext,
        names: List<String>,
        bunny: BunnyClient?,
        background: ((suspend () -> Unit) -> Unit)? = null,
    ): CertMaterial? {
        if (names.isEmpty()) return null
        val directory = ctx.config.acme.directoryUrl()
        val now = nowMillis()
        val old = meta()
        val asked = requested
        val reason = asked?.first ?: due(old, names, directory, now / 1000) ?: return current()
        ctx.diff.add(
            domain = domain,
            target = "nodes",
            key = "certificate",
            action = if (old == null) "create" else "update",
            desired = names.joinToString(),
            actual = old?.names?.joinToString(),
        )
        if (ctx.dryRun || ctx.pauses().global) return current()
        val state = orders.state()
        if (ordering) return current()
        val retryLater = state.state == CertOrderStore.FAILED &&
            state.failedNames == names &&
            now < (state.retryAt ?: 0)
        if (asked == null && retryLater) return current()
        requested = null
        val request = OrderRequest(
            ctx = ctx,
            names = names,
            directory = directory,
            bunny = bunny,
            now = now,
            old = old,
            reason = reason,
            replaceKey = asked?.second == true,
        )
        val order: suspend () -> Unit = { runOrder(request) }
        ordering = true
        if (background == null) {
            order()
        } else {
            ctx.log.info(domain, "ordering the certificate of the nodes for ${names.size} names ($reason)")
            background(order)
        }
        return current()
    }

    /** Runs one order to its end. An error does not leave this function: the state and the log hold it. */
    private suspend fun runOrder(request: OrderRequest) {
        val started = nowMillis()
        try {
            issue(request, started)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            recordFailure(request, started, e.message.orEmpty())
        } finally {
            ordering = false
            onChange()
        }
    }

    private suspend fun issue(request: OrderRequest, started: Long) {
        val names = request.names
        val reason = request.reason
        step("records", started, reason, names)
        val bunny = request.bunny ?: error("the Bunny API key ${request.config.bunny.apiKey.describe()} is not set")
        check(names.size <= CertNames.MAX) {
            "${names.size} names do not fit on one certificate (the limit is ${CertNames.MAX})"
        }
        val meta = order(request, bunny)
        val issued = current()
        val version = info()?.fingerprint?.let(::versionOf)
        orders.add(
            CertOrderStore.Record(
                id = Crypto.token(10),
                kind = "order",
                at = started,
                reason = reason,
                names = names,
                ok = true,
                durationMs = nowMillis() - started,
                version = version,
                notBefore = meta.notBefore,
                hash = issued?.hash,
            ),
        )
        orders.update {
            it.copy(
                state = CertOrderStore.IDLE,
                step = null,
                cause = null,
                causeText = null,
                failedNames = emptyList(),
                retryAt = null,
                failures = it.failures.filter { time -> time >= nowMillis() - HOUR_MS },
            )
        }
        val validDays = (meta.notAfter - request.now / 1000) / SECONDS_IN_DAY
        request.ctx.log.change(
            domain,
            "ordered the certificate of the nodes for ${names.size} names ($reason); valid for $validDays days",
        )
        val old = request.old
        if (old != null && old.directory == request.directory) {
            val why = if (request.replaceKey) "the key was replaced" else "a name left the list"
            retire(request, old, revoke = request.replaceKey || old.names.any { it !in names }, why = why)
        }
    }

    private fun recordFailure(request: OrderRequest, started: Long, message: String) {
        val cause = OrderCause.of(message)
        val failedAt = nowMillis()
        val retryAt = failedAt + RETRY_MS
        val id = Crypto.token(10)
        orders.add(
            CertOrderStore.Record(
                id = id,
                kind = "order",
                at = started,
                reason = request.reason,
                names = request.names,
                ok = false,
                durationMs = failedAt - started,
                cause = cause.code,
                causeText = cause.text,
            ),
        )
        orders.update {
            it.copy(
                state = CertOrderStore.FAILED,
                step = null,
                cause = cause.code,
                causeText = cause.text,
                failedNames = request.names,
                failedAt = failedAt,
                retryAt = retryAt,
                failures = it.failures.filter { time -> time >= failedAt - HOUR_MS } + failedAt,
            )
        }
        request.ctx.log.error(
            domain,
            "The certificate order failed: ${cause.text}. The nodes keep the certificate they have. " +
                "The next try is in ${RETRY_MS / 60_000} minutes.",
            fields = mapOf("cause" to cause.code, "order" to id, "detail" to OrderCause.redact(message).take(800)),
        )
    }

    private fun step(step: String, startedAt: Long? = null, reason: String? = null, names: List<String>? = null) {
        orders.update {
            it.copy(
                state = CertOrderStore.RUNNING,
                step = step,
                startedAt = startedAt ?: it.startedAt,
                reason = reason ?: it.reason,
                names = names ?: it.names,
            )
        }
        onChange()
    }

    private suspend fun order(request: OrderRequest, bunny: BunnyClient): Meta {
        val config = request.config
        val names = request.names
        val zoneId = request.ctx.state.snapshot().bunnyZoneId
            ?: bunny.findZone(config.bunny.zone)?.id
            ?: error("Bunny zone ${config.bunny.zone} not found")
        val dns = DnsChallenge(
            bunny = bunny,
            zoneId = zoneId,
            zone = config.bunny.zone,
            names = names,
            onPresent = { step("dns") },
            onCleanup = { step("validated") },
        )
        dns.sweep()
        Files.mkdirs(dir)
        Files.chmodPath(dir, "700".toInt(8))
        val certName = "homegap-${request.now / 1000}"
        val server = ChallengeServer(dns)
        // Lego waits for the record on the name servers of the zone only, as Let's Encrypt reads it
        // there. A resolver can hold the answer "no such record" from before the record was made.
        val result = server.use { endpoint ->
            val argv = buildList {
                addAll(account(config, request.directory, "run"))
                addAll(listOf("--accept-tos", "--dns", "httpreq", "--dns.propagation.disable-rns"))
                addAll(listOf("--cert.name", certName, "--key-type", "EC256"))
                names.forEach { addAll(listOf("-d", it)) }
            }
            Subprocess.run(
                argv,
                timeout = 20.minutes,
                env = mapOf(
                    "HTTPREQ_ENDPOINT" to endpoint,
                    "HTTPREQ_USERNAME" to server.user,
                    "HTTPREQ_PASSWORD" to server.password,
                    "HTTPREQ_PROPAGATION_TIMEOUT" to "300",
                    "LEGO_LOG_FORMAT" to "text",
                ),
            )
        }
        check(result.ok) { "lego exit ${result.exitCode}: ${failure(result.stderr.ifBlank { result.stdout })}" }
        step("issued")
        val cert = Files.readText("$store/certificates/$certName.crt")
        val key = Files.readText("$store/certificates/$certName.key")
        val validity = X509.validity(cert) ?: error("cannot read the new certificate")
        val meta = Meta(names, request.directory, certName, validity.notBefore, validity.notAfter)
        synchronized(files) {
            Files.writeAtomic(keyFile, key, "600".toInt(8))
            Files.writeAtomic(certFile, cert, "600".toInt(8))
            Files.writeAtomic(metaFile, Json.encodeToString(meta), "600".toInt(8))
        }
        return meta
    }

    /** The error lines of Lego's [output], or its end if Lego marks no line as an error. */
    internal fun failure(output: String): String {
        val errors = output.lines().filter { "level=ERROR" in it }.map { it.substringAfter("level=ERROR").trim() }
        return (if (errors.isEmpty()) output.trim() else errors.joinToString(" ")).takeLast(800)
    }

    /** Removes the files of the certificate [old] from Lego's store, and revokes it first if [revoke]. */
    private suspend fun retire(request: OrderRequest, old: Meta, revoke: Boolean, why: String) {
        if (revoke) {
            revoke(request, old, why)
        }
        for (extension in listOf("crt", "issuer.crt", "key", "json")) {
            Files.delete("$store/certificates/${old.certName}.$extension")
        }
    }

    private suspend fun revoke(request: OrderRequest, old: Meta, why: String) {
        val log = request.ctx.log
        val started = nowMillis()
        val result = runCatchingCancellable {
            Subprocess.run(
                account(request.config, old.directory, "certificates", "revoke") +
                    listOf("--cert.name", old.certName, "--keep", "--reason", SUPERSEDED),
                timeout = 2.minutes,
                env = mapOf("LEGO_LOG_FORMAT" to "text"),
            )
        }.getOrNull()
        val ok = result?.ok == true
        orders.add(
            CertOrderStore.Record(
                id = Crypto.token(10),
                kind = "revoke",
                at = started,
                reason = why,
                names = old.names,
                ok = ok,
                durationMs = nowMillis() - started,
                causeText = if (ok) null else "Let's Encrypt did not revoke it",
            ),
        )
        if (ok) {
            log.change(domain, "revoked the old certificate of the nodes, because $why")
        } else {
            val detail = result?.stderr?.trim()?.let(OrderCause::redact)?.takeLast(300) ?: "lego did not start"
            log.warn(domain, "cannot revoke the old certificate of the nodes: $detail")
        }
    }

    private fun account(config: AppConfig, directory: String, vararg command: String): List<String> = buildList {
        add(lego)
        addAll(command)
        addAll(listOf("--path", store, "--server", directory, "--email", email(config)))
    }

    fun email(config: AppConfig) = config.acme.email ?: config.pangolin.acmeEmail ?: NodeTemplates.adminEmail(config)

    companion object {
        const val RETRY_MS = 60 * 60_000L
        const val HOUR_MS = 60 * 60_000L

        /** Let's Encrypt allows this many failed orders for each name in an hour. */
        const val RATE_LIMIT = 5

        /** RFC 5280 reason code. */
        private const val SUPERSEDED = "4"

        private const val SECONDS_IN_DAY = 86400

        /** The short version id of a certificate: the start of its fingerprint. */
        fun versionOf(fingerprint: String): String = fingerprint.replace(":", "").take(6).lowercase()
    }
}
