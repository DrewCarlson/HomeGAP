package homegap.provision

/**
 * Outside services that the software under HomeGAP contacts on its own, and that no setting
 * stops. Their names resolve to [NOWHERE] in the containers that make the calls, so each call
 * fails at once, on the machine itself. Settings stop the other calls: the usage data, update
 * notices and AI model catalog of Pangolin, and the version check and usage data of Traefik.
 */
object OutsideCalls {
    /** Loopback: nothing serves these names there. On the host network of a node, Traefik refuses the TLS. */
    const val NOWHERE = "127.0.0.1"

    /** Newt asks Fossorial for the newest version at each start (`updates.CheckForUpdate`). */
    val NEWT = listOf("api.fossorial.io")

    /**
     * Pangolin asks these services for its public address at each start. The address is only a
     * hint on the DNS records page of the dashboard. HomeGAP publishes the records of each node
     * itself. The API of Fossorial stays reachable, because Pangolin checks the EE license there.
     */
    val PANGOLIN = listOf("checkip.amazonaws.com", "ifconfig.io", "api.ipify.org")

    /** The AI model catalog that Pangolin reads, in place of the catalog of Fossorial. It is empty. */
    const val EMPTY_AI_CATALOG = "{\"data\": []}\n"
}
