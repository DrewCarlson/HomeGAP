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
    AddNode("add-node", "Add node", "Set up a fresh server from its initial login and provision it"),
    ;

    /** Where this route sits in the navigation: itself, or (for sub-pages) its section. */
    val navSection: Route
        get() = when (this) {
            AddNode -> Nodes
            else -> this
        }

    /** Shown as its own entry in the navigation. */
    val inNav: Boolean get() = navSection == this

    companion object {
        val DEFAULT = Overview

        fun fromPath(path: String): Route = entries.firstOrNull { it.path == path.removePrefix("#") } ?: DEFAULT
    }
}
