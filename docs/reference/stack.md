# Homelab stack

This page describes the stack that `homegap init` writes to the homelab: the variables of `.env`, the image tags, your changes to the stack, the networks, the data folder and the services. To install the stack, see [Install on the homelab](../getting-started/install.md).

## The files

The stack is one Docker Compose project with the name `homegap`. Three files configure it:

| File | What it holds | Who changes it |
| --- | --- | --- |
| `compose.yml` | The services, the networks and the mounts. | HomeGAP. `upgrade.sh` replaces it with the file of the new version. Do not edit it. |
| `.env` | The secrets and the variables of `compose.yml`. | You. |
| `compose.override.yml` | Each other change to the stack. | You. `upgrade.sh` never changes it. |

## Variables in `.env`

| Variable | Default | What it sets |
| --- | --- | --- |
| `BUNNY_API_KEY` | None. It is necessary. | The API key of your Bunny account. |
| `HOMEGAP_DASHBOARD_TOKEN` | Empty. | The bearer token of the dashboard. If it is empty, HomeGAP creates a token. |
| `TRAEFIK_NETWORK` | `traefik` | The name of the Docker network of your Traefik. |
| `HOMEGAP_DATA_DIR` | `./data` | The folder of all that HomeGAP writes. |
| `HOMEGAP_VERSION` | `latest` | The tag of the HomeGAP image. The agent of each node runs the same version. See [The image tags](#the-image-tags). |
| `HOMEGAP_UID`, `HOMEGAP_GID` | `1000` | The user and the group of the orchestrator and the sign-in: the owner of the stack folder. |
| `HOMEGAP_DASHBOARD_PUBLISH` | `127.0.0.1:8088` | The address and the port of the host that publish the dashboard. |
| `HOMEGAP_DRY_RUN` | Empty. | With `true`, the orchestrator runs in dry run. |
| `HOMEGAP_OIDC_CLIENT_SECRET` | Empty. | The client secret of the OpenID Connect provider of the sign-in (`sign_in.oidc`). |

Give `.env` mode 600, and never commit it. `homegap init` creates the file from `.env.example` with that mode.

The orchestrator gets each variable of `.env`. Thus a secret that the config refers to with an `*_env` key needs only a line in `.env`. The sign-in container gets only `HOMEGAP_OIDC_CLIENT_SECRET`. See [Secrets](../configuration/secrets.md).

## The image tags

The orchestrator, the mesh agent and the sign-in use one image, `ghcr.io/drewcarlson/homegap`. Each release has four tags. The tags have no `v` at the start.

| Tag | What an upgrade gets |
| --- | --- |
| `latest` | The newest release. |
| `0` | The newest release of major version 0. |
| `0.1` | The newest patch release of version 0.1. |
| `0.1.0` | Only this release. |

To stay on one line of releases, set `HOMEGAP_VERSION` in `.env`, for example `HOMEGAP_VERSION=0.1`. To upgrade, see [Upgrade](../operations/upgrade.md).

## Changes in `compose.override.yml`

Docker Compose reads `compose.override.yml` after `compose.yml` and merges the two files. You give no extra argument. The file `compose.override.example.yml` has an example of each usual change. All of them attach the `homegap-mesh` service to a network:

```yaml
services:
  homegap-mesh:
    networks:
      # A fixed address on the Traefik network, for homegap.proxy_protocol on the front door.
      traefik: { ipv4_address: 172.32.0.250 }
      # The private path (homegap.private_network on the front door).
      homegap-private: {}
      # The network of a service that HomeGAP exposes directly.
      other-stack: {}

networks:
  homegap-private:
    ipam: { config: [ { subnet: 172.33.0.0/24 } ] }
  other-stack:
    name: other_default
    external: true
```

Keep only the parts that you need. The nodes reach each service through the network namespace of `homegap-mesh`. Thus `homegap-mesh` must share a Docker network with each service that is not on the Traefik network.

!!! warning "Caution"
    A change to the `homegap-mesh` service recreates its container. The mesh agent restarts with it, and the mesh is down for a few seconds.

## The networks

| Network | Members | Purpose |
| --- | --- | --- |
| `traefik` | `orchestrator`, `homegap-mesh`, `homegap-sign-in` | Your existing Traefik network. It is external. Its name is the value of `TRAEFIK_NETWORK`. |
| `homegap-docker` | `orchestrator`, `docker-proxy` | The path of the orchestrator to the Docker socket proxy. It is internal, and no other container is on it. |
| `homegap-mesh` | `homegap-mesh` | The path of the mesh namespace to the nodes. For a device that uses the homelab as its exit, it is also the path to the internet. |

The `homegap-mesh-agent` service has no network of its own. It is in the network namespace of `homegap-mesh`.

## The data folder

Everything that HomeGAP writes is in folders below `HOMEGAP_DATA_DIR` (default `./data`, next to `compose.yml`). The stack has no named volume. `homegap init` and `upgrade.sh` make the folders, with you as their owner.

| Path | Content | Mounted by |
| --- | --- | --- |
| `data/orchestrator/` | The data of the orchestrator: its state, the SSH keys of the nodes, the key that signs the directory of users, the ACME account and the dashboard token. It has mode 0700. | `orchestrator` only, at `/data`. |
| `data/mesh/` | What the orchestrator and the mesh agent tell each other, and the WireGuard keys of the homelab. | `orchestrator` and `homegap-mesh-agent`, at `/mesh`. |
| `data/identity/` | The signed directory of users, machines and private services. | `orchestrator` at `/identity`. `homegap-sign-in` at `/identity`, read-only. |
| `data/sign-in/` | The key and the devices of the sign-in. | `homegap-sign-in` at `/sign-in`. `orchestrator` at `/sign-in`, read-only. |
| `data/vpn/` | The devices that are connected, between the sign-in and the mesh agent. | `homegap-sign-in` and `homegap-mesh-agent` at `/vpn`. `orchestrator` at `/vpn`, read-only. |

To keep the data in a different place, for example on a different disk, set `HOMEGAP_DATA_DIR` in `.env`. For each file in these folders, see [Files and state](files.md).

The paths `/mesh`, `/identity`, `/sign-in` and `/vpn` are fixed in the HomeGAP image. You set no variable for them. If a shared directory is not a mount, the other container cannot see it. Then the orchestrator writes an error with the path and the name of the folder to its log.

## The services

The stack has five services. The file limits the log of each service to 30 MB.

| Service | Image | What it does |
| --- | --- | --- |
| `homegap-mesh` | `docker.io/library/busybox:1.37` | Holds the network namespace of the mesh. |
| `homegap-mesh-agent` | The HomeGAP image, with the command `mesh`. | Keeps the mesh interface, the forwards to the containers and the home end of the VPN. |
| `orchestrator` | The HomeGAP image, with the command `run`. | Reads the labels, configures the nodes and DNS, and serves the dashboard. |
| `homegap-sign-in` | The HomeGAP image, with the command `sign-in`. | The public sign-in: the page where a user signs in, and the calls of the apps. |
| `docker-proxy` | `docker.io/tecnativa/docker-socket-proxy:v0.5.0` | A filter in front of the Docker socket. |

### `homegap-mesh`

The service does no work itself. It holds a network namespace with two WireGuard interfaces: `hg-mesh`, the mesh to the nodes, and `hg-vpn`, the interface of the VPN devices. Nothing else is in the namespace. The service enables IP forwarding (`net.ipv4.ip_forward`) in the namespace, because it forwards packets between the mesh, the devices and the containers of the services.

Each node is a member of the mesh. Thus HomeGAP treats this namespace as a place that a node can reach. The orchestrator is not in it. The firewall of the mesh agent closes the namespace. Nothing in it can open a connection to the orchestrator, to a different container or to your home network. For a node, the namespace forwards only the ports of the published services, each to its one container. For a device, it forwards only to the services that the roles of its user can use.

The service uses a fixed image, not the HomeGAP image. A new container replaces the namespace and drops the mesh, so the container must not change with a HomeGAP release. See [The mesh](mesh.md).

### `homegap-mesh-agent`

The agent is the only container in the namespace of `homegap-mesh` that can change the network. It drops all capabilities and adds `NET_ADMIN`, `NET_RAW` and `DAC_OVERRIDE`. It runs as root and the folder `data/mesh` belongs to the user of the orchestrator, so it needs `DAC_OVERRIDE`.

It mounts `data/mesh` at `/mesh` and `data/vpn` at `/vpn`. If `homegap-mesh` gets a new container, Docker Compose restarts the agent.

### `orchestrator`

The orchestrator is not root. It runs as the user `HOMEGAP_UID:HOMEGAP_GID`, which is `1000:1000` without the two variables. Thus the files that it writes in `config/` and `data/` belong to you. If the owner of the stack folder is a different user, `homegap init` writes the two variables to `.env`. The orchestrator has no capability, and `no-new-privileges` is on.

The service publishes the dashboard on port 8088 of the loopback address of the host. To change the address, set `HOMEGAP_DASHBOARD_PUBLISH`. To serve the dashboard through your Traefik, set `dashboard.hostname` in the config.

The compose file sets these environment variables:

- `BUNNY_API_KEY`. It is necessary. Docker Compose does not start the stack without it.
- `HOMEGAP_DASHBOARD_TOKEN`. The bearer token of the dashboard. If it is empty, HomeGAP creates a token.
- `DOCKER_HOST`. The address of `docker-proxy`, `tcp://docker-proxy:2375`.
- `HOMEGAP_DRY_RUN`. It is optional.
- Each other variable of `.env`.

The orchestrator mounts these folders:

- `./config` at `/config`. The config file is `config.yml` in it. This mount must be writable, unless you set `dashboard.allow_config_edits: false`. The mount is the folder and not the file, so an editor that replaces the file does not break it.
- `data/orchestrator` at `/data`.
- `data/mesh` at `/mesh`, and `data/identity` at `/identity`.
- `data/sign-in` at `/sign-in` and `data/vpn` at `/vpn`, both read-only.

The orchestrator does not mount the Docker socket. It is on two networks: `traefik` and `homegap-docker`.

The speed test and the status page need no labels on this service. The Traefik of your homelab gets their routes from the orchestrator, at `http://orchestrator:8088/api/traefik/homelab`.

### `homegap-sign-in`

The sign-in is the part of HomeGAP that the internet reaches. For this reason it is a container of its own. It has no SSH key, no DNS key and no access to Docker. It runs as the same user as the orchestrator, with no capability and a read-only root file system. It is only on the `traefik` network.

It mounts these folders:

- `./config` at `/config`, read-only. It reads the `sign_in` and `vpn` parts of the config at its start. After a change of these parts, start the service again.
- `data/identity` at `/identity`, read-only. It reads the signed directory of users there.
- `data/sign-in` at `/sign-in`. It keeps its own key and the devices there.
- `data/vpn` at `/vpn`. It writes the devices that are connected there, for the mesh agent.

Its only variable is `HOMEGAP_OIDC_CLIENT_SECRET`. Each node sends the name of the sign-in (`sign_in.url`) to this container, on port 8090. See [Sign-in](../vpn/sign-in.md).

### `docker-proxy`

The service mounts the Docker socket and accepts only `GET` requests for containers and events. A mount of the socket gives the whole Docker API, and with it control of the host. The orchestrator needs only to read, so the proxy refuses each other request. The service is only on the `homegap-docker` network, so only the orchestrator reaches it.

See [Security model](security.md).
