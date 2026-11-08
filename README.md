<div align="center">

# HomeGAP

**Homelab Global Access Platform**

Publish homelab services worldwide through your own regional edge nodes — no inbound ports, no third-party tunnel.

[![Build](https://github.com/DrewCarlson/HomeGAP/actions/workflows/build.yml/badge.svg)](https://github.com/DrewCarlson/HomeGAP/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Container image](https://img.shields.io/badge/ghcr.io-drewcarlson%2Fhomegap-2496ED?logo=docker&logoColor=white)](https://github.com/DrewCarlson/HomeGAP/pkgs/container/homegap)

</div>

---

HomeGAP is a self-hosted alternative to Cloudflare Tunnels and WARP. Your homelab stays the single
origin and is never exposed; regional VPS nodes terminate connections close to each audience and
tunnel back home over outbound-only WireGuard. A single controller container drives everything
from Docker labels:

- **Regional nodes** are any Ubuntu VPS, from any provider and in any location, running
  [Pangolin EE](https://github.com/fosrl/pangolin) with Gerbil and Traefik.
- **[Bunny DNS](https://bunny.net/dns/)** steers each viewer to the nearest node and fails over
  using health checks.
- **The orchestrator** reads Docker labels on the homelab and turns them into Pangolin resources
  on every node, the Newt tunnels that feed them, and the Bunny DNS records that steer viewers.
  It also provisions and maintains the nodes themselves over SSH.

> [!NOTE]
> HomeGAP is early-stage software. Validate it on a domain that nothing depends on yet — a spare
> domain, or a subdomain delegated to Bunny — before moving production traffic onto it (see
> [Getting started](#getting-started)).

## Table of contents

- [Features](#features)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Getting started](#getting-started)
- [Service labels](#service-labels)
- [Configuration](#configuration)
- [Operations](#operations)
- [Performance](#performance)
- [Dashboard & API](#dashboard--api)
- [Security model](#security-model)
- [Failure modes](#failure-modes)
- [Development](#development)
- [Contributing](#contributing)
- [Acknowledgements](#acknowledgements)
- [AI assistance](#ai-assistance)
- [License](#license)

## Features

- **Origin stays hidden** — no inbound ports at home; every tunnel is outbound.
- **End-to-end TLS by default** — nodes route by SNI and never see plaintext; flip one label to
  terminate on the node instead.
- **HTTP(S), raw TCP and UDP**, plus private services reachable only through the Pangolin client.
- **Geo routing with health-checked failover** — viewers reach the nearest healthy node.
- **One-label exposure** — Docker labels are the source of truth; the controller reconciles the rest.
- **Hands-off node lifecycle** — bare VPS to working edge node over SSH, with drift repair.
- **Built-in dashboard** for observability, pausing, re-provisioning and node management.
- **Controller is never in the traffic path** — if it stops, nodes keep serving, tunnels stay
  up and DNS keeps resolving. Everything remains manageable by hand: SSH into a node, use each
  node's Pangolin dashboard, or use the Bunny console.

## Architecture

Solid arrows are viewer traffic; dashed arrows are the orchestrator's control plane, which is
never in the traffic path.

```mermaid
flowchart TD
    viewer([Viewers])
    dns["Bunny DNS<br/>geo routing + health checks"]
    node["Nearest regional node<br/>Pangolin EE · Gerbil · Traefik"]

    subgraph homelab["Homelab (no inbound ports)"]
        newt["Newt connector<br/>homegap tunnels"]
        traefik["Homelab Traefik"]
        services["Services"]
        orch["Orchestrator<br/>homegap run"]
    end

    viewer --> dns
    dns -->|resolves to| node
    node <-->|WireGuard tunnel, dialed outbound from home| newt
    newt --> traefik
    traefik --> services

    orch -.->|reads Docker labels| traefik
    orch -.->|connector specs| newt
    orch -.->|SSH + Pangolin API| node
    orch -.->|Bunny API| dns
```

### Reconciliation

The orchestrator is a reconciler. It continuously drives the *actual* state toward the *desired*
state. Desired state is the Docker labels plus the config file and nothing else. The orchestrator
works in six independent domains, so a failure in one never blocks the others:

| Domain | What it does |
| --- | --- |
| Discovery | Reads `homegap.*` labels from the Docker socket (mounted read-only). If discovery fails, the resource and DNS domains are skipped for that pass, so an empty view can never delete anything. |
| Nodes | Uses SSH to turn a bare Ubuntu 22.04/24.04/26.04/26.10 VPS into a Pangolin EE node: preflight, Docker, `ufw`, the compose stack, secrets, health wait, headless bootstrap (admin, org, least-privilege API key, license) and a verify step. Every step is idempotent, and a periodic drift check repairs out-of-band changes. |
| Tunnels | On each node, keeps a Pangolin site that represents the homelab. Writes one Newt connector spec per node for the `homegap tunnels` supervisor. |
| Resources | Declares Pangolin resources through per-resource blueprints keyed by deterministic `hg-*` niceIds. It deletes only stale `hg-*` resources and never touches unmanaged ones. It also writes the node's hot-reloaded Traefik routes (SNI passthrough, health, management API). |
| DNS | Keeps Bunny geo record sets for the root, the wildcard and each dedicated hostname, plus a plain record per node management host. |
| Observability | Collects node, tunnel, resource and DNS health for the dashboard. |

Reconciliation runs in three ways at once:

- **Event-driven:** on Docker events, debounced.
- **Periodic:** a full resync, every 60s by default.
- **On demand:** from the dashboard.

Every pass applies only the minimal diff.

### Traffic paths

- **Front door, passthrough (default).** The homelab Traefik claims the root domain and
  `*.domain`. On each node, a Traefik TCP router on `:443` matches the TLS SNI (root, plus any
  single label under the domain) and forwards the still-encrypted stream into the Pangolin TCP
  resource and through the tunnel to the homelab Traefik. The node never sees plaintext, and
  Authelia and anything else at home keep working unchanged.
- **Front door, terminate.** Setting `homegap.tls=terminate` swaps in Pangolin HTTP resources for
  the root and the wildcard. The node ends TLS (it already holds the same wildcard certificate)
  and re-proxies to the homelab Traefik with the host preserved. Wildcard HTTP resources are a
  Pangolin EE feature and need a license key on every node (see [EE licenses](#ee-licenses)).
  A node that refuses the wildcard is automatically left out of the wildcard DNS set, so viewers
  are never steered to a node that would answer 404.
- **Dedicated subdomain.** A labeled service with its own hostname gets its own resource, which
  takes precedence over the wildcard. HTTPS services default to passthrough straight to the
  service; plain HTTP services are terminated on the node.
- **Raw TCP/UDP.** The service gets its own public port on its nodes: a Traefik entrypoint, a
  published Gerbil port and a `ufw` rule. It also gets its own DNS record.
- **Private.** Private services become Pangolin private resources (with an alias set to the
  hostname) on the primary Pangolin and on their nodes. They are reachable only through the
  Pangolin client, and get no public DNS record and no public port.

### Health and failover

Each geo record carries a Bunny HTTP monitor. Bunny probes `http://<node-ip>/` every 30s from
three regions. On the node, that request is routed through the tunnel to the front door's
`homegap.healthcheck` path, so a node only counts as healthy when it is up, its tunnel is up and
the origin answers:

- If the origin is down, the probe gets a 502.
- If the tunnel is down, the probe fails within 5s.

Bunny drops offline members from a record set, so those viewers resolve to the next-nearest node.
When every member is offline, Bunny returns all of them (fail-open). Realistic failover time is
the health interval plus the 60s TTL plus resolver caching: roughly 1–5 minutes.

## Requirements

| Component | Requirement |
| --- | --- |
| Homelab | Docker with Compose; a Traefik instance fronting your services |
| Regional nodes | Ubuntu 22.04, 24.04, 26.04 or 26.10 VPS (x86_64 or aarch64), SSH access as a user with passwordless sudo, public IPv4 (IPv6 optional) |
| DNS | A [Bunny DNS](https://bunny.net/dns/) zone and API key |
| Pangolin EE license | Optional; required for wildcard resources in `terminate` mode — one key per Pangolin instance (see [EE licenses](#ee-licenses)) |
| Primary Pangolin | Optional; a hand-curated Pangolin EE on the homelab for private (client) access |

## Getting started

The container image is published at `ghcr.io/drewcarlson/homegap` for `linux/amd64` and
`linux/arm64`. The same image runs both the orchestrator (`homegap run`) and the tunnel
supervisor (`homegap tunnels`).

### Manual steps (once)

1. Choose a test domain that no live services depend on — a spare domain, or a subdomain of your
   real domain (e.g. `gap.example.net`) delegated to Bunny with NS records — host it in Bunny DNS,
   and create an API key.
2. Create a bare VPS per region (Ubuntu 22.04, 24.04, 26.04 or 26.10) that is reachable over SSH as a user with
   passwordless sudo. Use a dedicated SSH key per node.
3. Stand up the primary Pangolin EE on the homelab by hand (optional, for private access). Create
   its admin, org and homelab site, plus an Integration API key.
4. Label your Traefik container (root + wildcard + `tls`, see [Service labels](#service-labels))
   and write `config/config.yml` starting from
   [`config/config.example.yml`](config/config.example.yml).
5. Run the orchestrator and the tunnel supervisor on the homelab
   ([`deploy/homelab-compose.yml`](deploy/homelab-compose.yml)) with the Docker socket mounted
   read-only, plus the config, the SSH keys and the secrets.

> [!TIP]
> Try `HOMEGAP_DRY_RUN=true` first: it runs full discovery and logs the desired state, with no
> SSH, API or DNS writes. `homegap plan --labels labels.json` prints what a set of labels
> produces without touching Docker.

### Automated by the orchestrator

6. Provision each node (Docker, `ufw`, Pangolin EE + Gerbil + Traefik, secrets, certificate,
   admin, org, API key, license).
7. Create one homelab site per node and one Newt connector per node.
8. Create the passthrough or terminate resources, the dedicated resources, and the Bunny geo
   records and monitors.
9. Verify on the test domain: nodes green, each name resolving per region, failover working, and
   the passthrough/terminate toggle working. Then point `domain` at your production domain and
   move it to Bunny.

## Service labels

Exposing a service is a one-label change. Everything the homelab Traefik fronts needs no labels:
the Traefik container's claim on the root and the wildcard covers it.

| Label | Default | Description |
| --- | --- | --- |
| `homegap.expose` | — (required) | Hostname to expose, e.g. `app.example.net`, or the root `example.net`. Must be inside `domain`. |
| `homegap.wildcard` | `false` | On the root entry, also claim `*.<domain>`. |
| `homegap.port` | 443 for https, 80 for http | Target container port. Required for tcp/udp. |
| `homegap.protocol` | `http` | `http` / `https` / `tcp` / `udp` |
| `homegap.tls` | `passthrough` | `passthrough` (the node sees only SNI) or `terminate` (the node ends TLS; Pangolin auth possible). Plain `http` is always terminated. |
| `homegap.regions` | `all` | Comma-separated list of node ids, e.g. `na1,as1`. |
| `homegap.access` | `public` | `public`, or `private` (Pangolin client only). |
| `homegap.auth` | `none` | `none` / `sso` / `pin`. Requires `tls=terminate`. |
| `homegap.healthcheck` | `/` (front door probe) | HTTP path or `tcp`. Used for Pangolin target health and by the node's Bunny health probe. |

Extensions:

| Label | Description |
| --- | --- |
| `homegap.host` | Host the tunnel dials. Defaults to the container name, which must resolve on a network shared with the `tunnels` container. |
| `homegap.public_port` | Public port on nodes for tcp/udp services. Defaults to `homegap.port`. Ports used by the node itself (22, 80, 443, 51820, …) are rejected. |
| `homegap.roles` | Pangolin roles allowed for `auth=sso` and private resources. |
| `homegap.pin_env` | Environment variable on the orchestrator holding the 6-digit PIN for `auth=pin`. |
| `homegap.pause` | `true` holds this service's Pangolin resources and DNS records as they are. |

### Examples

```yaml
# The homelab Traefik claims root + wildcard with end-to-end TLS.
traefik:
  labels:
    homegap.expose: "example.net"
    homegap.wildcard: "true"
    homegap.protocol: "https"
    homegap.tls: "passthrough"      # flip to "terminate" to A/B test

# A raw-TCP service on its own subdomain, private, on two nodes.
ssh-jump:
  labels:
    homegap.expose: "ssh.example.net"
    homegap.port: "22"
    homegap.protocol: "tcp"
    homegap.access: "private"
    homegap.regions: "na1,as1"

# A public game server on UDP 27015, served from one region.
game:
  labels:
    homegap.expose: "game.example.net"
    homegap.protocol: "udp"
    homegap.port: "27015"
    homegap.regions: "eu1"
```

Errors are reported per container in the dashboard and the log, and never block other services.
Examples of errors: an unknown protocol, auth without terminate, a reserved public port, or a
hostname claimed twice.

## Configuration

One YAML file holds the configuration. A complete example is
[`config/config.example.yml`](config/config.example.yml). Secrets are referenced, never inlined:

- `*_env` names an environment variable.
- `*_file` names a mounted file.
- `/run/secrets/<ENV_NAME>` is also checked, so Docker secrets work.

Key sections:

| Section | Purpose |
| --- | --- |
| `domain`, `bunny.zone` | The served domain and its Bunny zone (equal to, or a parent of, the domain). |
| `bunny.routing` | `geolocation` uses each node's `location` (latitude/longitude). If only a `region` code is given (`NA`, `SA`, `EU`, `AF`, `ME`, `AS`, `OC`), a rough centroid is used. `latency` uses `latency_zone`. `none` turns off smart routing. |
| `bunny.monitor` | `http` (recommended), `ping` or `none`. |
| `pangolin` | Image versions, ACME email, org id, optional default `license_key_env`, optional CrowdSec, and `base_blueprint` — a Pangolin blueprint applied to every node before the managed resources. |
| `certificates` | How the wildcard + root certificate reaches the nodes: `none` (nodes use their own ACME HTTP-01), `files` (PEM chain and key), or `acme_json` (read the homelab Traefik's ACME store, mounted read-only; the certificate must be issued with DNS-01, for example Traefik's `bunny` provider). |
| `primary_pangolin` | Integration API URL, key, org and site of the hand-curated homelab Pangolin. HomeGAP only writes private resources there. |
| `nodes[]` | Per node: `id`, `host`, SSH user, port and key file, `location`, and optionally `license_key_env` (each node needs its own EE key), `public_ipv4` / `public_ipv6`, `management_host` (default `pangolin-<id>.<domain>`), `dashboard_public` (default `false`), `firewall` (default `true`), `install_docker` (default `true`). |
| `tunnels` | Shared state directory, `mtu` (WireGuard MTU for Newt and every Gerbil) and `native` (kernel WireGuard for Newt). See [Performance](#performance). |
| `pangolin.tcp_tuning` | BBR, fq and larger TCP buffers on every node (default `true`). |
| `speedtest` | `enabled`, `hostname` (default `speedtest`), `bind` (default `0.0.0.0:8089`), `link_ttl`, `phase_seconds`, `streams` (homelab ↔ node, default 4). |
| `dashboard` | Bind address, optional bearer token, and whether config edits from the UI are allowed. |

The orchestrator re-reads the file on every pass. An invalid edit is logged, and the last good
configuration stays in effect.

### EE licenses

Pangolin EE license keys are **bound to a single Pangolin instance** when first activated: a
second instance trying the same key is rejected ("already been activated with a different
instance name"). Each regional node and the primary need their own key. Give each node one with
`nodes[].license_key_env`; `pangolin.license_key_env` is only a default for single-node setups.
A rejected key is reported once per node (dashboard check `ee-license`) and is not retried until
the key changes.

> [!WARNING]
> Activations are recorded by the license server, so avoid activating production keys on
> throwaway or test instances.

### Node updates and rollback

Provisioning runs in the background, so a slow node (a first install, a Docker download) never
delays health checks, tunnels or DNS for the others. The dashboard shows `provisioning…` or
`updating…` on the node while it runs.

When a change alters the stack of nodes that are already healthy (a new image version, MTU,
tuning, raw ports and so on), the update **rolls out one node at a time**:

1. The running stack is saved on the node (`<install_dir>/.homegap-rollback`): every managed file
   and, when the Pangolin image changes, Pangolin's database, since a newer version migrates it.
2. The node is updated, then must pass Pangolin's health check, the API bootstrap, the verify
   checks (Gerbil UDP ports, Integration API) and, if its tunnel was up, the tunnel reconnecting.
3. If any step fails, the saved stack is restored and restarted, and the node keeps serving on it.

A failed update **holds the rollout**: no other node is updated, and the dashboard shows a banner
with the error. To recover:

- **Retry**: apply the update to that node again (after fixing the cause on the node).
- **Skip**: leave that node on its current stack and let the rollout continue with the others.
- **Change the config**: a new desired stack is tried on the failed node first, then rolls out.
  Reverting the change clears the failure.

New nodes and drift repairs do not wait for rollouts. A first provisioning that fails is retried
after five minutes, or at once with *Re-provision*. Host-level steps (Docker, firewall, TCP
tuning) are idempotent and not rolled back.

### Node management host

Newt and the Pangolin client must reach each node's Pangolin API. HomeGAP routes
`https://<management_host>/api/v1` on each node. That hostname is excluded from the wildcard
passthrough and has its own plain DNS record. The node's web UI is not routed publicly by default.
To reach it, use SSH port forwarding:

```bash
ssh -L 3002:127.0.0.1:3002 deploy@<node>
```

Then open `http://localhost:3002`. Set `dashboard_public: true` if you use `auth: sso|pin`, which
needs the node's login pages to be public.

## Operations

- **Expose a service:** add labels to its container. The Docker event triggers a reconcile.
- **Add a region:** add a node to the config, or use the dashboard. It is provisioned and DNS is
  extended on the next pass.
- **Remove a region:** remove it from the config. Its DNS records drain on the next pass, and the
  stack keeps running until you confirm the decommission in the dashboard.
- **Hold a manual change:** pause globally, per node or per service (dashboard or
  `homegap.pause`). Paused targets are observed but never modified.
- **Re-provision a node:** click *Re-provision*. Destroying and recreating a VPS is safe: nodes
  are cattle.

## Performance

Every request crosses two TCP legs (viewer → node, node → homelab) and one WireGuard tunnel.
Three settings matter most:

| Setting | Effect |
| --- | --- |
| `pangolin.tcp_tuning: true` (default) | Writes `/etc/sysctl.d/99-homegap.conf` on each node: BBR congestion control, `fq` qdisc, socket buffers up to 64 MiB, MTU probing, higher reordering tolerance. The same TCP settings are applied inside the node's Gerbil/Traefik namespace (Docker containers do not inherit the host's); the Nodes tab shows them as `edge tcp`. BBR keeps long, lossy paths fast. The node fact `tcp_cc` (Nodes tab) shows what the kernel actually uses; some VPS kernels refuse. |
| `tunnels.mtu: 1420` | Sets the WireGuard MTU on both ends (Newt `--mtu`, Gerbil `--mtu`). The upstream default of 1280 costs throughput. Lower it if the path has PPPoE or another tunnel: check with `ping -M do -s <mtu - 28> <node>` from the homelab. |
| `tunnels.native: true` | Runs each Newt main tunnel on a kernel WireGuard interface instead of Newt's userspace network stack, usually the largest gain. The `tunnels` container then needs `cap_add: [NET_ADMIN]`, `devices: [/dev/net/tun]` and a host kernel with WireGuard. |

Each node gets its own tunnel subnet (`100.89.<128 + 4·index>.0/22`, by position in `nodes[]`) so
kernel interfaces for several nodes can coexist in the `tunnels` container. The subnet is fixed
when a node's Pangolin first registers its exit node, so keep the order of `nodes[]` stable, and
re-create a node that predates this layout before enabling `native` with more than one node.

For the homelab side in native mode, tune the `tunnels` container itself. TCP settings are per
network namespace, so the host's `/etc/sysctl.d` does not reach containers, and the defaults
(cubic, a 4 MiB send buffer) cap every download from the homelab to a distant node. Set them on
the service (see `deploy/homelab-compose.yml`):

```yaml
sysctls:
  net.ipv4.tcp_congestion_control: bbr   # needs `modprobe tcp_bbr` on the host
  net.ipv4.tcp_rmem: "4096 131072 67108864"
  net.ipv4.tcp_wmem: "4096 65536 67108864"
  net.ipv4.tcp_slow_start_after_idle: "0"
  net.ipv4.tcp_mtu_probing: "1"
  net.ipv4.tcp_reordering: "10"          # tolerate reordering on long routes
  net.ipv4.tcp_max_reordering: "1000"
```

Check with `docker compose exec tunnels sysctl net.ipv4.tcp_congestion_control net.ipv4.tcp_wmem`.
On a path with about 170 ms round trip this took one setup from about 115 to about 250 Mbps.
Also enable `fq` on the host (`net.core.default_qdisc = fq`), which BBR paces with.

In userspace mode Newt uses its own TCP stack, so these settings do not apply; raising
`net.core.rmem_max` / `net.core.wmem_max` on the host (for example to `7500000`) still helps its
UDP socket.

### Speed tests

With `speedtest.enabled: true` the orchestrator serves a speed test origin on `speedtest.bind`.
Route `speedtest.<domain>` and `speedtest-<node>.<domain>` to it from the homelab reverse proxy
(the front door already forwards them from the nodes). For Traefik labels on the orchestrator:

```yaml
labels:
  traefik.enable: "true"
  traefik.http.routers.homegap-speedtest.rule: "Host(`speedtest.example.net`) || HostRegexp(`^speedtest-[a-z0-9-]+\\.example\\.net$`)"
  traefik.http.routers.homegap-speedtest.entrypoints: websecure
  traefik.http.routers.homegap-speedtest.tls: "true"
  traefik.http.services.homegap-speedtest.loadbalancer.server.port: "8089"
```

The dashboard's *Speed test* tab offers two measurements: ping, jitter, download and upload.

- **Homelab ↔ node:** runs `curl` on the node over SSH against its own port 443 with the node's
  speed test hostname, so the traffic takes the production path through the tunnel. Runs
  `speedtest.streams` parallel streams (default 4, selectable per run); rates are the sum. One
  stream shows what a single connection gets over the distance; several show the tunnel's capacity.
- **Device → node → homelab:** creates a short-lived link (`speedtest.link_ttl`) to open on any
  device. `http://speedtest.<domain>/` is answered by whichever node DNS picks for the device,
  which redirects to its own `speedtest-<node>` hostname, so results are attributed to a node.
  The token travels in the URL fragment, which is never sent to a server or logged.

Results are kept in `<data_dir>/speedtests.json`. Changing `speedtest.enabled` or `bind` needs an
orchestrator restart.

## Dashboard & API

The dashboard is served by the orchestrator on `dashboard.bind`.

> [!IMPORTANT]
> Expose the dashboard **only** as a private Pangolin resource, never publicly.

It shows:

- **Nodes:** reachability; Docker, Pangolin, Gerbil and Traefik health; certificate expiry;
  tunnel state; last provision; drift.
- **Services:** per-region health.
- **DNS:** records, monitor state and whether they match the desired state.
- **Tunnels:** process state, restarts and throughput.
- **Speed test:** homelab ↔ node and device → node → homelab measurements, with history.
- **Reconcile log:** grouped by domain.
- **Desired vs. actual:** the diff, plus every referenced secret with whether it is present.
  Secret values are never shown.

| Method & path | Purpose |
| --- | --- |
| `GET /api/status` | Full status snapshot |
| `GET /api/log?domain=` | Recent reconcile log |
| `GET /api/diff` | Desired-vs-actual items and the effective desired state |
| `GET /api/secrets` | Referenced secrets (names, sources, presence only) |
| `GET /api/config` | Config file and nodes |
| `POST /api/resync` | Queue a pass |
| `POST /api/nodes/{id}/provision` | Force a full re-provision |
| `POST /api/nodes` / `DELETE /api/nodes/{id}` | Add / remove a node (rewrites the config file, with a backup) |
| `POST /api/nodes/{id}/decommission?confirm={id}` | Stop the stack on a removed node and forget it |
| `GET /api/speedtest` | Speed test settings and recent results |
| `POST /api/speedtest/nodes/{id}` | Run a homelab ↔ node speed test (blocks until done) |
| `POST /api/speedtest/link` | Create a device test link |
| `POST /api/pause` | `{"scope":"global|node|service","id":"…","paused":true}` |

## Security model

- **The orchestrator is the trust center.** It holds every SSH key and API key. Run it on a
  minimal host, mount secrets rather than baking them into the image, and keep the dashboard
  private (optionally also set `dashboard.auth_token_env`).
- **Node secrets** (Pangolin server secret, admin credentials, setup token, CrowdSec bouncer key)
  are generated from the kernel CSPRNG. They are pushed over SSH into
  `<install_dir>/secrets/node.json` (mode 0600), which is authoritative. The local state file
  (`/data/state.json`, mode 0600) is only a cache: if it is lost, credentials are re-read from
  the node.
- The orchestrator's Pangolin API key on each node is a root key restricted to the actions it
  needs. Older keys it minted are retired automatically.
- Node Pangolin ports (3000/3002/3003) are bound to loopback. The orchestrator reaches them only
  through SSH port forwarding.
- `ufw` allows only SSH, 80/443, 51820/udp and 21820/udp, plus raw ports that a label asks for.
  Rules HomeGAP added for ports that are no longer wanted are removed.
- The Docker socket is mounted read-only, and labels are only read. Tunnels run in a separate
  container, so the controller never carries traffic.
- `passthrough` keeps nodes blind to plaintext. Enable `pangolin.crowdsec` for edge abuse
  mitigation on terminated traffic. It cannot inspect passthrough streams.
- The homelab IP is never published. Every tunnel is outbound from home.

To report a security issue, please open a private
[security advisory](https://github.com/DrewCarlson/HomeGAP/security/advisories/new) rather than
a public issue.

## Failure modes

| Failure | Effect | Recovery |
| --- | --- | --- |
| Regional node down | Bunny drops it; its viewers resolve to the next-nearest node | The orchestrator repairs or re-provisions it; DNS recovers automatically |
| Node update fails | That node is rolled back to its previous stack; the rollout to other nodes stops | Retry or skip it from the dashboard, or change the config (see [Node updates and rollback](#node-updates-and-rollback)) |
| Orchestrator down | None on live traffic; only new label or config changes wait | Restart it; it reconciles to the desired state |
| Homelab origin down | All remote access down (single origin, by design) | Restore the origin; tunnels reconnect |
| Newt tunnel drops | That node's health probe fails, so it drops out of DNS | The supervisor restarts Newt with backoff; failover covers the gap |
| Certificate issuance fails on a node | That node cannot serve TLS for affected names | Push the wildcard via `certificates`, or fix ACME; the dashboard shows expiry |
| Bunny outage | DNS resolution affected | Accepted single dependency |
| State cache lost | None | Secrets are re-read from the nodes, API keys re-minted, Newt credentials recovered from the connector spec |

## Development

### Building

Requirements: JDK 21. Gradle, Kotlin/Native and Node for the dashboard are downloaded
automatically.

```bash
./gradlew macosArm64Test                       # unit tests (host target)
./gradlew linkReleaseExecutableLinuxX64 linkReleaseExecutableLinuxArm64
docker buildx build --platform linux/amd64,linux/arm64 -t homegap .
./gradlew -Phomegap.skipWeb=true …             # skip bundling the dashboard for fast iteration
./gradlew :web:jsBrowserDevelopmentServe --continuous   # dashboard dev server (proxies /api to :8088)
```

### End-to-end tests

`e2e/` contains a self-contained end-to-end harness, built from:

- a fake Ubuntu VPS (sshd plus its own Docker daemon),
- a simulated homelab (Traefik, whoami, socat),
- the orchestrator and the tunnel supervisor,
- a Bunny API stand-in.

```bash
./gradlew linkReleaseExecutableLinuxArm64   # or LinuxX64 on an amd64 Docker host
e2e/run.sh        # provisions two fake nodes from scratch over SSH
                  # (exports an untracked ./.env, e.g. PANGOLIN_LICENSE_KEY, to test EE features)
e2e/verify.sh     # checks passthrough, wildcard, terminate, raw TCP, health and redirects
```

### Repository layout

```text
src/nativeMain/kotlin/homegap/
  discovery/    Docker socket client + label parsing
  model/        pure planner: labels + config -> desired state
  reconcile/    controller loop and the node, tunnel, resource and DNS reconcilers
  clients/      Pangolin (integration + bootstrap), Bunny, SSH (OpenSSH ControlMaster)
  provision/    node stack templates, certificates, YAML emitter
  state/        local state cache
  dashboard/    REST API + embedded SPA
  tunnels/      Newt connector supervisor
api/            wire types shared by the API and the dashboard
web/            Compose for HTML dashboard, bundled with Vite (kotlin-vite)
deploy/         homelab compose; reference node stack
blueprints/     example Pangolin base blueprint
config/         example configuration
e2e/            end-to-end harness
```

## Contributing

Issues and pull requests are welcome. Before opening a pull request, please make sure the unit
tests pass (`./gradlew macosArm64Test`) and, for changes that touch provisioning, routing or
DNS, that the [end-to-end harness](#end-to-end-tests) passes.

## Acknowledgements

HomeGAP builds on [Pangolin](https://github.com/fosrl/pangolin), Newt and Gerbil by Fossorial,
[Traefik](https://traefik.io/), [Bunny DNS](https://bunny.net/dns/),
[Ktor](https://ktor.io/), [Compose for HTML](https://github.com/JetBrains/compose-multiplatform)
and [kotlin-vite](https://github.com/DrewCarlson/kotlin-vite).

## AI assistance

HomeGAP was built with AI assistance. The architecture and system design were created by a
human, and the core security code was written by a human. The rest of the implementation was
written with AI assistance under human direction and review.

## License

HomeGAP is released under the [MIT License](LICENSE).

Pangolin EE is subject to its own license terms. It is free for personal use and for
organizations below its commercial revenue threshold.
