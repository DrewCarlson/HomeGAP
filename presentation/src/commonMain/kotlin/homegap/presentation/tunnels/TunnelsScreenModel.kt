package homegap.presentation.tunnels

import androidx.compose.runtime.Immutable
import homegap.api.TunnelStatusDto
import homegap.presentation.common.Badge
import homegap.presentation.core.ScreenModel
import homegap.presentation.format.formatBytes
import homegap.presentation.format.toHealth

@Immutable
data class TunnelsScreenModel(val tunnels: List<TunnelRowModel>) : ScreenModel

@Immutable
data class TunnelRowModel(
    val nodeId: String,
    val endpoint: String,
    val site: Badge,
    val process: Badge,
    val lastExit: String?,
    val restarts: String,
    val traffic: String,
)

fun TunnelStatusDto.toRowModel() = TunnelRowModel(
    nodeId = nodeId,
    endpoint = endpoint,
    site = Badge(siteOnline.toHealth(), siteOnline?.let { if (it) "online" else "offline" } ?: "unknown"),
    process = Badge.status(processRunning, "running", "stopped"),
    lastExit = lastExit?.let { "last exit: $it" },
    restarts = restarts.toString(),
    traffic = bytesIn?.let { "${formatBytes(it)} / ${formatBytes(bytesOut ?: 0)}" } ?: "—",
)
