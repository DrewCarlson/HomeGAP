package homegap.acme

import homegap.clients.BunnyClient
import homegap.config.AppConfig
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.Subprocess
import homegap.platform.env
import homegap.platform.nowMillis
import homegap.provision.NodeTemplates
import homegap.reconcile.ReconcileContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    data class Meta(val names: List<String>, val directory: String, val certName: String, val notBefore: Long, val notAfter: Long)

    private val metaFile = "$dir/node-cert.json"
    private val certFile = "$dir/node.crt"
    private val keyFile = "$dir/node.key"
    private val store = "$dir/lego"
    private val domain = "certificates"

    /** After a failed order, the next order of the same names waits. Let's Encrypt limits failures. */
    @kotlin.concurrent.Volatile
    private var retryAt = 0L
    @kotlin.concurrent.Volatile
    private var failedNames: List<String>? = null

    /** True while an order runs outside the pass. */
    @kotlin.concurrent.Volatile
    private var ordering = false

    /** An order writes three files. A reader gets all three from the same order. */
    private val files = SynchronizedObject()

    fun meta(): Meta? = Files.readTextOrNull(metaFile)?.let { runCatching { Json.decodeFromString<Meta>(it) }.getOrNull() }

    fun current(): CertMaterial? = synchronized(files) {
        val meta = meta() ?: return null
        val cert = Files.readTextOrNull(certFile) ?: return null
        val key = Files.readTextOrNull(keyFile) ?: return null
        CertMaterial(cert, key, meta.names, meta.notAfter)
    }

    /** Why a new order is due, or null. */
    fun due(meta: Meta?, names: List<String>, directory: String, nowSeconds: Long): String? = when {
        meta == null -> "there is no certificate"
        meta.names != names -> "the names changed"
        meta.directory != directory -> "the ACME directory changed"
        nowSeconds >= meta.notAfter - (meta.notAfter - meta.notBefore) / 3 -> "it expires soon"
        else -> null
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
        val config = ctx.config
        val directory = config.acme.directoryUrl()
        val now = nowMillis()
        val old = meta()
        val reason = due(old, names, directory, now / 1000) ?: return current()
        ctx.diff.add(domain, "nodes", "certificate", if (old == null) "create" else "update", names.joinToString(), old?.names?.joinToString())
        if (ctx.dryRun || ctx.pauses().global) return current()
        if (ordering || (failedNames == names && now < retryAt)) return current()
        val order: suspend () -> Unit = {
            try {
                if (bunny == null) error("the Bunny API key ${config.bunny.apiKey.describe()} is not set")
                if (names.size > CertNames.MAX) error("${names.size} names do not fit on one certificate (the limit is ${CertNames.MAX})")
                val meta = order(ctx, names, directory, bunny, now)
                failedNames = null
                ctx.log.change(domain, "ordered the certificate of the nodes for ${names.size} names ($reason); valid for ${(meta.notAfter - now / 1000) / 86400} days")
                if (old != null && old.directory == directory) retire(config, old, revoke = old.names.any { it !in names }, ctx)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failedNames = names
                retryAt = nowMillis() + RETRY_MS
                ctx.log.error(domain, "cannot order the certificate of the nodes ($reason): ${e.message}. The next try is in ${RETRY_MS / 60_000} minutes.")
            } finally {
                ordering = false
            }
        }
        ordering = true
        if (background == null) order() else {
            ctx.log.info(domain, "ordering the certificate of the nodes for ${names.size} names ($reason)")
            background(order)
        }
        return current()
    }

    private suspend fun order(ctx: ReconcileContext, names: List<String>, directory: String, bunny: BunnyClient, now: Long): Meta {
        val config = ctx.config
        val zoneId = ctx.state.snapshot().bunnyZoneId ?: bunny.findZone(config.bunny.zone)?.id
            ?: error("Bunny zone ${config.bunny.zone} not found")
        val dns = DnsChallenge(bunny, zoneId, config.bunny.zone, names)
        dns.sweep()
        Files.mkdirs(dir)
        Files.chmodPath(dir, "700".toInt(8))
        val certName = "homegap-${now / 1000}"
        val server = ChallengeServer(dns)
        // Lego waits for the record on the name servers of the zone only, as Let's Encrypt reads it
        // there. A resolver can hold the answer "no such record" from before the record was made.
        val result = server.use { endpoint ->
            Subprocess.run(
                account(config, directory, "run") + listOf("--accept-tos", "--dns", "httpreq", "--dns.propagation.disable-rns", "--cert.name", certName, "--key-type", "EC256") + names.flatMap { listOf("-d", it) },
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
        if (!result.ok) error("lego exit ${result.exitCode}: ${failure(result.stderr.ifBlank { result.stdout })}")
        val cert = Files.readText("$store/certificates/$certName.crt")
        val key = Files.readText("$store/certificates/$certName.key")
        val validity = X509.validity(cert) ?: error("cannot read the new certificate")
        val meta = Meta(names, directory, certName, validity.notBefore, validity.notAfter)
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
    private suspend fun retire(config: AppConfig, old: Meta, revoke: Boolean, ctx: ReconcileContext) {
        if (revoke) {
            val r = runCatching {
                Subprocess.run(account(config, old.directory, "certificates", "revoke") + listOf("--cert.name", old.certName, "--keep", "--reason", SUPERSEDED), timeout = 2.minutes, env = mapOf("LEGO_LOG_FORMAT" to "text"))
            }.getOrNull()
            if (r?.ok == true) ctx.log.change(domain, "revoked the old certificate of the nodes, because a name left the list")
            else ctx.log.warn(domain, "cannot revoke the old certificate of the nodes: ${r?.stderr?.trim()?.takeLast(300) ?: "lego did not start"}")
        }
        for (ext in listOf("crt", "issuer.crt", "key", "json")) Files.delete("$store/certificates/${old.certName}.$ext")
    }

    private fun account(config: AppConfig, directory: String, vararg command: String): List<String> =
        listOf(lego) + command + listOf("--path", store, "--server", directory, "--email", email(config))

    private fun email(config: AppConfig) = config.acme.email ?: config.pangolin.acmeEmail ?: NodeTemplates.adminEmail(config)

    companion object {
        const val RETRY_MS = 60 * 60_000L
        /** RFC 5280 reason code. */
        private const val SUPERSEDED = "4"
    }
}
