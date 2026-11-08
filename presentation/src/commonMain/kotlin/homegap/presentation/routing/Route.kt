package homegap.presentation.routing

/** Dashboard destinations. [path] is the URL fragment (`#nodes`). */
enum class Route(val path: String, val title: String, val subtitle: String) {
    Overview("overview", "Overview", "Health of nodes, tunnels, services and DNS at a glance"),
    Nodes("nodes", "Nodes", "Regional edge nodes provisioned over SSH"),
    Services("services", "Services", "Containers published through homegap.* labels"),
    Dns("dns", "DNS", "Bunny DNS records managed by HomeGAP"),
    Tunnels("tunnels", "Tunnels", "Newt connectors from the homelab to each node"),
    SpeedTest("speedtest", "Speed test", "Latency and throughput through the real traffic path"),
    Activity("log", "Activity", "Reconcile log, newest first"),
    Config("config", "Config", "Desired vs. actual state, secrets and the config file"),
    ;

    companion object {
        val DEFAULT = Overview

        fun fromPath(path: String): Route = entries.firstOrNull { it.path == path.removePrefix("#") } ?: DEFAULT
    }
}
