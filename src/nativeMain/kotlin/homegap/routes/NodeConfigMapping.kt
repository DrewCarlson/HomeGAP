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
    locationName = location?.name,
    managementHost = managementHost,
    sshAllowFrom = sshAllowFrom,
)

/** Checks a node that the dashboard sends. Bad input throws IllegalArgumentException, which gives a 400. */
internal fun NodeConfigDto.toConfig(): NodeConfig {
    require(ConfigLoader.NODE_ID.matches(id)) { "node id must match ${ConfigLoader.NODE_ID.pattern}" }
    require(host.isNotBlank()) { "host is required" }
    require(sshKeyFile.isNotBlank()) { "ssh key file is required" }
    val node = NodeConfig(
        id = id,
        region = region,
        provider = provider,
        host = host,
        os = os,
        sshUser = sshUser,
        sshPort = sshPort,
        sshKeyFile = sshKeyFile,
        location = location(),
        managementHost = managementHost?.takeIf { it.isNotBlank() },
        sshAllowFrom = sshAllowFrom.map { it.trim() }.filter { it.isNotEmpty() },
    )
    val error = ConfigLoader.nodeErrors(node).firstOrNull()
    require(error == null) { error.orEmpty() }
    return node
}

/** The location of the node, or null if the latitude or the longitude is not there. */
private fun NodeConfigDto.location(): GeoLocation? {
    val latitude = latitude ?: return null
    val longitude = longitude ?: return null
    return GeoLocation(latitude, longitude, locationName?.trim()?.ifBlank { null })
}
