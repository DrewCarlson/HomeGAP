package homegap.presentation.services

import androidx.compose.runtime.Immutable
import homegap.api.ServiceStatusDto
import homegap.presentation.common.Badge
import homegap.presentation.core.ScreenModel

@Immutable
data class ServicesScreenModel(
    val services: List<ServiceRowModel>,
    val onTogglePause: (key: String, paused: Boolean) -> Unit,
) : ScreenModel

/** One labelled service as shown in the services tables. */
@Immutable
data class ServiceRowModel(
    val key: String,
    val hostname: String,
    /** "+ *.example.net" for wildcard services. */
    val wildcard: String?,
    val container: String,
    val paused: Boolean,
    val kind: String,
    val protocol: String,
    val access: String,
    val regions: List<String>,
    val perNode: List<Badge>,
)

fun ServiceStatusDto.toRowModel() = ServiceRowModel(
    key = key,
    hostname = hostname,
    wildcard = if (wildcard) "+ *.$hostname" else null,
    container = container,
    paused = paused,
    kind = kind,
    protocol = "$protocol / $tls",
    access = access + if (auth != "none") " · $auth" else "",
    regions = regions,
    perNode = perNode.map { Badge(it.health, it.nodeId, it.detail) },
)
