package homegap.presentation.routing

/** Dashboard destinations. [path] is the URL fragment (`#nodes`). */
enum class Route(val path: String, val title: String, val subtitle: String) {
    Overview("overview", "Overview", "Health of nodes, tunnels, services and DNS at a glance"),
    Nodes("nodes", "Nodes", "The nodes that serve your visitors"),
    Services("services", "Services", "Containers published through homegap.* labels"),
    Dns("dns", "DNS", "Bunny DNS records managed by HomeGAP"),
    Certificates("certificates", "Certificates", "The certificate of the nodes, who has it, and what visitors get"),
    Tunnels("tunnels", "Tunnels", "The tunnels from the homelab to each node"),
    Access("access", "Access", "Who can sign in to the Pangolin apps, and their devices"),
    SpeedTest("speedtest", "Speed test", "Latency and throughput through the real traffic path"),
    Activity("log", "Activity", "Reconcile log, newest first"),
    Diagnostics("diagnostics", "Diagnostics", "Container logs and a read-only snapshot of each node"),
    Config("config", "Config", "Desired vs. actual state, secrets and the config file"),
    AddNode("add-node", "Add node", "Set up a fresh server from its initial login and provision it"),
    Locations("locations", "Locations", "Data-center cities for node locations, and where the homelab is"),
    ;

    /** Where this route sits in the navigation: itself, or (for sub-pages) its section. */
    val navSection: Route
        get() = when (this) {
            AddNode -> Nodes
            Locations -> Config
            Certificates -> Dns
            else -> this
        }

    /** Opens over its section's page, which stays the screen behind it. */
    val isOverlay: Boolean get() = this == AddNode || this == Locations

    /** The tabs of a section: DNS has its records and its certificates. */
    val tabs: List<Route>
        get() = when (navSection) {
            Dns -> listOf(Dns, Certificates)
            else -> emptyList()
        }

    /** Shown as its own entry in the navigation. */
    val inNav: Boolean get() = navSection == this

    /** The label where space is short: the rail and the phone menu. */
    val shortTitle: String
        get() = when (this) {
            SpeedTest -> "Speed"
            Diagnostics -> "Diagnose"
            else -> title
        }

    /** The navigation group; sub-pages take their section's. */
    val group: NavGroup
        get() = when (navSection) {
            Overview, Nodes, Services, Access, SpeedTest -> NavGroup.Operate
            else -> NavGroup.Plumbing
        }

    companion object {
        val DEFAULT = Overview

        /** The ten sections in navigation order: Operate, then Plumbing. */
        val NAV: List<Route> = listOf(
            Overview,
            Nodes,
            Services,
            Access,
            SpeedTest,
            Dns,
            Tunnels,
            Activity,
            Diagnostics,
            Config,
        )

        fun fromPath(path: String): Route = entries.firstOrNull { it.path == path.removePrefix("#") } ?: DEFAULT
    }
}

/** The two groups of the navigation: what is run day to day, and what carries it. */
enum class NavGroup(val title: String) {
    Operate("Operate"),
    Plumbing("Plumbing"),
}
