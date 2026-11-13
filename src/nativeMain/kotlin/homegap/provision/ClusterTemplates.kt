package homegap.provision

import homegap.config.MeshConfig
import kotlinx.serialization.Serializable

/** The passwords that the cluster services share. HomeGAP makes them once and keeps them in the data directory. */
@Serializable
data class ClusterSecrets(
    /** The PostgreSQL superuser (`postgres`). Only a member uses it, on itself, never over the mesh. */
    val postgres: String,
    /** PostgreSQL streaming replication (`replicator`). */
    val replication: String,
    /** Valkey `requirepass`, `masterauth` and Sentinel `auth-pass`. */
    val valkey: String,
    /** The REST API of Patroni, for the unsafe endpoints (switchover, reload). */
    val rest: String,
    /** The role of Pangolin (`pangolin`). It owns the `pangolin` database and nothing else. */
    val pangolin: String,
    /** The role that pg_rewind connects with (`rewinder`). */
    val rewind: String,
    /** The etcd `root` user. Only the members and the orchestrator hold it, never Pangolin. */
    val etcd: String,
    /** The `requirepass` of Sentinel. It is not the Valkey password, which Pangolin holds. */
    val sentinel: String,
)

/**
 * The files that each cluster member runs from. Each member has one directory, which the cluster
 * image mounts at `/config`. See `cluster/hg-member.sh`.
 *
 * - The Patroni of each member connects only to its own etcd. Thus nothing changes when a member
 *   joins, and a member without a path to the majority loses etcd and demotes itself.
 * - The services listen only on the mesh address of the member and on loopback.
 * - The nodes reach the current primary, on any member, through HAProxy on the `homegap-cluster`
 *   Docker network: PostgreSQL on 5433, Valkey on 6380.
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

    /** The data directories of the member under `<install_dir>/cluster`. Their containers own them. */
    val DATA_DIRS = listOf("etcd", "pgdata", "valkey", "sentinel")

    /** The bridge interface of the cluster network. It has a fixed name, so the node firewall can admit it. */
    const val BRIDGE = "hg-cluster"
    const val PROXY_HOST = "cluster-proxy"
    const val REST_USER = "homegap"
    const val ETCD_USER = "root"

    /** Ports that only the members use. A container on a node has no path to them. */
    val CONTROL_PORTS = listOf(ETCD_PEER_PORT, SENTINEL_PORT)

    /** Ports of the member that the containers of its own node use (HAProxy, Pangolin). */
    val BRIDGE_PORTS = listOf(POSTGRES_PORT, PATRONI_PORT, VALKEY_PORT)

    /** The ports that the other members connect to over the mesh. */
    val MESH_PORTS = listOf(ETCD_CLIENT_PORT, ETCD_PEER_PORT, POSTGRES_PORT, PATRONI_PORT, VALKEY_PORT, SENTINEL_PORT)

    /**
     * The number of Sentinels that must agree before Valkey fails over. With fewer members, Valkey
     * never fails over, as with etcd.
     */
    private const val SENTINEL_QUORUM = 2

    data class Member(val id: String, val meshIp: String)

    /** The etcd peer URL of [member]. It uses plain HTTP, because the mesh encrypts the traffic. */
    @Suppress("HttpUrlsUsage")
    fun peerUrl(member: Member) = "http://${member.meshIp}:$ETCD_PEER_PORT"

    /** The etcd client URL on [host]. It uses plain HTTP, because etcd listens only on the mesh and on loopback. */
    @Suppress("HttpUrlsUsage")
    private fun clientUrl(host: String) = "http://$host:$ETCD_CLIENT_PORT"

    /** The mesh address of the member. Each service waits for it before it starts. */
    fun meshEnv(member: Member) = "HG_MESH_IP=${member.meshIp}\n"

    /**
     * The settings of etcd. etcd reads [initialCluster] and [state] only at the first start of
     * the member. [state] is `new` for the first member of the cluster and `existing` for a
     * member that joins it.
     */
    fun etcdEnv(member: Member, initialCluster: List<Member>, state: String): String = buildString {
        require(state == "new" || state == "existing")
        appendLine("ETCD_NAME=${member.id}")
        appendLine("ETCD_DATA_DIR=/var/lib/etcd")
        appendLine("ETCD_LISTEN_PEER_URLS=${peerUrl(member)}")
        appendLine("ETCD_LISTEN_CLIENT_URLS=${clientUrl(member.meshIp)},${clientUrl("127.0.0.1")}")
        appendLine("ETCD_ADVERTISE_CLIENT_URLS=${clientUrl(member.meshIp)}")
        appendLine("ETCD_INITIAL_ADVERTISE_PEER_URLS=${peerUrl(member)}")
        appendLine("ETCD_INITIAL_CLUSTER=${initialCluster.joinToString(",") { "${it.id}=${peerUrl(it)}" }}")
        appendLine("ETCD_INITIAL_CLUSTER_STATE=$state")
        appendLine("ETCD_INITIAL_CLUSTER_TOKEN=$SCOPE")
    }

    /** Reloads HAProxy in its container with the config that the script wrote before. */
    const val HAPROXY_RELOAD = "hg-member haproxy-reload"

    /** The database of Pangolin and the role that owns it. */
    const val PANGOLIN_DB = "pangolin"
    const val PANGOLIN_USER = "pangolin"
    const val REWIND_USER = "rewinder"

    /** The patroni.yml in the config directory of the member, in quotes for the shell. */
    private const val PATRONI_CONFIG = $$"\"${HG_CONFIG:-/config}/patroni.yml\""

    /**
     * Runs in the `db` container of a member. It copies the patroni.yml of the member into the
     * container, and Patroni (PID 1) reads the file again.
     */
    const val PATRONI_RELOAD =
        "install -o postgres -g postgres -m 0600 $PATRONI_CONFIG /tmp/patroni.yml && kill -HUP 1"

    /**
     * The Patroni settings of a member. To apply a change, HomeGAP tells Patroni to read the file
     * again ([PATRONI_RELOAD]). Patroni restarts nothing. A setting that needs a restart of
     * PostgreSQL shows as pending.
     *
     * `postgresql.parameters` are the PostgreSQL settings of this member. JIT compilation is off.
     * The queries of Pangolin are small lookups. A connection that JIT-compiles a query loads LLVM
     * and keeps about 40 MB until it closes, and a node has up to 30 pooled connections.
     */
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
        |  username: $ETCD_USER
        |  password: "${secrets.etcd}"
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
        |  # Creates Pangolin's role and database, once, on the member that creates the cluster.
        |  post_bootstrap: /usr/local/bin/hg-member post-bootstrap
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
        |    rewind: { username: $REWIND_USER, password: "${secrets.rewind}" }
        |  # The superuser never signs in over the mesh: a node holds only Pangolin's role (its own
        |  # database), the replication role and the rewind role.
        |  pg_hba:
        |    - local all all trust
        |    - host all postgres 127.0.0.1/32 scram-sha-256
        |    - host $PANGOLIN_DB $PANGOLIN_USER 127.0.0.1/32 scram-sha-256
        |    - host $PANGOLIN_DB $PANGOLIN_USER ${mesh.subnet} scram-sha-256
        |    # Containers on the same node (HAProxy, Pangolin) reach the member from Docker networks.
        |    - host $PANGOLIN_DB $PANGOLIN_USER 172.16.0.0/12 scram-sha-256
        |    - host postgres $REWIND_USER ${mesh.subnet} scram-sha-256
        |    - host replication replicator ${mesh.subnet} scram-sha-256
        |    - host replication replicator 127.0.0.1/32 scram-sha-256
        |""".trimMargin()

    /**
     * The SQL that `hg-member post-bootstrap` runs as the superuser, once, immediately after it
     * creates the database cluster: the role of Pangolin and the database that the role owns. The
     * password is alphanumeric.
     */
    fun rolesSql(secrets: ClusterSecrets): String = """
        |CREATE ROLE $PANGOLIN_USER LOGIN PASSWORD '${secrets.pangolin}';
        |CREATE DATABASE $PANGOLIN_DB OWNER $PANGOLIN_USER;
        |REVOKE ALL ON DATABASE $PANGOLIN_DB FROM PUBLIC;
        |""".trimMargin()

    /**
     * The settings of Valkey. The member starts as a replica of the master that the [sentinels]
     * report. If no Sentinel knows a master, it starts as a replica of [initialMaster]. Sentinel
     * promotes the member with the lowest [priority].
     */
    fun valkeyEnv(
        member: Member,
        secrets: ClusterSecrets,
        priority: Int,
        initialMaster: String,
        sentinels: List<String>,
    ): String = buildString {
        appendLine("HG_MESH_IP=${member.meshIp}")
        appendLine("HG_PASSWORD=${secrets.valkey}")
        appendLine("HG_SENTINEL_PASSWORD=${secrets.sentinel}")
        appendLine("HG_PRIORITY=$priority")
        appendLine("HG_INITIAL_MASTER=$initialMaster")
        appendLine("HG_SENTINELS='${sentinels.joinToString(" ")}'")
    }

    /** The first config of Sentinel. Sentinel records what it learns in its own copy. */
    fun sentinel(member: Member, initialMaster: String, secrets: ClusterSecrets): String = """
        |bind ${member.meshIp} 127.0.0.1
        |port $SENTINEL_PORT
        |requirepass ${secrets.sentinel}
        |sentinel announce-ip ${member.meshIp}
        |sentinel monitor $SCOPE $initialMaster $VALKEY_PORT $SENTINEL_QUORUM
        |sentinel auth-pass $SCOPE ${secrets.valkey}
        |sentinel down-after-milliseconds $SCOPE 10000
        |sentinel failover-timeout $SCOPE 60000
        |sentinel parallel-syncs $SCOPE 1
        |""".trimMargin()

    /**
     * Fixed addresses for the current primaries. HAProxy closes the connections to a member that
     * is not the primary any more.
     */
    fun haproxy(members: List<Member>, secrets: ClusterSecrets, self: String? = null): String = buildString {
        appendLine("# Managed by HomeGAP: the current PostgreSQL and Valkey primaries, wherever they are.")
        appendLine("global")
        appendLine("  maxconn 2000")
        // HAProxy reloads a config change in place (`hg-member haproxy-reload`). This file
        // keeps the up/down state of each server across the reload.
        appendLine("  stats socket /tmp/haproxy.sock mode 600 level admin")
        appendLine("  server-state-file /tmp/haproxy.state")
        appendLine()
        appendLine("defaults")
        appendLine("  mode tcp")
        appendLine("  load-server-state-from-file global")
        // The round trip between two members can be a quarter second or more. A check has time
        // for two lost SYNs (sent again after 1s and 3s), and three misses in a row mark a member
        // down, so a slow link never flaps. HAProxy starts with each member up. After a miss, the
        // next checks come one second apart (`fastinter`). The check of a replica fails at once,
        // because the replica answers "not primary". Thus a new proxy rules the replicas out in
        // seconds.
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
        // QUIT goes with the query. Valkey answers and closes, so the "role:slave" of a replica
        // fails the check at once, without a wait of the full timeout for "role:master".
        appendLine("  tcp-check send info\\ replication\\r\\nQUIT\\r\\n")
        appendLine("  tcp-check expect string role:master")
        members.forEach { appendLine("  server ${it.id} ${it.meshIp}:$VALKEY_PORT check") }
        appendLine()
        // The orchestrator reads the stats over SSH. The port is only on the loopback of the node.
        append(ClusterProxy.STATS_CONFIG)
        appendLine()
        append(MeshProbe.containerBackend(members.filter { it.id != self }))
    }

    /**
     * The cluster member of a node, as its own compose project. It is not part of the Pangolin
     * stack, so an update of the stack never restarts the database. [configHashes] gives a hash
     * of the config of each service, so a change makes only that service again.
     */
    fun nodeCompose(image: String, configHashes: Map<String, String>): String {
        fun label(service: String) =
            configHashes[service]?.let { "\n    labels: { org.homegap.config: \"$it\" }" }.orEmpty()
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
