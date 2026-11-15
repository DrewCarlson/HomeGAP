# Homelab stack

This page describes the stack that `homegap init` writes to the homelab: the variables of `.env`, the image tags, your changes to the stack, the data folder and the services. To install the stack, see [Install on the homelab](../getting-started/install.md).

## Variables in `.env`

| Variable | Default | What it sets |
| --- | --- | --- |
| `BUNNY_API_KEY` | None. It is necessary. | The API key of your Bunny account. |
| `PANGOLIN_LICENSE_KEY` | Empty. | The Pangolin EE license. |
| `HOMEGAP_DASHBOARD_TOKEN` | Empty. | The bearer token of the dashboard. If it is empty, HomeGAP creates a token. |
| `TRAEFIK_NETWORK` | `traefik` | The name of the Docker network of your Traefik. |
| `COMPOSE_PROFILES` | Empty. | With `cluster`, Docker Compose also starts the cluster services of the homelab. Cluster mode needs it. |
| `HOMEGAP_DATA_DIR` | `./data` | The folder of all that HomeGAP writes. |
| `HOMEGAP_UID`, `HOMEGAP_GID` | `1000` | The user and the group of the orchestrator: the owner of the stack folder. |
| `HOMEGAP_VERSION` | `latest` | The tag of the HomeGAP images. See [The image tags](#the-image-tags). |
| `HOMEGAP_DASHBOARD_PUBLISH` | `127.0.0.1:8088` | The address and the port of the host that publish the dashboard. |
| `HOMEGAP_DRY_RUN` | Empty. | With `true`, the orchestrator runs in dry run. |

The orchestrator gets each variable of `.env`. Thus a secret that the config refers to, for example with `license_key_env`, needs only a line in `.env`.

## The image tags

Each release has four tags. The tags have no `v` at the start.

| Tag | What an upgrade gets |
| --- | --- |
| `latest` | The newest release. |
| `0` | The newest release of major version 0. |
| `0.1` | The newest patch release of version 0.1. |
| `0.1.0` | Only this release. The upgrade script then changes nothing. |

To stay on one line of releases, set `HOMEGAP_VERSION` in `.env`, for example `HOMEGAP_VERSION=0.1`.

## Changes in `compose.override.yml`

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

## The data folder

| Path | Content |
| --- | --- |
| `data/orchestrator/` | The data of the orchestrator: the state cache, the SSH keys of the nodes (`ssh/`), the ACME account and the dashboard token. Only the orchestrator mounts it. |
| `data/tunnels/` | The connector specs. The orchestrator shares it with the `tunnels` container. |
| `data/mesh/` | The description of the mesh and the mesh key of the homelab. The orchestrator shares it with the mesh agent. |
| `data/cluster/config/` | The config that the orchestrator writes for the cluster containers. |
| `data/cluster/postgres/`, `data/cluster/etcd/`, `data/cluster/valkey/`, `data/cluster/sentinel/` | The cluster member of the homelab. They stay empty without cluster mode. |

To keep the data in a different place, for example on a different disk, set `HOMEGAP_DATA_DIR` in `.env`. For each file in `data/orchestrator/`, see [Files and state](files.md#the-data-directory-of-the-orchestrator).

## The services

In standalone mode, the stack has three containers: `orchestrator`, `docker-proxy` and `tunnels`. The other seven services are the part of the homelab in cluster mode. They have the profile `cluster` in `compose.yml`, and Docker Compose starts them only with `COMPOSE_PROFILES=cluster` in `.env`.

| Service | What it does |
| --- | --- |
| `homegap-mesh` | A busybox container that holds the network namespace of the cluster. The mesh interface (`hg-mesh`) and the cluster containers of the homelab are in it. It has the profile `cluster`. |
| `homegap-mesh-agent` | The same image as the orchestrator, with the command `mesh`. It keeps the mesh interface and the firewall of that namespace. It has the profile `cluster`. |
| `orchestrator` | The HomeGAP image with the command `run`. It runs as the owner of the stack folder, with no capability. |
| `homegap-mesh-route` | The same image with the command `mesh-route`. It keeps the route of the orchestrator to the mesh. It has the profile `cluster`. |
| `docker-proxy` | A filter in front of the Docker socket. The orchestrator reads the container list, the events and the image data through it. |
| `homegap-etcd`, `homegap-db`, `homegap-valkey`, `homegap-sentinel` | The cluster member of the homelab. They have the profile `cluster`. |
| `tunnels` | The same image with the command `tunnels`. Its directory is `/tunnels`. It keeps one Newt connector for each node. |

### `homegap-mesh`

The service holds a network namespace for the cluster only. The orchestrator is not in it. It is on one network, `homegap-cluster`, which it shares with the orchestrator. See [Cluster mesh](../cluster/mesh.md#the-interfaces).

The service uses a fixed image. Do not recreate it when you update HomeGAP. A new container replaces the namespace and drops the mesh.

### `homegap-mesh-agent`

The agent is the only container in the namespace of `homegap-mesh` that can change the network. It has `NET_ADMIN` and `NET_RAW`. It shares the folder `data/mesh` with the orchestrator. It runs as root and the folder belongs to the user of the orchestrator, so it also has `DAC_OVERRIDE`.

### `orchestrator`

The orchestrator is not root. It runs as the user `HOMEGAP_UID:HOMEGAP_GID`, which is `1000:1000` without the two variables. Thus the files that it writes in `config/` and `data/` belong to you. If the owner of the stack folder is a different user, `homegap init` writes the two variables to `.env`. The user must own `config/` and the folders `data/orchestrator`, `data/tunnels`, `data/mesh` and `data/cluster/config`. `homegap init` makes these folders with the correct owner.

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

The orchestrator does not mount the Docker socket. See [Security model](security.md#the-homelab).

### `homegap-mesh-route`

An ordinary user cannot change a route, and the orchestrator needs a route to the mesh subnet. This service sets that route. It is in the network namespace of the orchestrator, as root with only `NET_ADMIN` and `DAC_READ_SEARCH`. It mounts `data/mesh` read-only, to read the subnet. See [Cluster mesh](../cluster/mesh.md#the-interfaces).

### `docker-proxy`

The service mounts the Docker socket and accepts only `GET` requests for containers, images and events. It is only on the `homegap-docker` network, so no other container can reach it.

### The cluster member

The services `homegap-etcd`, `homegap-db`, `homegap-valkey` and `homegap-sentinel` start only with the profile `cluster`. After their start, they wait until the orchestrator writes their config to `data/cluster/config`. While they wait, their log shows "waiting for ... (written by the HomeGAP orchestrator)".

The file limits the log of each service to 30 MB (`x-logging`).

### `tunnels`

The tunnel supervisor is independent of the orchestrator. If the orchestrator stops, the tunnels stay up. It shares only the folder `data/tunnels` with the orchestrator.

The `tunnels` service must share a Docker network with Traefik. It must also share a network with each service that HomeGAP exposes directly, that is raw TCP and UDP services and dedicated subdomains.

The service has `NET_ADMIN` and the device `/dev/net/tun`. The setting `tunnels.native: true` needs them. To attach the service to more networks, use `compose.override.yml`.
