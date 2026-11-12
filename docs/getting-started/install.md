# Install on the homelab

This page tells you how to run the orchestrator and the tunnel supervisor with `deploy/homelab-compose.yml`. The first start is a dry run, so that you can examine the changes before HomeGAP makes them.

## The image

The image is `ghcr.io/drewcarlson/homegap`, for `linux/amd64` and `linux/arm64`. The same image runs the orchestrator (`homegap run`) and the tunnel supervisor (`homegap tunnels`).

## The folder

Put these files in one folder:

| Path | Content |
| --- | --- |
| `compose.yml` | A copy of `deploy/homelab-compose.yml`. |
| `config/config.yml` | The config file. |
| `secrets/ssh/<node id>` | The private SSH keys, if you add nodes by hand. |
| `.env` | The secrets. Use mode 600, and never commit this file. |
| `backups/` | In cluster mode, the nightly `pg_dump` files. HomeGAP keeps the newest seven. |

## The compose services

| Service | What it does |
| --- | --- |
| `homegap-mesh` | A busybox container that holds the network namespace of the cluster. The mesh interface (`hg-mesh`) and the cluster containers of the homelab are in it. |
| `homegap-mesh-agent` | The same image as the orchestrator, with the command `mesh`. It keeps the mesh interface and the firewall of that namespace. HomeGAP uses it only with `cluster:`. |
| `orchestrator` | The image `ghcr.io/drewcarlson/homegap:latest` with the command `run`. It drops all capabilities except `NET_ADMIN` and `DAC_OVERRIDE`. |
| `docker-proxy` | A filter in front of the Docker socket. The orchestrator reads the container list, the events and the image data through it. |
| `homegap-etcd`, `homegap-db`, `homegap-valkey`, `homegap-sentinel` | The cluster member of the homelab. HomeGAP uses them only with `cluster:`. |
| `tunnels` | The same image with the command `tunnels --dir /tunnels`. It keeps one Newt connector for each node. |

### `homegap-mesh`

The service holds a network namespace for the cluster only. The orchestrator is not in it. It is on one network, `homegap-cluster`, which it shares with the orchestrator. See [Cluster mesh](../cluster/mesh.md#the-interfaces).

The service uses a fixed image. Do not recreate it when you update HomeGAP. A new container replaces the namespace and drops the mesh.

### `homegap-mesh-agent`

The agent is the only container in the namespace of `homegap-mesh` that can change the network. It has `NET_ADMIN` and `NET_RAW`. It shares the `homegap-mesh` volume with the orchestrator. Without `cluster:`, it waits.

### `orchestrator`

The published ports and the Traefik labels of the dashboard go on this service. It is on three networks: `traefik`, `homegap-docker` (where it reaches `docker-proxy`) and `homegap-cluster` (where it reaches the cluster).

The orchestrator reads these environment variables:

- `BUNNY_API_KEY`. It is necessary.
- `PANGOLIN_LICENSE_KEY`. It is optional.
- `HOMEGAP_DASHBOARD_TOKEN`. The bearer token of the dashboard. If it is empty, HomeGAP creates a token.
- `HOMEGAP_CLUSTER_CONFIG_DIR`, `HOMEGAP_TUNNELS_DIR` and `HOMEGAP_MESH_DIR`. The mount points of the three shared volumes.
- `DOCKER_HOST`. The address of `docker-proxy`.
- `HOMEGAP_DRY_RUN`. It is optional.

The orchestrator has these volumes:

- `./config/config.yml:/config/config.yml`. This mount must be writable only if you enable config edits on the dashboard.
- `./secrets/ssh:/secrets/ssh:ro`. The SSH keys of the nodes.
- `homegap-data:/data`. The state cache, `known_hosts`, the generated SSH keys and the dashboard token. No other container mounts this volume.
- `homegap-cluster-config:/cluster-config`. The config that the orchestrator writes for the cluster containers. They mount it read-only.
- `homegap-tunnels:/tunnels`. The connector specs. The `tunnels` container mounts the same volume.
- `homegap-mesh:/mesh`. The description of the mesh, for the mesh agent.
- Optional: the `acme.json` file of the homelab Traefik, read-only.

The orchestrator does not mount the Docker socket. See [Security model](../reference/security.md#the-homelab).

### `docker-proxy`

The service mounts the Docker socket and lets through only `GET` requests for containers, images and events. It is only on the `homegap-docker` network, so no other container can reach it.

### The cluster member

Without cluster mode, you can remove `homegap-etcd`, `homegap-db`, `homegap-valkey` and `homegap-sentinel`. In cluster mode, they wait until the orchestrator writes their config to the `homegap-cluster-config` volume. While they wait, their log shows "waiting for ... (written by the HomeGAP orchestrator)".

The file limits the log of each service to 30 MB (`x-logging`).

### `tunnels`

The tunnel supervisor is independent of the orchestrator. If the orchestrator stops, the tunnels stay up. It shares only the `homegap-tunnels` volume with the orchestrator.

The `tunnels` service must share a Docker network with Traefik. It must also share a network with each service that HomeGAP exposes directly, that is raw TCP and UDP services and dedicated subdomains.

For `tunnels.native: true`, the service needs `cap_add: [NET_ADMIN]` and `devices: ["/dev/net/tun:/dev/net/tun"]`.

## Install

1. Copy `deploy/homelab-compose.yml` to `compose.yml` in your folder.
2. Copy `config/config.example.yml` to `config/config.yml`.
3. In `config/config.yml`, set `domain`, `bunny.zone` and `pangolin.acme_email`.
4. Set `certificates`. See [Certificates](../configuration/certificates.md).
5. Add the nodes to `nodes[]`. To use the wizard, set `nodes: []` instead. The wizard writes each node to the config file.
6. For cluster mode, add `cluster: {}`.
7. For the first start, set `reconcile.dry_run: true`.
8. Create `.env` next to `compose.yml`, with this content:
    ```text
    BUNNY_API_KEY=...
    PANGOLIN_LICENSE_KEY=...
    HOMEGAP_DASHBOARD_TOKEN=...
    ```
    `PANGOLIN_LICENSE_KEY` is optional. For `HOMEGAP_DASHBOARD_TOKEN`, use a long random string.
9. Make sure that the config has `dashboard.auth_token_env: HOMEGAP_DASHBOARD_TOKEN`. The example config has it.
10. If you set no dashboard token, read the token that HomeGAP creates after the first start. See [Dashboard](../operations/dashboard.md#access).
11. Make the file readable only by you:
    ```bash
    chmod 600 .env
    ```
12. Make sure that your Traefik network exists. Its name must match the external network `networks.traefik` in `compose.yml`.
13. Add the front door labels to your homelab Traefik container:
    ```yaml
    labels:
      homegap.expose: "example.net"
      homegap.wildcard: "true"
      homegap.protocol: "https"
      homegap.tls: "passthrough"
    ```
    If the HTTPS entrypoint of Traefik does not listen on 443 inside its container, also add `homegap.port: "<that port>"`.
14. Add the HTTP provider of HomeGAP to the static configuration of your homelab Traefik. With it, Traefik answers 421 for the names that only the nodes serve. See [When a browser uses one connection for two names](../services/traffic-paths.md#when-a-browser-uses-one-connection-for-two-names).
    ```yaml
    providers:
      http:
        endpoint: http://orchestrator:8088/api/traefik/homelab
        pollInterval: 30s
    ```
    Keep your other providers. Traefik can use the file provider and the HTTP provider at the same time. The address is the dashboard of the orchestrator, on the `traefik` network. The dashboard must listen on that network: `dashboard.bind: 0.0.0.0:8088`, as in the example config. Traefik does not need the dashboard token for this address. The orchestrator accepts a request from the front door container that Traefik did not forward for another client.
15. Recreate the Traefik container, so that it gets the labels and the provider.
16. Start the services:
    ```bash
    docker compose up -d
    ```
17. Look at the log of the orchestrator:
    ```bash
    docker compose logs -f orchestrator
    ```
18. Check the config:
    ```bash
    docker compose exec orchestrator homegap validate
    ```
    The command prints `OK: N node(s), domain ..., zone ...`, or the list of errors.

## Open the dashboard

!!! danger "Warning"
    Never expose the dashboard publicly. The dashboard has control of every node.

For a first look, publish the dashboard only on the loopback address of the homelab. Then use an SSH tunnel from your workstation.

1. On the `orchestrator` service, add `ports: ["127.0.0.1:8088:8088"]`.
2. Recreate the service:
    ```bash
    docker compose up -d
    ```
3. On your workstation, open the SSH tunnel:
    ```bash
    ssh -L 8088:127.0.0.1:8088 <homelab>
    ```
4. Open `http://localhost:8088` in your browser. If you set a token, the dashboard asks for it.

For the long term, publish the dashboard as a private Pangolin resource. See [Private services](../services/private.md).

## Disable dry run

In dry run, the orchestrator does full discovery and writes the desired state to the log. It makes no SSH, API or DNS writes.

1. Open the **Config** page on the dashboard.
2. Examine the diff between the desired state and the actual state.
3. Make sure that each secret in the config shows as present. The page never shows the values.
4. Set `reconcile.dry_run: false` in `config/config.yml`. The orchestrator reads the file again on each pass, so you do not restart it.

If you use `HOMEGAP_DRY_RUN` instead, remove it from the `environment` of the `orchestrator` service. Then run `docker compose up -d` to recreate the container.

## Dry run reference

Each of these starts the orchestrator in dry run:

- The environment variable `HOMEGAP_DRY_RUN` with the value `true`, `1` or `yes`.
- The option `--dry-run` of `homegap run`.
- The config key `reconcile.dry_run: true`.

To see what a set of labels produces without Docker, use `homegap plan`:

```bash
homegap plan --labels labels.json
```

The file `labels.json` is a JSON map of container names to labels.

If you make an edit to the config that is not valid, the orchestrator writes the error to the log. The last good config stays in effect.
