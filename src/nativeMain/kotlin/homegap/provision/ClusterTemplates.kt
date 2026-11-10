package homegap.provision

import homegap.config.MeshConfig
import kotlinx.serialization.Serializable

/** Passwords the cluster's services share; generated once and kept in the data directory. */
@Serializable
data class ClusterSecrets(
    /** PostgreSQL superuser (`postgres`), also used for pg_rewind. */
    val postgres: String,
    /** PostgreSQL streaming replication (`replicator`). */
    val replication: String,
    /** Valkey `requirepass`, `masterauth` and Sentinel `auth-pass`. */
    val valkey: String,
    /** Patroni's REST API, for the unsafe endpoints (switchover, reload). */
    val rest: String,
)

/**
 * Files each cluster member runs from (one directory per member, mounted at `/config` in the
 * cluster image; see `cluster/hg-member.sh`).
 *
 * - Every member's Patroni talks only to its own etcd, so nothing changes when members are added,
 *   and a member cut off from the majority loses etcd and demotes itself.
 * - Services listen on the member's mesh address and loopback only.
 * - Nodes reach "the primary, wherever it is" through HAProxy on the `homegap-cluster` Docker
 *   network: PostgreSQL on 5433, Valkey on 6380.
 */
object ClusterTemplates {
    const val SCOPE = "homegap"
    const val ETCD_CLIENT_PORT = 2379
    const val ETCD_PEER_PORT = 2380
    const val POSTGRES_PORT = 5432
    const val PATRONI_PORT = 8008
    const val VALKEY_PORT = 6379
    const val SENTINEL_PORT = 26379
    const val PROXY_POSTGRES_PORT = 5433
    const val PROXY_VALKEY_PORT = 6380
    const val NETWORK = "homegap-cluster"
    /** The member's data directories under `<install_dir>/cluster`, owned by their containers. */
    val DATA_DIRS = listOf("etcd", "pgdata", "valkey", "sentinel")
    /** The cluster network's bridge interface, named so the node firewall can admit it. */
    const val BRIDGE = "hg-cluster"
    const val PROXY_HOST = "cluster-proxy"
    const val REST_USER = "homegap"

    /** Ports other members connect to over the mesh. */
    val MESH_PORTS = listOf(ETCD_CLIENT_PORT, ETCD_PEER_PORT, POSTGRES_PORT, PATRONI_PORT, VALKEY_PORT, SENTINEL_PORT)

    /** Sentinels that must agree before Valkey fails over (with fewer members it never does, like etcd). */
    private const val SENTINEL_QUORUM = 2

    data class Member(val id: String, val meshIp: String)

    fun peerUrl(m: Member) = "http://${m.meshIp}:$ETCD_PEER_PORT"

    /** The member's mesh address; every service waits for it before starting. */
    fun meshEnv(member: Member) = "HG_MESH_IP=${member.meshIp}\n"

    /**
     * etcd's settings. [initialCluster] and [state] only matter on the member's first start
     * (`new` for the cluster's first member, `existing` for one joining it).
     */
    fun etcdEnv(member: Member, initialCluster: List<Member>, state: String): String = buildString {
        require(state == "new" || state == "existing")
        appendLine("ETCD_NAME=${member.id}")
        appendLine("ETCD_DATA_DIR=/var/lib/etcd")
        appendLine("ETCD_LISTEN_PEER_URLS=${peerUrl(member)}")
        appendLine("ETCD_LISTEN_CLIENT_URLS=http://${member.meshIp}:$ETCD_CLIENT_PORT,http://127.0.0.1:$ETCD_CLIENT_PORT")
        appendLine("ETCD_ADVERTISE_CLIENT_URLS=http://${member.meshIp}:$ETCD_CLIENT_PORT")
        appendLine("ETCD_INITIAL_ADVERTISE_PEER_URLS=${peerUrl(member)}")
        appendLine("ETCD_INITIAL_CLUSTER=${initialCluster.joinToString(",") { "${it.id}=${peerUrl(it)}" }}")
        appendLine("ETCD_INITIAL_CLUSTER_STATE=$state")
        appendLine("ETCD_INITIAL_CLUSTER_TOKEN=$SCOPE")
    }

    /**
     * A member's Patroni settings. A change is applied by asking Patroni to reload the file (it
     * restarts nothing; a setting that needs a PostgreSQL restart shows as pending).
     *
     * `postgresql.parameters` are this member's PostgreSQL settings. JIT compilation is off:
     * Pangolin's queries are small lookups, and a connection that JIT-compiles one loads LLVM and
     * keeps about 40 MB for the rest of its life, with up to 30 pooled connections per node.
     */
    /** Run in a member's `db` container: copies in its patroni.yml and has Patroni (PID 1) re-read it. */
    /** Reloads HAProxy in the running haproxy container with the config just written. */
    const val HAPROXY_RELOAD = "hg-member haproxy-reload"

    const val PATRONI_RELOAD =
        "install -o postgres -g postgres -m 0600 \"\${HG_CONFIG:-/config}/patroni.yml\" /tmp/patroni.yml && kill -HUP 1"

    fun patroni(member: Member, mesh: MeshConfig, secrets: ClusterSecrets): String = """
        |# Managed by HomeGAP: this member's PostgreSQL, run by Patroni.
        |scope: $SCOPE
        |name: ${member.id}
        |restapi:
        |  listen: ${member.meshIp}:$PATRONI_PORT
        |  connect_address: ${member.meshIp}:$PATRONI_PORT
        |  authentication:
        |    username: $REST_USER
        |    password: "${secrets.rest}"
        |etcd3:
        |  hosts: 127.0.0.1:$ETCD_CLIENT_PORT
        |bootstrap:
        |  dcs:
        |    ttl: 30
        |    loop_wait: 10
        |    retry_timeout: 10
        |    maximum_lag_on_failover: 1048576
        |    postgresql:
        |      use_pg_rewind: true
        |      parameters:
        |        wal_log_hints: "on"
        |        max_connections: 300
        |  initdb:
        |    - encoding: UTF8
        |    - data-checksums
        |postgresql:
        |  listen: ${member.meshIp},127.0.0.1:$POSTGRES_PORT
        |  connect_address: ${member.meshIp}:$POSTGRES_PORT
        |  data_dir: /var/lib/postgresql/data/pgdata
        |  bin_dir: /usr/lib/postgresql/17/bin
        |  pgpass: /tmp/pgpass
        |  parameters:
        |    jit: "off"
        |  authentication:
        |    superuser: { username: postgres, password: "${secrets.postgres}" }
        |    replication: { username: replicator, password: "${secrets.replication}" }
        |    rewind: { username: postgres, password: "${secrets.postgres}" }
        |  pg_hba:
        |    - local all all trust
        |    - host all all 127.0.0.1/32 scram-sha-256
        |    - host all all ${mesh.subnet} scram-sha-256
        |    # Containers on the same node (HAProxy, Pangolin) reach the member from Docker networks.
        |    - host all all 172.16.0.0/12 scram-sha-256
        |    - host replication replicator ${mesh.subnet} scram-sha-256
        |    - host replication replicator 127.0.0.1/32 scram-sha-256
        |""".trimMargin()

    /**
     * Valkey's settings. The member starts as a replica of the master the [sentinels] report, or
     * of [initialMaster] when none knows one; [priority] decides who Sentinel promotes (lower wins).
     */
    fun valkeyEnv(member: Member, secrets: ClusterSecrets, priority: Int, initialMaster: String, sentinels: List<String>): String = buildString {
        appendLine("HG_MESH_IP=${member.meshIp}")
        appendLine("HG_PASSWORD=${secrets.valkey}")
        appendLine("HG_PRIORITY=$priority")
        appendLine("HG_INITIAL_MASTER=$initialMaster")
        appendLine("HG_SENTINELS='${sentinels.joinToString(" ")}'")
    }

    /** Sentinel's starting config; it records what it learns in its own copy. */
    fun sentinel(member: Member, initialMaster: String, secrets: ClusterSecrets): String = """
        |bind ${member.meshIp} 127.0.0.1
        |port $SENTINEL_PORT
        |sentinel announce-ip ${member.meshIp}
        |sentinel monitor $SCOPE $initialMaster $VALKEY_PORT $SENTINEL_QUORUM
        |sentinel auth-pass $SCOPE ${secrets.valkey}
        |sentinel down-after-milliseconds $SCOPE 10000
        |sentinel failover-timeout $SCOPE 60000
        |sentinel parallel-syncs $SCOPE 1
        |""".trimMargin()

    /** Stable addresses for the current primaries; connections to a demoted member are cut. */
    fun haproxy(members: List<Member>, secrets: ClusterSecrets, self: String? = null): String = buildString {
        appendLine("# Managed by HomeGAP: the current PostgreSQL and Valkey primaries, wherever they are.")
        appendLine("global")
        appendLine("  maxconn 2000")
        // Config changes are reloaded in place (`hg-member haproxy-reload`), carrying each
        // server's up/down state over through this file.
        appendLine("  stats socket /tmp/haproxy.sock mode 600 level admin")
        appendLine("  server-state-file /tmp/haproxy.state")
        appendLine()
        appendLine("defaults")
        appendLine("  mode tcp")
        appendLine("  load-server-state-from-file global")
        // Members can be a quarter second or more apart: a check leaves room for two lost SYNs
        // (resent after 1s and 3s), and three misses in a row mark a member down, so a slow link
        // never flaps. HAProxy starts with every member up: after a miss the next checks come a
        // second apart (`fastinter`), and a replica's checks fail at once (it answers "not
        // primary" rather than timing out), so a fresh proxy rules replicas out in seconds.
        appendLine("  timeout connect 5s")
        appendLine("  timeout client 30m")
        appendLine("  timeout server 30m")
        appendLine("  timeout check 5s")
        appendLine("  default-server inter 4s fastinter 1s fall 3 rise 2 on-marked-down shutdown-sessions")
        appendLine()
        appendLine("listen postgres-primary")
        appendLine("  bind :$PROXY_POSTGRES_PORT")
        appendLine("  option httpchk OPTIONS /primary")
        appendLine("  http-check expect status 200")
        members.forEach { appendLine("  server ${it.id} ${it.meshIp}:$POSTGRES_PORT check port $PATRONI_PORT") }
        appendLine()
        appendLine("listen valkey-primary")
        appendLine("  bind :$PROXY_VALKEY_PORT")
        appendLine("  option tcp-check")
        appendLine("  tcp-check send AUTH\\ ${secrets.valkey}\\r\\n")
        appendLine("  tcp-check expect string +OK")
        // QUIT goes with the query: Valkey answers and closes, so a replica's "role:slave" fails
        // the check straight away instead of waiting out the timeout for "role:master".
        appendLine("  tcp-check send info\\ replication\\r\\nQUIT\\r\\n")
        appendLine("  tcp-check expect string role:master")
        members.forEach { appendLine("  server ${it.id} ${it.meshIp}:$VALKEY_PORT check") }
        appendLine()
        // Read by the orchestrator over SSH (published on the node's loopback only).
        append(ClusterProxy.STATS_CONFIG)
        appendLine()
        append(MeshProbe.containerBackend(members.filter { it.id != self }))
    }

    /**
     * A node's cluster member, its own compose project (separate from the Pangolin stack, so stack
     * updates never restart the database). [configHashes] maps a service to a hash of its config,
     * so a change recreates only that service.
     */
    fun nodeCompose(image: String, configHashes: Map<String, String>): String {
        fun label(service: String) = configHashes[service]?.let { "\n    labels: { org.homegap.config: \"$it\" }" }.orEmpty()
        return """
            |# Managed by HomeGAP: this node's cluster member. Changes are overwritten.
            |name: homegap-cluster
            |networks:
            |  $NETWORK:
            |    name: $NETWORK
            |    # A fixed interface name: the node firewall admits it, so containers here (HAProxy,
            |    # Pangolin) reach the services this member runs on the host's mesh address.
            |    driver_opts: { com.docker.network.bridge.name: $BRIDGE }
            |services:
            |  etcd:
            |    image: $image
            |    command: [etcd]
            |    network_mode: host
            |    restart: unless-stopped${label("etcd")}
            |    volumes: [./config:/config:ro, ./etcd:/var/lib/etcd]
            |  db:
            |    image: $image
            |    command: [patroni]
            |    network_mode: host
            |    restart: unless-stopped
            |    shm_size: 256m
            |    stop_grace_period: 60s${label("db")}
            |    volumes: [./config:/config:ro, ./pgdata:/var/lib/postgresql/data]
            |  valkey:
            |    image: $image
            |    command: [valkey]
            |    network_mode: host
            |    restart: unless-stopped${label("valkey")}
            |    volumes: [./config:/config:ro, ./valkey:/data]
            |  sentinel:
            |    image: $image
            |    command: [sentinel]
            |    network_mode: host
            |    restart: unless-stopped${label("sentinel")}
            |    volumes: [./config:/config:ro, ./sentinel:/var/lib/sentinel]
            |  haproxy:
            |    image: $image
            |    command: [haproxy]
            |    restart: unless-stopped${label("haproxy")}
            |    volumes: [./config:/config:ro]
            |    ports: ["127.0.0.1:${ClusterProxy.STATS_PORT}:${ClusterProxy.STATS_PORT}"]
            |    networks:
            |      $NETWORK:
            |        aliases: [$PROXY_HOST]
            |  ${MeshProbe.SERVICE}:
            |    image: $image
            |    entrypoint: [python3, -u, /config/probe.py]
            |    network_mode: host
            |    restart: unless-stopped
            |    stop_grace_period: 2s${label(MeshProbe.SERVICE)}
            |    volumes: [./config:/config:ro, ${MeshProbe.STATE_DIR}:/probe]
            |""".trimMargin()
    }
}
