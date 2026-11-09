package homegap.presentation.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.Health
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter
import homegap.presentation.format.toHealth
import homegap.presentation.map.overviewMap
import homegap.presentation.nodes.toRowModel
import homegap.presentation.services.toRowModel

data class OverviewScreenProps(val status: StatusSnapshot, val now: Long)

@SingleIn(AppScope::class)
@Inject
class OverviewScreenPresenter : Presenter<OverviewScreenProps, OverviewScreenModel> {
    @Composable
    override fun model(props: OverviewScreenProps): OverviewScreenModel {
        val status = props.status
        val stats = remember(status) { summaryStats(status) }
        val nodes = remember(status.nodes, props.now) { status.nodes.map { it.toRowModel(props.now) } }
        val services = remember(status.services) { status.services.map { it.toRowModel() } }
        val map = remember(status, props.now / 60_000) { status.overviewMap(props.now) }
        return OverviewScreenModel(stats, status.labelErrors, nodes, services, map, status.homelab == null)
    }

    internal fun summaryStats(status: StatusSnapshot): List<StatModel> {
        val servicesOk = status.services.count { svc -> svc.perNode.isNotEmpty() && svc.perNode.all { it.health == Health.OK } }
        return listOf(
            ratio("Nodes healthy", status.nodes.count { it.health == Health.OK }, status.nodes.size),
            ratio("Tunnels up", status.tunnels.count { it.up }, status.tunnels.size),
            ratio("Services", servicesOk, status.services.size, hint = "healthy on every node"),
            ratio("DNS in sync", status.dns.count { it.inSync }, status.dns.size),
            StatModel("Label errors", status.labelErrors.size.toString(), status.labelErrors.isEmpty().toHealth()),
        )
    }

    private fun ratio(label: String, ok: Int, total: Int, hint: String? = null): StatModel {
        val health = when {
            total == 0 -> Health.UNKNOWN
            ok == total -> Health.OK
            ok == 0 -> Health.DOWN
            else -> Health.DEGRADED
        }
        return StatModel(label, "$ok / $total", health, hint)
    }
}
