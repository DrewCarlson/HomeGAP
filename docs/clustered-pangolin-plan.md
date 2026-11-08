# Build plan: clustered Pangolin with nearest-node client access

Status: built (phases 1-8). `e2e/verify.sh` and `e2e/verify-cluster.sh` cover it end to end; signing in with the mobile app is left to a real device. The design is proven in the `e2e/cluster` prototype (commits d2f94a0, b0c64db,
6123ef4). This plan turns it into HomeGAP.

## Goal

- **One identity, nearest node.** A person logs in once. Their Pangolin client relays through the
  nearest healthy node to reach private homelab services.
- **The VPN outlives the homelab.** Logging in, enrolling devices and staying connected keep
  working when the homelab is down (only homelab services are unreachable).
- **Nothing lost from today.** Both SNI passthrough and edge TLS termination, Bunny geo routing and
  failover, raw TCP/UDP ports, the add-node wizard, one-at-a-time rollouts and the dashboard keep
  working.

## Target architecture

| Piece | Today | After |
| --- | --- | --- |
| Pangolin | One independent instance per node, each with its own SQLite database, users, sites and license | One Pangolin EE **cluster**: an instance on every node, sharing PostgreSQL and Valkey. One org, one set of users, one license |
| Database | Per-node SQLite | PostgreSQL managed by Patroni, with a member on the homelab and on every node. etcd on all members is the vote |
| Primary | n/a | **Dynamic.** The homelab by default. The nodes elect one of themselves while the homelab is unreachable, and the orchestrator switches back after a stability hold |
| Valkey | n/a | Valkey on every member, with Sentinel. Follows the same primary policy |
| Node to node | Nothing | A HomeGAP-managed **WireGuard mesh** (`hg-mesh`, UDP 51821, its own subnet) between the homelab and all nodes. The homelab dials out (works behind NAT) |
| Pangolin's database address | n/a | A local HAProxy on each node that always points at the current PostgreSQL and Valkey primaries, so Pangolin's config never changes on failover |
| Homelab sites | One site per node (in that node's Pangolin) | Still one site per node, now in the one cluster org. Each node has its own Pangolin `app.region`, which pins the site created through it to that node |
| Public resources | Duplicated per node | One resource per purpose with a target on every node's site. Each node only routes to its local target |
| Private resources | Per node, plus the hand-made homelab Pangolin | One resource attached to every node's site. Clients route through the best site (by latency, chosen when they connect) |
| Passthrough | HomeGAP's SNI router file, referencing `…@http` services | The same file, in Pangolin's file-mode directory, referencing `…@file` services |
| Edge termination | Per-node HTTP and wildcard resources | One HTTP or wildcard resource, served by every node with the wildcard certificate HomeGAP already pushes |
| Client entry | `pangolin-<id>.<domain>` per node | A geo-routed `pangolin.<domain>` (Bunny, health-monitored), plus the per-node management hosts the connectors use |
| `primary_pangolin` (the hand-made homelab Pangolin) | Optional pet for private access | Retired. The cluster is the identity anchor. Kept only as a migration source if it holds anything |

Design choices fixed by the prototype:

- **Regions pin sites.** A node's Pangolin only offers its own exit node to connectors that connect
  through it.
- **Pangolin reaches its local Gerbil at `127.0.0.1:3004`** (`gerbil.local_exit_node_reachable_at`).
  Gerbil advertises its mesh address (`--reachableAt`).
- **Gerbil's SNI proxy stays unused.** Traefik owns :443 as today, so wildcard passthrough
  hostnames Pangolin doesn't know are never dropped. No cross-node routing is needed, since every
  node holds its own homelab tunnel.
- **Start order.** The first Pangolin migrates an empty database alone; the others start after it.
  Upgrades stay one node at a time.
- **License.** Activate once, then call `POST /license/recheck` on every other node.

## Configuration

```yaml
cluster:
  mesh:
    subnet: 10.77.0.0/24        # must not overlap Pangolin's tunnel subnets
    port: 51821
  primary:
    preferred: homelab          # later: a node id ("primary on a node, homelab as replica plus vote")
    switch_back_after: 10m      # the homelab must be a caught-up replica this long first
  client_hostname: pangolin     # geo-routed pangolin.<domain> for clients and the login pages
pangolin:
  license_key_env: PANGOLIN_LICENSE_KEY   # one key for the cluster
nodes:
  - id: eu1                     # license_key_env per node is ignored in cluster mode (warned)
```

Secrets HomeGAP generates and keeps (state, with the homelab member as the authoritative copy):
- PostgreSQL superuser and replication passwords;
- the Valkey password;
- Patroni REST credentials;
- mesh private keys (one per member);
- the shared Pangolin `server.secret`.

## Phases

Each phase ends with something that works on the e2e stack and can be merged on its own.

### Phase 1: the mesh

- `provision/MeshConfig.kt`:
  - key generation and member addresses;
  - `wg-quick` config rendering (peers: every other member; the homelab has no endpoint);
  - the firewall rule (UDP 51821, added to `FirewallRules`).
- Nodes: install `wireguard-tools`, write `/etc/wireguard/hg-mesh.conf`, enable
  `wg-quick@hg-mesh`, as a host-level step in `NodeReconciler`.
- Homelab: a `homegap-member` container in `deploy/homelab-compose.yml` that owns the homelab's
  mesh interface (NET_ADMIN). The orchestrator renders its config into a shared volume and joins
  its network namespace, so it can reach the members.
- Reconcile: adding or removing a node updates every member's peers. Drift check: handshake age
  per peer.
- Dashboard: a mesh panel with each peer, its last handshake and its traffic.
- **Done when:** e2e nodes and the homelab orchestrator reach each other only over the mesh (a
  public-path block like the prototype's).

### Phase 2: the database layer

- Node stack additions (`provision/NodeTemplates.kt`):
  - etcd;
  - Patroni with PostgreSQL 17 (HomeGAP builds and publishes the image);
  - Valkey and Sentinel;
  - HAProxy;
  - all bound to the mesh address or loopback, never public.
- Homelab member: the same data services in the `homegap-member` stack.
- Auth everywhere: PostgreSQL scram with `pg_hba` limited to the mesh subnet, Valkey `requirepass`
  and `masterauth`, Patroni REST basic auth.
- **Valkey must start as a replica of Sentinel's current master, never as a master.** In the
  prototype a returning homelab Valkey was a second master for about 10 seconds.
- Membership: a new node joins etcd as a member (`etcdctl member add`), then Patroni clones it.
  Removing a node removes its etcd member and Patroni entry. Keep at least three voters; the
  homelab is always one.
- Orchestrator (`reconcile/ClusterReconciler.kt`, new):
  - reads Patroni and Sentinel state;
  - performs the **switch-back**: when the preferred primary has been a caught-up replica for
    `switch_back_after`, a Patroni switchover plus `SENTINEL FAILOVER`;
  - shows an isolated member stuck "shutting down";
  - never uses logins as a health probe (Pangolin limits logins to 15 per 15 minutes,
    cluster-wide).
- Backups: nightly `pg_dump` on the homelab member, kept on homelab disk. The homelab replica is
  already a live copy.
- **Done when:** the e2e versions of the prototype tests pass under HomeGAP's control:
  - kill the homelab → a node is promoted;
  - bring it back → switch-back after the hold;
  - partition the leader → no split brain.

### Phase 3: clustered Pangolin on the nodes

- Image `ee-postgresql-<version>`.
- Config:
  - `traefik.file_mode`;
  - `site_types: [newt]`, local and basic WireGuard sites disabled;
  - per-node `app.region` and `exit_node_name`;
  - the shared `server.secret`;
  - `postgres` pointed at HAProxy, with replicas set to the local member;
  - `redis` pointed at HAProxy;
  - `local_exit_node_reachable_at`.
- Traefik: the file provider on Pangolin's dynamic directory (no HTTP provider). HomeGAP's routes
  file moves there, and service references change from `@http` to `@file`.
- Bootstrap (`PangolinBootstrapClient`) runs once for the cluster, not per node: server admin, org,
  API key, license activation, then a recheck on every other node.
- Rollout: the first node alone, then the others, keeping today's one-at-a-time update and
  rollback. Rollback snapshots become PostgreSQL-aware: a version upgrade migrates the shared
  database, so the snapshot is a `pg_dump` taken before the first node upgrades.
- **Done when:** e2e nodes run as one cluster, and the API logs in through either node.

### Phase 4: reconcile against one Pangolin

- `TunnelReconciler`:
  - one site per node, created through that node's API so the region pins it;
  - Newt endpoints stay the per-node management hosts.
- `ResourceReconciler`:
  - one blueprint for the cluster org;
  - each public resource gets a target on every node's site;
  - each private resource lists every node's site;
  - stale targets are removed when a node leaves.
- Passthrough and edge termination: the SNI router file and wildcard certificate as today. An
  e2e check on both nodes asserts the origin certificate for passthrough names and the node
  certificate for terminated names.
- `DnsReconciler`: adds the geo-routed `client_hostname` record (with monitors) pointing at node
  IPs; the per-node management records are unchanged.
- The servable-DNS guard now checks the resource's target on that node's site.
- **Done when:** the full e2e verify passes on the cluster:
  - services in both TLS modes, and raw ports;
  - private access from a client, which relays through the nearest node.

### Phase 5: users and clients from the dashboard

- Users:
  - create through the cluster: an invite (Integration API), completed with a password set in the
    HomeGAP dashboard, or the invite link handed to the person;
  - list, disable and remove users;
  - assign roles.
- Devices: list each user's clients and machine clients; archive or revoke them.
- Client access panel: the `pangolin.<domain>` address to put into the app, and which node each
  client is relaying through.
- **Done when:** a user created in the dashboard signs in to the mobile app with
  `pangolin.<domain>` and reaches a private service.

### Phase 6: cluster status in the dashboard

- Members (homelab and nodes): role (primary or replica), replication lag, etcd health, Valkey
  role and mesh handshake.
- Primary history, a pending switch-back with its countdown, and a manual "switch primary now".
- Alerts: no quorum; a member isolated; lag above a threshold; the license state.

### Phase 7: migration of an existing deployment

Existing node Pangolins hold nothing HomeGAP can't recreate from labels: their sites, resources
and API keys are all generated. Migration rebuilds instead of copying data:

1. Deploy the homelab member (Phases 1 and 2). This doesn't touch live traffic.
2. Pick the first node:
   - pause it and drain it from DNS;
   - re-provision it in cluster mode (bootstrap, license);
   - reconcile resources;
   - verify, then return it to DNS.
3. Repeat for each remaining node. While migrating, standalone and cluster nodes coexist. Each is
   still served by its own homelab tunnel, so public traffic is unaffected.
4. Free the per-node license keys. Anything hand-made on `primary_pangolin` is recreated through
   labels or the dashboard.

`pg_dump` and restore preserved the cluster license's host id in the prototype, so a cluster can
also be moved between databases later.

### Phase 8: docs and e2e

- README:
  - architecture and traffic paths;
  - the failure-modes table (homelab down, primary node down, partition, mesh down);
  - the client setup;
  - the cluster section of the configuration table.
- Replace the main e2e with a HomeGAP-driven cluster: the homelab member plus two nodes, and the
  prototype's failover, switch-back and partition tests as automated checks.
- Remove the `primary_pangolin` docs.

## Behaviour to document

| Event | Public traffic | Connected clients | New logins and devices |
| --- | --- | --- | --- |
| Homelab down | Down (only origin) | Stay connected; homelab services unreachable | Work after about 20 s (a node takes the primary) |
| Homelab back | Restored | Unaffected | Unaffected. Switch-back after the hold pauses writes for about 1–2 s |
| A node down | Served by the other nodes (Bunny failover) | Clients relaying through it reconnect through another node | Unaffected, unless it held the primary: then about 20 s |
| Leader cut off from the mesh | Unaffected | Unaffected | About 20 s while the rest elect a new primary. The isolated member stops taking writes |
| Mesh between two nodes broken | Unaffected | Unaffected | Unaffected while a majority can still reach each other |

## Decisions

- **Public login pages on `client_hostname`: yes.** The Pangolin apps sign in through the browser,
  so new devices can't enroll without the UI. Every node serves it, so it works while the homelab
  is down. Protections: Pangolin's login rate limit (15 per 15 minutes, cluster-wide), optional
  two-factor per user, and CrowdSec in front when `pangolin.crowdsec` is on. This also fixes the
  404 the app hit on a node's management host.
- **Continuous nearest-site re-selection: not pursued.** Clients pick the nearest node when they
  connect or change network, which covers roaming. No released Olm re-measures during a session
  (checked through its development branch). The mobile apps ship their own client, so HomeGAP
  couldn't change it anyway. Switching mid-session would also reset open connections to private
  services (SSH sessions, streams). Revisit if Pangolin adds it.
- **Own Patroni image: yes**, published by this repository's workflow next to the HomeGAP image.
  - **Why our own:** it's small (PostgreSQL plus Patroni and its etcd driver), pins exact versions,
    and is built for both architectures.
  - **Why not Spilo,** Zalando's published image: it's much larger, configured through its own
    environment variables, and bundles backup tooling HomeGAP doesn't use.
- **Pinned versions** (bumped deliberately, like Pangolin's):
  - PostgreSQL 17 and Patroni 4.1 (from the PostgreSQL apt repository);
  - etcd 3.5;
  - Valkey 8.1;
  - HAProxy 3.0 (LTS).

  A future PostgreSQL major upgrade needs a planned `pg_upgrade` path, so it gets its own item.
- **Primary on a node: deferred.** The homelab stays the default primary. The option
  (`cluster.primary.preferred: <node>`) uses the same machinery and can be added later.
