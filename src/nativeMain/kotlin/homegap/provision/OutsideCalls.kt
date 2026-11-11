package homegap.provision

/**
 * Outside services that the software HomeGAP runs would contact on its own, with no setting to
 * stop it. Their names resolve to [NOWHERE] in the containers that make the calls, so each one
 * fails at once, on the machine itself. The rest is turned off by settings: Pangolin's usage
 * data, update notices and AI model catalog, and Traefik's version check and usage data.
 */
object OutsideCalls {
    /** Loopback: nothing serves these names there (on a node's host network, Traefik refuses the TLS). */
    const val NOWHERE = "127.0.0.1"

    /** Newt asks Fossorial for the newest version on every start (`updates.CheckForUpdate`). */
    val NEWT = listOf("api.fossorial.io")

    /**
     * Pangolin looks up its public address at every start. It only fills in a hint on the
     * dashboard's DNS records page; HomeGAP publishes each node's records itself. Fossorial's own
     * API stays reachable: the EE license is checked there.
     */
    val PANGOLIN = listOf("checkip.amazonaws.com", "ifconfig.io", "api.ipify.org")

    /** The AI model catalog Pangolin reads instead of downloading Fossorial's (an empty one). */
    const val EMPTY_AI_CATALOG = "{\"data\": []}\n"
}
