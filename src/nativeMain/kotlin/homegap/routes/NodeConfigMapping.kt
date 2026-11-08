package homegap.routes

import homegap.api.NodeConfigDto
import homegap.config.ConfigLoader
import homegap.config.GeoLocation
import homegap.config.NodeConfig

internal fun NodeConfig.toDto() = NodeConfigDto(
    id = id,
    region = region,
    provider = provider,
    host = host,
    os = os,
    sshUser = sshUser,
    sshPort = sshPort,
    sshKeyFile = sshKeyFile,
    latitude = location?.latitude,
    longitude = location?.longitude,
    managementHost = managementHost,
)

/** Validates a node submitted from the dashboard (400 on bad input via IllegalArgumentException). */
internal fun NodeConfigDto.toConfig(): NodeConfig {
    require(ConfigLoader.NODE_ID.matches(id)) { "node id must match ${ConfigLoader.NODE_ID.pattern}" }
    require(host.isNotBlank()) { "host is required" }
    require(sshKeyFile.isNotBlank()) { "ssh key file is required" }
    return NodeConfig(
        id = id,
        region = region,
        provider = provider,
        host = host,
        os = os,
        sshUser = sshUser,
        sshPort = sshPort,
        sshKeyFile = sshKeyFile,
        location = latitude?.let { lat -> longitude?.let { lon -> GeoLocation(lat, lon) } },
        managementHost = managementHost?.takeIf { it.isNotBlank() },
    )
}
