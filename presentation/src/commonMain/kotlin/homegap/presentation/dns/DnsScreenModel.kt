package homegap.presentation.dns

import androidx.compose.runtime.Immutable
import homegap.api.DnsRecordStatusDto
import homegap.api.Health
import homegap.presentation.common.Badge
import homegap.presentation.core.ScreenModel

@Immutable
data class DnsScreenModel(val records: List<DnsRowModel>) : ScreenModel

@Immutable
data class DnsRowModel(
    val key: String,
    val name: String,
    val type: String,
    val value: String,
    val node: String,
    val routing: String,
    /** Null when the record has no health monitor. */
    val monitor: Badge?,
    val state: Badge,
)

fun DnsRecordStatusDto.toRowModel() = DnsRowModel(
    key = "$type $name $value $nodeId",
    name = name,
    type = type,
    value = value,
    node = nodeId ?: "—",
    routing = geo ?: "plain",
    monitor = if (monitor == "none") null else {
        val health = when (monitorStatus) {
            "online" -> Health.OK
            "offline" -> Health.DOWN
            else -> Health.UNKNOWN
        }
        Badge(health, "$monitor $monitorStatus")
    },
    state = if (inSync) Badge(Health.OK, "in sync") else Badge(Health.DEGRADED, diff ?: "differs"),
)
