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
| `homegap-mesh` | A busybox container that holds the network namespace of the orchestrator. Thus the cluster mesh interface (`hg-mesh`) stays up when the orchestrator restarts. |
| `orchestrator` | The image `ghcr.io/drewcarlson/homegap:latest` with the command `run`. It uses `network_mode: service:homegap-mesh` and `cap_add: [NET_ADMIN]`, for the mesh in cluster mode. |
| `homegap-etcd`, `homegap-db`, `homegap-valkey`, `homegap-sentinel` | The cluster member of the homelab. HomeGAP uses them only with `cluster:`. |
| `tunnels` | The same image with the command `tunnels --dir /data/tunnels`. It keeps one Newt connector for each node. |

### `homegap-mesh`

The networks, the published ports and the Traefik labels of the orchestrator go on this service, not on `orchestrator`. The service is on the `traefik` network with the alias `orchestrator`.

The service uses a fixed image. Do not recreate it when you update HomeGAP. A new container replaces the namespace and drops the mesh.

### `orchestrator`

The orchestrator reads these environment variables:

- `BUNNY_API_KEY`. It is necessary.
- `PANGOLIN_LICENSE_KEY`. It is optional.
- `HOMEGAP_DRY_RUN`. It is optional.

The orchestrator has these volumes:

- `/var/run/docker.sock:/var/run/docker.sock:ro`. The Docker socket is read-only, because the orchestrator only reads labels.
- `./config/config.yml:/config/config.yml`. This mount must be writable only if you enable config edits on the dashboard.
- `./secrets/ssh:/secrets/ssh:ro`. The SSH keys of the nodes.
- `homegap-data:/data`. The state cache, `known_hosts` and the tunnel specs.
- Optional: the `acme.json` file of the homelab Traefik, read-only.

### The cluster member

Without cluster mode, you can remove `homegap-etcd`, `homegap-db`, `homegap-valkey` and `homegap-sentinel`. In cluster mode, they wait until the orchestrator writes their config under `/data/cluster/config`. While they wait, their log shows "waiting for ... (written by the HomeGAP orchestrator)".

### `tunnels`

The tunnel supervisor is independent of the orchestrator. If the orchestrator stops, the tunnels stay up. It shares the `homegap-data` volume with the orchestrator.

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
    `PANGOLIN_LICENSE_KEY` and `HOMEGAP_DASHBOARD_TOKEN` are optional. For the token, use a long random string.
9. If you set a dashboard token, set `dashboard.auth_token_env: HOMEGAP_DASHBOARD_TOKEN` in the config.
10. If you set a dashboard token, add `HOMEGAP_DASHBOARD_TOKEN` to the `environment` of the `orchestrator` service.
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
14. Recreate the Traefik container, so that it gets the labels.
15. Start the services:
    ```bash
    docker compose up -d
    ```
16. Look at the log of the orchestrator:
    ```bash
    docker compose logs -f orchestrator
    ```
17. Check the config:
    ```bash
    docker compose exec orchestrator homegap validate
    ```
    The command prints `OK: N node(s), domain ..., zone ...`, or the list of errors.

## Open the dashboard

!!! danger "Warning"
    Never expose the dashboard publicly. The dashboard has control of every node.

For a first look, publish the dashboard only on the loopback address of the homelab. Then use an SSH tunnel from your workstation.

1. On the `homegap-mesh` service, add `ports: ["127.0.0.1:8088:8088"]`.
2. Recreate the service:
    ```bash
    docker compose up -d
    ```
    Docker also restarts the containers that share the namespace of `homegap-mesh`.
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
