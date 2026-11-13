package homegap.acme

import homegap.api.CaaStatusDto
import homegap.api.CertDeliveryDto
import homegap.api.CertNameDto
import homegap.api.CertOrderDto
import homegap.api.CertOrderRecordDto
import homegap.api.CertProbeDto
import homegap.api.CertProbeRowDto
import homegap.api.CertificatesDto
import homegap.api.IssuedCertificateDto
import homegap.config.AcmeConfig
import homegap.config.AppConfig
import homegap.config.NodeConfig
import homegap.model.DesiredState

/** Builds the `certificates` part of the status. It holds public facts only. */
object CertificateStatus {
    /**
     * The last probes of one node. [installed] is the version of the certificate on the node and
     * [installedAt] the time of its last write, in epoch seconds. [at] is in epoch ms.
     */
    data class NodeProbes(
        val at: Long,
        val installed: String?,
        val installedAt: Long?,
        val results: Map<String, CertProbes.Result>,
    )

    /** What a pass knows about the certificate. [build] makes the status from it. */
    data class Inputs(
        val config: AppConfig,
        val desired: DesiredState?,
        val meta: NodeCertificate.Meta?,
        val info: X509.Info?,
        val currentHash: String?,
        val renewAt: Long?,
        val order: CertOrderStore.State,
        val running: Boolean,
        val due: String?,
        val history: List<CertOrderStore.Record>,
        val contact: String,
        val probes: Map<String, NodeProbes>,
        /** False for a node that did not answer the last pass: its probes are from before. */
        val reachable: Map<String, Boolean>,
        /** The CAA records of the domain in DNS, as `flags tag "value"`. Null before a DNS pass. */
        val caaLive: List<String>?,
        val nowMs: Long,
    )

    fun build(inputs: Inputs): CertificatesDto = Builder(inputs).build()

    /** The values that the parts of one status share. */
    private class Builder(private val inputs: Inputs) {
        private val config = inputs.config
        private val now = inputs.nowMs / 1000
        private val entries = inputs.desired?.let { CertNames.entries(config, it) }.orEmpty()
        private val wanted = entries.map { it.name }
        private val meta = inputs.meta
        private val version = inputs.info?.fingerprint?.let(NodeCertificate::versionOf)
        private val byHash = inputs.history.filter { it.hash != null }.associateBy { it.hash }
        private val managementNames = config.nodes.associate { it.id to it.managementHost(config.domain).lowercase() }

        fun build(): CertificatesDto {
            val known = inputs.desired != null
            return CertificatesDto(
                names = entries.map { CertNameDto(it.name, it.reason, it.subject) },
                issued = issued(),
                pendingAdd = if (known && meta != null) wanted.filter { it !in meta.names } else emptyList(),
                pendingRemove = if (known && meta != null) meta.names.filter { it !in wanted } else emptyList(),
                directory = config.acme.directoryUrl(),
                staging = config.acme.directoryUrl() == AcmeConfig.STAGING,
                contact = inputs.contact,
                order = order(),
                history = inputs.history.map { record(it) },
                delivery = config.nodes.map { delivery(it) },
                probes = nodeRows() + homelabRows(),
                caa = caa(),
                maxNames = CertNames.MAX,
            )
        }

        private fun issued(): IssuedCertificateDto? {
            val info = inputs.info
            if (meta == null || info == null) return null
            return IssuedCertificateDto(
                names = meta.names,
                issuer = info.issuer,
                notBefore = meta.notBefore,
                notAfter = meta.notAfter,
                renewAt = inputs.renewAt ?: meta.notAfter,
                keyType = info.keyType,
                serial = info.serial,
                fingerprint = info.fingerprint,
                version = version.orEmpty(),
            )
        }

        private fun cell(node: String, name: String): CertProbeDto {
            val probes = inputs.probes[node] ?: return CertProbeDto(node = node, state = "unknown")
            val result = probes.results[name] ?: return CertProbeDto(node = node, state = "unknown")
            return CertProbeDto(
                node = node,
                state = CertProbes.state(name, result, now),
                daysRemaining = CertProbes.daysLeft(result, now),
                subject = result.subject,
                issuer = result.issuer,
                notAfter = result.notAfter,
                probedAt = probes.at / 1000,
                stale = inputs.reachable[node] == false,
            )
        }

        private fun delivery(node: NodeConfig): CertDeliveryDto {
            val probes = inputs.probes[node.id]
            val managementName = managementNames.getValue(node.id)
            val management = cell(node.id, managementName)
            val onCertificate = meta?.names?.contains(managementName) == true
            val installed = probes?.installed
            val record = installed?.let { byHash[it] }
            val current = installed != null && installed == inputs.currentHash
            val serving = management.state == "default" || management.state == "none"
            val state = when {
                probes == null -> "unknown"
                !onCertificate && managementName in wanted && (serving || !current) -> "waiting"
                inputs.reachable[node.id] == false -> "unknown"
                installed == null -> if (management.state == "default") "default" else "none"
                current && management.state == "default" -> "default"
                current -> "ok"
                else -> "behind"
            }
            val missing = when (state) {
                "behind" -> meta?.names.orEmpty().filter { it !in record?.names.orEmpty() }
                else -> emptyList()
            }
            return CertDeliveryDto(
                node = node.id,
                state = state,
                installed = if (current) version else record?.version,
                installedIssuedAt = if (current) meta?.notBefore else record?.notBefore,
                installedAt = probes?.installedAt,
                missing = missing,
                management = management.takeIf { probes != null },
            )
        }

        private fun nodeRows(): List<CertProbeRowDto> = entries.map { entry ->
            CertProbeRowDto(
                name = entry.name,
                kind = "node",
                group = GROUPS[entry.reason] ?: "service",
                cells = config.nodes.map { cell(it.id, entry.name) },
            )
        }

        private fun homelabRows(): List<CertProbeRowDto> =
            inputs.desired
                ?.let(CertNames::passthrough)
                .orEmpty()
                .filter { it !in wanted }
                .map { name ->
                    CertProbeRowDto(
                        name = name,
                        kind = "homelab",
                        group = "homelab",
                        cells = config.nodes.map { cell(it.id, name) },
                    )
                }

        private fun order(): CertOrderDto {
            val order = inputs.order
            val failed = order.state == CertOrderStore.FAILED
            return CertOrderDto(
                state = if (inputs.running) "running" else order.state.takeIf { failed } ?: "idle",
                step = order.step.takeIf { inputs.running },
                startedAt = order.startedAt,
                reason = order.reason,
                names = order.names.size,
                cause = order.cause.takeIf { failed },
                causeText = order.causeText.takeIf { failed },
                failedNames = order.failedNames,
                failedAt = order.failedAt,
                retryAt = order.retryAt.takeIf { failed },
                failuresLastHour = order.failuresSince(inputs.nowMs - NodeCertificate.HOUR_MS),
                due = inputs.due,
            )
        }

        private fun record(record: CertOrderStore.Record) = CertOrderRecordDto(
            id = record.id,
            kind = record.kind,
            at = record.at,
            reason = record.reason,
            names = record.names,
            ok = record.ok,
            durationMs = record.durationMs,
            cause = record.cause,
            causeText = record.causeText,
            version = record.version,
        )

        private fun caa(): CaaStatusDto {
            val desired = when {
                config.acme.caa ->
                    listOf("issue", "issuewild").map { "0 $it \"${config.acme.caaIssuer}; validationmethods=dns-01\"" }
                else -> emptyList()
            }
            val live = inputs.caaLive
            return CaaStatusDto(
                enabled = config.acme.caa,
                desired = desired,
                live = live.orEmpty(),
                missing = if (live == null) emptyList() else desired.filter { it !in live },
                foreign = live.orEmpty().filter { it !in desired },
                known = live != null,
            )
        }
    }

    /** The group of a row in the table of probes, by the reason of the name. Each other reason is `service`. */
    private val GROUPS = mapOf(
        "management" to "management",
        "client" to "shared",
        "status" to "shared",
        "speedtest" to "shared",
    )
}
