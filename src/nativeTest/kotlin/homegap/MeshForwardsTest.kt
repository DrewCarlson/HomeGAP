package homegap

import homegap.discovery.ContainerNetworkSettings
import homegap.discovery.DockerContainerSummary
import homegap.discovery.NetworkEndpoint
import homegap.model.Planner
import homegap.reconcile.MeshForwards
import kotlin.test.Test
import kotlin.test.assertEquals

class MeshForwardsTest {
    private fun DockerContainerSummary.on(vararg networks: Pair<String, String>) = copy(
        networkSettings = ContainerNetworkSettings(networks.associate { (name, ip) -> name to NetworkEndpoint(ip) }),
    )

    private val mesh = container("homegap-homegap-mesh-1", "com.docker.compose.service" to "homegap-mesh")
        .on("homegap_homegap-mesh" to "172.31.0.2", "traefik" to "172.24.0.250", "homegap-private" to "172.33.0.3")

    @Test
    fun aTargetGetsItsAddressOnANetworkOfTheMeshContainer() {
        val door = TRAEFIK.on("a-vpn" to "172.28.0.4", "traefik" to "172.24.0.32")
        val plan = Planner.plan(testConfig(), listOf(door, mesh))
        assertEquals("172.24.0.32", MeshForwards.addresses(plan, listOf(door, mesh))["traefik"])
    }

    @Test
    fun publicTrafficDoesNotTakeThePrivateNetworkOfTheFrontDoor() {
        val door = TRAEFIK.copy(labels = TRAEFIK.labels + ("homegap.private_network" to "homegap-private"))
            .on("a-vpn" to "172.28.0.4", "homegap-private" to "172.33.0.2", "traefik" to "172.24.0.32")
        val plan = Planner.plan(testConfig(), listOf(door, mesh))
        assertEquals("172.24.0.32", MeshForwards.addresses(plan, listOf(door, mesh))["traefik"])
    }

    @Test
    fun withoutTheMeshContainerTheNetworksOfTheFrontDoorStandIn() {
        val door = TRAEFIK.on("traefik" to "172.24.0.32")
        val app = container("app", "homegap.expose" to "app.test.example.net", "homegap.port" to "80")
            .on("other" to "10.0.0.5", "traefik" to "172.24.0.40")
        val plan = Planner.plan(testConfig(), listOf(door, app))
        assertEquals("172.24.0.40", MeshForwards.addresses(plan, listOf(door, app))["app"])
    }
}
