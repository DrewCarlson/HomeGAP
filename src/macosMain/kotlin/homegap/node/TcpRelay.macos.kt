package homegap.node

/** Development hosts only: a node is Linux, so this relay serves nothing. */
actual fun tcpRelay(log: (String) -> Unit): TcpRelay = object : TcpRelay {
    override fun apply(listeners: Map<Int, String>) {
        log("the relay runs only on Linux: ${listeners.size} port(s) not served")
    }

    override fun run() {
        error("the node agent runs only on Linux")
    }
}
