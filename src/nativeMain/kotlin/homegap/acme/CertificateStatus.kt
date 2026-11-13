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
import homegap.model.DesiredState

/** Builds the `certificates` part of the status. It holds public facts only. */
object CertificateStatus {
    /**
     * The last probes of one node. [installed] is the version of the certificate on the node and
     * [installedAt] the time it was written, in epoch seconds. [at] is in epoch ms.
     */
    data class NodeProbes(
        val at: Long,
        val installed: String?,
        val installedAt: Long?,
        val results: Map<String, CertProbes.Result>,
    )

    /** What a pass knows about the certificate, gathered for [build]. */
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
        /** The CAA records of the domain in DNS, as `flags tag "value"`; null before a DNS pass. */
        val caaLive: List<String>?,
        val nowMs: Long,
    )

    fun build(inputs: Inputs): CertificatesDto {
        val config = inputs.config
        val now = inputs.nowMs / 1000
        val entries = inputs.desired?.let { CertNames.entries(config, it) }.orEmpty()
        val wanted = entries.map { it.name }
        val meta = inputs.meta
        val info = inputs.info
        val version = info?.fingerprint?.let(NodeCertificate::versionOf)
        val issued = if (meta != null && info != null) {
            IssuedCertificateDto(
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
        } else {
            null
        }
        val known = inputs.desired != null
        val byHash = inputs.history.filter { it.hash != null }.associateBy { it.hash }
        val mgmtNames = config.nodes.associate { it.id to it.managementHost(config.domain).lowercase() }

        fun cell(node: String, name: String): CertProbeDto {
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

        val delivery = config.nodes.map { node ->
            val probes = inputs.probes[node.id]
            val mgmt = mgmtNames.getValue(node.id)
            val management = cell(node.id, mgmt)
            val onCertificate = meta?.names?.contains(mgmt) == true
            val installed = probes?.installed
            val record = installed?.let { byHash[it] }
            val current = installed != null && installed == inputs.currentHash
            val serving = management.state == "default" || management.state == "none"
            val state = when {
                probes == null -> "unknown"
                !onCertificate && mgmt in wanted && (serving || !current) -> "waiting"
                inputs.reachable[node.id] == false -> "unknown"
                installed == null -> if (management.state == "default") "default" else "none"
                current && management.state == "default" -> "default"
                current -> "ok"
                else -> "behind"
            }
            CertDeliveryDto(
                node = node.id,
                state = state,
                installed = if (current) version else record?.version,
                installedIssuedAt = if (current) meta?.notBefore else record?.notBefore,
                installedAt = probes?.installedAt,
                missing = if (state == "behind") meta?.names.orEmpty().filter { it !in record?.names.orEmpty() } else emptyList(),
                management = management.takeIf { probes != null },
            )
        }

        val groups = mapOf("management" to "management", "client" to "shared", "status" to "shared", "speedtest" to "shared")
        val nodeRows = entries.map { entry ->
            CertProbeRowDto(
                name = entry.name,
                kind = "node",
                group = groups[entry.reason] ?: "service",
                cells = config.nodes.map { cell(it.id, entry.name) },
            )
        }
        val homelabRows = inputs.desired?.let(CertNames::passthrough).orEmpty().filter { it !in wanted }.map { name ->
            CertProbeRowDto(
                name = name,
                kind = "homelab",
                group = "homelab",
                cells = config.nodes.map { cell(it.id, name) },
            )
        }

        val desiredCaa = if (config.acme.caa) {
            listOf("issue", "issuewild").map { "0 $it \"${config.acme.caaIssuer}; validationmethods=dns-01\"" }
        } else {
            emptyList()
        }
        val live = inputs.caaLive
        val order = inputs.order
        val failures = order.failuresSince(inputs.nowMs - NodeCertificate.HOUR_MS)
        return CertificatesDto(
            names = entries.map { CertNameDto(it.name, it.reason, it.subject) },
            issued = issued,
            pendingAdd = if (known && meta != null) wanted.filter { it !in meta.names } else emptyList(),
            pendingRemove = if (known && meta != null) meta.names.filter { it !in wanted } else emptyList(),
            directory = config.acme.directoryUrl(),
            staging = config.acme.directoryUrl() == AcmeConfig.STAGING,
            contact = inputs.contact,
            order = CertOrderDto(
                state = if (inputs.running) "running" else order.state.takeIf { it == CertOrderStore.FAILED } ?: "idle",
                step = order.step.takeIf { inputs.running },
                startedAt = order.startedAt,
                reason = order.reason,
                names = order.names.size,
                cause = order.cause.takeIf { order.state == CertOrderStore.FAILED },
                causeText = order.causeText.takeIf { order.state == CertOrderStore.FAILED },
                failedNames = order.failedNames,
                failedAt = order.failedAt,
                retryAt = order.retryAt.takeIf { order.state == CertOrderStore.FAILED },
                failuresLastHour = failures,
                due = inputs.due,
            ),
            history = inputs.history.map {
                CertOrderRecordDto(
                    id = it.id,
                    kind = it.kind,
                    at = it.at,
                    reason = it.reason,
                    names = it.names,
                    ok = it.ok,
                    durationMs = it.durationMs,
                    cause = it.cause,
                    causeText = it.causeText,
                    version = it.version,
                )
            },
            delivery = delivery,
            probes = nodeRows + homelabRows,
            caa = CaaStatusDto(
                enabled = config.acme.caa,
                desired = desiredCaa,
                live = live.orEmpty(),
                missing = if (live == null) emptyList() else desiredCaa.filter { it !in live },
                foreign = live.orEmpty().filter { it !in desiredCaa },
                known = live != null,
            ),
            maxNames = CertNames.MAX,
        )
    }
}
