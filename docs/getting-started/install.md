# Install on the homelab

This page tells you how to put the HomeGAP stack in a folder on the homelab and start it. You do not edit the compose file of the stack: your settings go in files of your own. The first start is a dry run, so that you can examine the changes before HomeGAP makes them.

## The image

The image is `ghcr.io/drewcarlson/homegap`, for `linux/amd64` and `linux/arm64`. The same image runs the orchestrator (`homegap run`) and the tunnel supervisor (`homegap tunnels`). It also holds the files of the stack.

## Get the files

Make an empty folder for the stack on the homelab. Then get the files in one of these two ways.

### With the image

Run `homegap init` in the folder:

```bash
docker run --rm -it -v "$PWD:/work" ghcr.io/drewcarlson/homegap init
```

The command writes the files of [The folder](#the-folder). It gives them to the owner of the folder.

### With the release archive

Each [release](https://github.com/DrewCarlson/HomeGAP/releases) has the archive `homegap-<version>.tar.gz`, with the same files.

1. Download the archive of the newest release.
2. Extract the archive. The files are in the folder `homegap`.
3. Copy `.env.example` to `.env`.
4. Copy `config/config.example.yml` to `config/config.yml`.

## The folder

| Path | Content | Who changes it |
| --- | --- | --- |
| `compose.yml` | The compose file of the stack. | HomeGAP |
| `upgrade.sh` | The upgrade script. See [Upgrade the homelab](../operations/upgrade.md). | HomeGAP |
| `.env.example` | The variables that you can set in `.env`. | HomeGAP |
| `compose.override.example.yml` | Examples of changes to the stack. | HomeGAP |
| `config/config.example.yml` | The example config. | HomeGAP |
| `.homegap/` | A copy of each file above, as HomeGAP wrote it. With the copies, HomeGAP finds the files that you changed. | HomeGAP |
| `.env` | The secrets and the variables of `compose.yml`. Use mode 600, and never commit this file. | You |
| `compose.override.yml` | Your changes to the stack. This file is optional. | You |
| `config/config.yml` | The config file. An edit from the dashboard leaves a backup next to it. | You and the dashboard |
| `data/` | All that HomeGAP writes: its state, the SSH keys of the nodes and the cluster database. Docker makes it at the first start. See [The data folder](#the-data-folder). | HomeGAP |
| `backups/` | In cluster mode, the nightly `pg_dump` files. HomeGAP keeps the newest seven. | HomeGAP |

Do not edit the files that HomeGAP changes. An upgrade replaces them with the files of the new version.

### The data folder

| Path | Content |
| --- | --- |
| `data/orchestrator/` | The data of the orchestrator: the state cache, the SSH keys of the nodes (`ssh/`), the ACME account and the dashboard token. Only the orchestrator mounts it. |
| `data/tunnels/` | The connector specs. The orchestrator shares it with the `tunnels` container. |
| `data/mesh/` | The description of the mesh and the mesh key of the homelab. The orchestrator shares it with the mesh agent. |
| `data/cluster/config/` | The config that the orchestrator writes for the cluster containers. |
| `data/cluster/postgres/`, `data/cluster/etcd/`, `data/cluster/valkey/`, `data/cluster/sentinel/` | The cluster member of the homelab. They stay empty without cluster mode. |

Include `data/` and `config/` in the backup of the homelab. The folder `data/orchestrator/` holds every credential, so keep the backup private.

To keep the data in a different place, for example on a different disk, set `HOMEGAP_DATA_DIR` in `.env`.

### Run init again

You can run `homegap init` again at any time, for example to get a file back. For each file that HomeGAP changes, the command does one of these:

| The file in the folder | What the command does |
| --- | --- |
| It is missing. | The command writes the file. |
| It has no changes from you. | The command writes the file of this version. |
| It has changes from you. | The command asks you. If you answer `y`, it saves your file as `<name>.previous` and writes the file of this version. If you answer `n`, your file stays, and the file of this version goes next to it as `<name>.new`. |

The command never changes `.env`, `compose.override.yml` or `config/config.yml`.

Without a terminal, the answer to each question is no. The option `--yes` answers each question with yes. See [Command line](../reference/cli.md#init).

## Your settings

### Variables in `.env`

| Variable | Default | What it sets |
| --- | --- | --- |
| `BUNNY_API_KEY` | None. It is necessary. | The API key of your Bunny account. |
| `PANGOLIN_LICENSE_KEY` | Empty. | The Pangolin EE license. |
| `HOMEGAP_DASHBOARD_TOKEN` | Empty. | The bearer token of the dashboard. If it is empty, HomeGAP creates a token. |
| `TRAEFIK_NETWORK` | `traefik` | The name of the Docker network of your Traefik. |
| `COMPOSE_PROFILES` | Empty. | With `cluster`, Docker Compose also starts the cluster services of the homelab. Cluster mode needs it. |
| `HOMEGAP_DATA_DIR` | `./data` | The folder of all that HomeGAP writes. |
| `HOMEGAP_VERSION` | `latest` | The tag of the HomeGAP images. Set it to stay on one version. |
| `HOMEGAP_DASHBOARD_PUBLISH` | `127.0.0.1:8088` | The address and the port of the host that publish the dashboard. |
| `HOMEGAP_DRY_RUN` | Empty. | With `true`, the orchestrator runs in dry run. |

The orchestrator gets each variable of `.env`. Thus a secret that the config refers to, for example with `license_key_env`, needs only a line in `.env`.

### Other changes in `compose.override.yml`

Docker Compose reads `compose.override.yml` after `compose.yml` and merges the two files. You give no extra argument. Put each other change to the stack in this file. For example, this file attaches the `tunnels` service to the network of a different stack:

```yaml
services:
  tunnels:
    networks:
      media: {}

networks:
  media:
    name: media_default
    external: true
```

The file `compose.override.example.yml` has the usual changes: a fixed address for `tunnels`, the private network, more networks and BBR.

## The compose services

In standalone mode, the stack has three containers: `orchestrator`, `docker-proxy` and `tunnels`. The other six services are the part of the homelab in cluster mode. They have the profile `cluster` in `compose.yml`, and Docker Compose starts them only with `COMPOSE_PROFILES=cluster` in `.env`.

| Service | What it does |
| --- | --- |
| `homegap-mesh` | A busybox container that holds the network namespace of the cluster. The mesh interface (`hg-mesh`) and the cluster containers of the homelab are in it. It has the profile `cluster`. |
| `homegap-mesh-agent` | The same image as the orchestrator, with the command `mesh`. It keeps the mesh interface and the firewall of that namespace. It has the profile `cluster`. |
| `orchestrator` | The HomeGAP image with the command `run`. It drops all capabilities except `NET_ADMIN` and `DAC_OVERRIDE`. |
| `docker-proxy` | A filter in front of the Docker socket. The orchestrator reads the container list, the events and the image data through it. |
| `homegap-etcd`, `homegap-db`, `homegap-valkey`, `homegap-sentinel` | The cluster member of the homelab. They have the profile `cluster`. |
| `tunnels` | The same image with the command `tunnels`. Its directory is `/tunnels`. It keeps one Newt connector for each node. |

### `homegap-mesh`

The service holds a network namespace for the cluster only. The orchestrator is not in it. It is on one network, `homegap-cluster`, which it shares with the orchestrator. See [Cluster mesh](../cluster/mesh.md#the-interfaces).

The service uses a fixed image. Do not recreate it when you update HomeGAP. A new container replaces the namespace and drops the mesh.

### `homegap-mesh-agent`

The agent is the only container in the namespace of `homegap-mesh` that can change the network. It has `NET_ADMIN` and `NET_RAW`. It shares the folder `data/mesh` with the orchestrator.

### `orchestrator`

The service publishes the dashboard on the loopback address of the host. To change the address, set `HOMEGAP_DASHBOARD_PUBLISH`. It is on three networks: `traefik`, `homegap-docker` (where it reaches `docker-proxy`) and `homegap-cluster` (where it reaches the cluster).

The orchestrator reads these environment variables:

- `BUNNY_API_KEY`. It is necessary.
- `PANGOLIN_LICENSE_KEY`. It is optional.
- `HOMEGAP_DASHBOARD_TOKEN`. The bearer token of the dashboard. If it is empty, HomeGAP creates a token.
- `DOCKER_HOST`. The address of `docker-proxy`.
- `HOMEGAP_DRY_RUN`. It is optional.
- Each other variable of `.env`.

The orchestrator mounts these folders:

- `./config` at `/config`. The config file is `config.yml` in it. This mount must be writable, unless you set `dashboard.allow_config_edits: false`.
- `data/orchestrator` at `/data`. The state cache, `known_hosts`, the SSH keys of the nodes and the dashboard token. No other container mounts this folder.
- `data/cluster/config` at `/cluster-config`. The config that the orchestrator writes for the cluster containers. They mount it read-only at `/config`.
- `data/tunnels` at `/tunnels`. The connector specs. The `tunnels` container mounts the same folder at `/tunnels`.
- `data/mesh` at `/mesh`. The description of the mesh. The mesh agent mounts the same folder at `/mesh`.

The paths `/cluster-config`, `/tunnels` and `/mesh` are fixed in the HomeGAP image. You set no variable for them.

If a shared directory is not a mount, the other container cannot see it. Then the orchestrator writes an error with the path and the name of the folder to its log at start.

The orchestrator does not mount the Docker socket. See [Security model](../reference/security.md#the-homelab).

### `docker-proxy`

The service mounts the Docker socket and accepts only `GET` requests for containers, images and events. It is only on the `homegap-docker` network, so no other container can reach it.

### The cluster member

The services `homegap-etcd`, `homegap-db`, `homegap-valkey` and `homegap-sentinel` start only with the profile `cluster`. After their start, they wait until the orchestrator writes their config to `data/cluster/config`. While they wait, their log shows "waiting for ... (written by the HomeGAP orchestrator)".

The file limits the log of each service to 30 MB (`x-logging`).

### `tunnels`

The tunnel supervisor is independent of the orchestrator. If the orchestrator stops, the tunnels stay up. It shares only the folder `data/tunnels` with the orchestrator.

The `tunnels` service must share a Docker network with Traefik. It must also share a network with each service that HomeGAP exposes directly, that is raw TCP and UDP services and dedicated subdomains.

The service has `NET_ADMIN` and the device `/dev/net/tun`. The setting `tunnels.native: true` needs them. To attach the service to more networks, use `compose.override.yml`.

## Install

1. Get the files. See [Get the files](#get-the-files).
2. Open `config/config.yml` in an editor.
3. In `config/config.yml`, set `domain`, `bunny.zone` and `pangolin.acme_email`.
4. Read [Certificates](../configuration/certificates.md). HomeGAP publishes CAA records for your domain, and they also apply to the Traefik of your homelab.
5. Add the nodes to `nodes[]`. To use the wizard, set `nodes: []` instead. The wizard writes each node to the config file.
6. For cluster mode, add `cluster: {}` to the config, and add `COMPOSE_PROFILES=cluster` to `.env`. See [Cluster mode](../cluster/index.md#enable-cluster-mode).
7. For the first start, set `reconcile.dry_run: true`.
8. Set the secrets in `.env`:
    ```text
    BUNNY_API_KEY=...
    PANGOLIN_LICENSE_KEY=...
    HOMEGAP_DASHBOARD_TOKEN=...
    ```
    `PANGOLIN_LICENSE_KEY` is optional. To use it, also set `pangolin.license_key_env: PANGOLIN_LICENSE_KEY` in the config. The example config has this line as a comment. For `HOMEGAP_DASHBOARD_TOKEN`, use a long random string.
9. Make sure that the config has `dashboard.auth_token_env: HOMEGAP_DASHBOARD_TOKEN`. The example config has it.
10. If you set no dashboard token, read the token that HomeGAP creates after the first start. See [Dashboard](../operations/dashboard.md#access).
11. Make the file readable only by you:
    ```bash
    chmod 600 .env
    ```
12. Make sure that the Docker network of your Traefik exists. If its name is not `traefik`, set `TRAEFIK_NETWORK` in `.env`.
13. Add the front door labels to your homelab Traefik container:
    ```yaml
    labels:
      homegap.expose: "example.net"
      homegap.wildcard: "true"
      homegap.protocol: "https"
      homegap.tls: "passthrough"
    ```
    If the HTTPS entrypoint of Traefik does not listen on 443 inside its container, also add `homegap.port: "<that port>"`.
14. Add the HTTP provider of HomeGAP to the static configuration of your homelab Traefik. HomeGAP uses it to give Traefik two routes. The first route is for the speed test and the status page, when you enable them. It sends their names to the orchestrator. See [Speed tests](../operations/speed-tests.md#routing). With the second route, Traefik answers 421 for the names that only the nodes serve. See [When a browser uses one connection for two names](../services/traffic-paths.md#when-a-browser-uses-one-connection-for-two-names).
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

The stack publishes the dashboard only on the loopback address of the homelab, at port 8088. For a first look, use an SSH tunnel from your workstation.

1. On your workstation, open the SSH tunnel:
    ```bash
    ssh -L 8088:127.0.0.1:8088 <homelab>
    ```
2. Open `http://localhost:8088` in your browser. The dashboard asks for the token: your `HOMEGAP_DASHBOARD_TOKEN`, or the token that HomeGAP created.

For the long term, give the dashboard a name at your Traefik, for Pangolin clients only. See [Dashboard](../operations/dashboard.md#serve-the-dashboard-through-your-traefik).

## Disable dry run

In dry run, the orchestrator does full discovery and writes the desired state to the log. It makes no SSH, API or DNS writes.

1. Open the **Config** page on the dashboard.
2. Examine the diff between the desired state and the actual state.
3. Make sure that each secret in the config shows as present. The page never shows the values.
4. Set `reconcile.dry_run: false` in `config/config.yml`. The orchestrator reads the file again on each pass, so you do not restart it.

If you use `HOMEGAP_DRY_RUN` instead, remove it from `.env`. Then run `docker compose up -d` to recreate the container.

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
