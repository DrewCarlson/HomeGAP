# Files and state

This page lists the files that HomeGAP reads and writes on the homelab and on the nodes.

## The folder of the homelab stack

| Path | Content |
| --- | --- |
| `compose.yml`, `upgrade.sh` | The compose file of the stack and the upgrade script. An upgrade replaces them. |
| `.env.example`, `compose.override.example.yml`, `config/config.example.yml` | The examples. An upgrade replaces them. |
| `.homegap/` | A copy of each file that HomeGAP wrote to the folder. HomeGAP uses it to find the files that you changed. |
| `<file>.new` | The file of the new version, next to a file that you changed and kept. |
| `<file>.previous` | Your file, after you let HomeGAP replace it. |
| `.env` | The secrets and the variables of `compose.yml`, with mode 600. |
| `compose.override.yml` | Your changes to the stack. |
| `config/config.yml` | The config file. |
| `config/config.yml.bak-<epoch ms>` | The backups of the edits from the dashboard, with mode 0600. HomeGAP keeps the newest 10. See [Configuration](../configuration/index.md). |
| `data/` | All that HomeGAP writes. The sections below list its folders. |

HomeGAP never changes `.env`, `compose.override.yml` and `config/config.yml` in an upgrade. See [Install on the homelab](../getting-started/install.md) and [Homelab stack](stack.md).

## The data directory of the orchestrator

The data directory is `data_dir` (default `/data`). With the shipped compose file, this is the folder `data/orchestrator` of the stack. No other container mounts it.

| Path | Content |
| --- | --- |
| `state.json` | The state cache, with mode 0600: the slot and the mesh public key of each node, the last runs, the pauses and the ids of the Bunny zones. |
| `state.json.bak` | A copy of the state cache from the last start. HomeGAP uses it if `state.json` is damaged. |
| `state.json.corrupt` | A copy of a state cache that HomeGAP was not able to read. |
| `discovery.json` | The containers of the last discovery, with their labels. HomeGAP uses it for the grace period of a container that is gone. |
| `activity.json` | The activity log of the dashboard. |
| `dashboard-token` | The dashboard token that HomeGAP creates if the config gives none, with mode 0600. |
| `known_hosts` | The SSH host keys of the nodes. |
| `ssh/` | The SSH keys of the nodes, with mode 0600: the keys from the add-node wizard (`<id>_ed25519`), and the keys that you put there by hand. |
| `ssh-askpass.sh` | A helper that gives the setup password to `ssh`. |
| `authority/authority.key` | The private key that signs the directory of users, with mode 0600. |
| `authority/users.json` | The users, the machines and the blocked devices, with mode 0600. It holds a hash of each password, not the password. |
| `acme/` | The ACME account key (`lego/`), and the certificate of the nodes with its key (`node.crt`, `node.key`, `node-cert.json`). The folder also holds the state of the order and the last 20 orders. See [Certificates](../configuration/certificates.md). |
| `locations.yml` | Your edits to the location catalog. |
| `speedtests.json` | The results of the speed tests. |

!!! danger "Warning"
    If `state.json` is damaged and there is no good copy, the orchestrator does not start. An empty state drops each pause and the slot of each node. Restore the file from a backup. Delete it only if you want an empty state.

Include the full folder in your backup. The folders `authority/` and `ssh/` and the file `state.json` have no other copy.

## The shared folders of the homelab

Each of these folders has a fixed path in the HomeGAP image. The compose file mounts the folder of the stack there.

### `data/mesh` (at `/mesh`)

The orchestrator and the mesh agent share this folder.

| Path | Written by | Content |
| --- | --- | --- |
| `mesh.json` | The orchestrator | The description of the mesh: the interface, the peers, the forwards and the VPN. |
| `status.json` | The mesh agent | The public key of the homelab, the description that the agent applied and the peers that it sees. |
| `host-gateway` | The mesh agent | The address of the homelab host, as the mesh namespace reaches it. |
| `hg-mesh.key`, `hg-mesh.conf` | The mesh agent | The WireGuard key and the config of the mesh interface, with mode 0600. |
| `hg-vpn.key`, `hg-vpn.conf` | The mesh agent | The WireGuard key and the config of the VPN interface, with mode 0600. |

### `data/identity` (at `/identity`)

The orchestrator writes this folder after each change of a user, a machine or a private service. The sign-in reads it.

| Path | Content |
| --- | --- |
| `directory.json` | The directory of users, machines, roles and private services, with the signature of the homelab. |
| `authority.pub` | The public key that checks the signature. |

The sign-in accepts a directory only with a valid signature. The private key stays in `data/orchestrator/authority/`.

### `data/sign-in` (at `/sign-in`)

Only the sign-in container writes this folder. The orchestrator reads the public key.

| Path | Content |
| --- | --- |
| `issuer.key` | The private key of the sign-in, with mode 0600. It signs the sessions of the devices. |
| `issuer.pub` | The public key of the sign-in. |
| `devices.json` | The devices that signed in, with mode 0600. |
| `ended-sessions.json` | The sessions that ended before their expiry. |

### `data/vpn` (at `/vpn`)

The sign-in and the mesh agent share this folder. The orchestrator reads it for the **Access** page.

| Path | Written by | Content |
| --- | --- | --- |
| `peers.json` | The sign-in | The devices that are connected. |
| `devices.json` | The sign-in | The devices, for the nodes. |
| `directory.json` | The sign-in | A copy of the signed directory, for the nodes. |
| `status.json` | The mesh agent | The state of the VPN interface: its key, its port, the peers and the exits of the nodes. |

See [Sign-in and VPN](../vpn/index.md).

## The image

| Path | Content |
| --- | --- |
| `/usr/local/bin/homegap` | The HomeGAP binary. |
| `/usr/local/bin/lego` | Lego, the ACME client that orders the certificate of the nodes. |
| `/usr/share/homegap/map/world.pmtiles` | The map tiles. |
| `/usr/share/homegap/docs` | These docs. |
| `/usr/share/homegap/deploy` | The files of the homelab stack, for `homegap init`. |

The image creates the system user `homegap` (uid 10001), with the home directory `/data`. The Dockerfile has no `USER` line, so a container runs as root unless the compose file sets a user. The shipped compose file runs the orchestrator and the sign-in as the owner of the stack folder.

## The node

The stack is in `node_stack.install_dir` (default `/opt/homegap`):

| Path | Content |
| --- | --- |
| `compose.yml` | The compose file of the node stack: Traefik, the maintenance page, the agent of HomeGAP and, with `node_stack.crowdsec`, CrowdSec. |
| `.env` | The environment of the stack, with mode 0600. With CrowdSec, it holds the key of the bouncer. |
| `config/traefik/traefik_config.yml` | The static config of Traefik. |
| `config/traefik/dynamic/homegap-routes.yml` | The routes that HomeGAP writes. |
| `config/traefik/dynamic/homegap-headers.yml` | The middleware for the HSTS header. |
| `config/traefik/dynamic/homegap-tls.yml` | The file that tells Traefik where the certificate is. |
| `config/traefik/dynamic/homegap-crowdsec.yml` | The bouncer middleware, with mode 0600. Only with `node_stack.crowdsec`. |
| `config/traefik/dynamic/homegap-maintenance.yml` | The routes of the maintenance page. The maintenance service adds the file while the node cannot reach the homelab, and removes it after. |
| `config/traefik/certs/homegap.crt`, `homegap.key` | The certificate that HomeGAP orders, with mode 0600. The folder has mode 0700. |
| `config/traefik/logs/` | The logs of Traefik. |
| `config/maintenance/` | The files of the maintenance page: `server.py`, `index.html`, `routes.yml` and `probe.json`. |
| `config/crowdsec/` | The config and the database of CrowdSec. Only with `node_stack.crowdsec`. |
| `config/homegap-sysctl.conf` | The TCP tuning. HomeGAP installs it as `/etc/sysctl.d/99-homegap.conf`. |
| `agent/agent.json` | What the agent of the node does: the PROXY protocol ports and the exit of the node. The agent acts when the file changes. |
| `agent-state/` | The state of the agent, with mode 0700. It holds the key and the config of the exit (`hg-exit.key`, `hg-exit.conf`) and a copy of the signed directory (`identity/`). It also holds the key of the sign-in of the node (`control/`). |
| `.homegap-rollback` | The saved stack, during an update. It has mode 0700. |

HomeGAP reverts a manual edit of a file that it writes here.

HomeGAP also writes these files on the node:

| Path | Content |
| --- | --- |
| `/etc/wireguard/hg-mesh.conf` | The config of the mesh interface, with mode 0600. |
| `/etc/wireguard/hg-mesh.key` | The mesh key of the node. The node creates it, and the private key never leaves the node. |
| `/etc/wireguard/hg-mesh-forwards.sh` | The rules that forward the raw ports to the homelab. |
| `/etc/systemd/system/docker.service.d/10-homegap-mesh.conf` | Starts Docker after the mesh interface. |
| `/etc/sysctl.d/99-homegap.conf` | The TCP tuning of the host, with `node_stack.tcp_tuning`. |
| `/etc/docker/daemon.json` | The log limit of 30 MB for each container. HomeGAP writes it only when it installs Docker and the file does not exist. |
| `/etc/ssh/sshd_config.d/01-homegap.conf` | The SSH hardening from the add-node wizard. |
| `/etc/sudoers.d/90-homegap-<user>` | The sudo rule of the SSH user, from the add-node wizard. |

When you decommission a node, HomeGAP stops the stack and removes the install folder with its data. It also removes the mesh interface, its config and the forward rules.
