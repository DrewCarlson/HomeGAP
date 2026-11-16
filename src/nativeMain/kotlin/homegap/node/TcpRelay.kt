package homegap.node

/**
 * Serves TCP ports for the agent of a node. It accepts each connection of a port, opens one to
 * the target of the port, sends a PROXY protocol header and then passes the bytes both ways.
 */
interface TcpRelay {
    /**
     * Sets the ports to serve: port to target (`address:port`). A port that is not in
     * [listeners] accepts no new connection, and its open connections go on. Each thread can
     * call this.
     */
    fun apply(listeners: Map<Int, String>)

    /** Serves the ports. It does not return. */
    fun run()
}

/** The relay of this platform. [log] gets a line when a port opens, closes or fails. */
expect fun tcpRelay(log: (String) -> Unit): TcpRelay
