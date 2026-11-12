# Files and state

This page lists the files that HomeGAP reads and writes on the homelab and on the nodes.

## The data directory of the orchestrator

The data directory is `data_dir` (default `/data`), on the `homegap-data` volume.

| Path | Content |
| --- | --- |
| `state.json` | The state cache, with mode 0600. |
| `state.json.bak` | A copy of the state cache from the last start. HomeGAP uses it if `state.json` is damaged. |
| `discovery.json` | The containers of the last discovery, with their labels. HomeGAP uses it for the grace period of a container that is gone. |
| `dashboard-token` | The dashboard token that HomeGAP creates if the config gives none, with mode 0600. |
| `known_hosts` | The SSH host keys of the nodes. |
| `ssh/` | The SSH keys that the add-node wizard generates, with mode 0600. |
| `ssh-askpass.sh` | A helper that gives the setup password to `ssh`. |
| `tunnels/` | The connector specs, which the `tunnels` container also reads. With the shipped compose file, this is the `homegap-tunnels` volume at `/tunnels`. |
| `acme/` | The ACME account key, and the certificate of the nodes with its key. See [Certificates](../configuration/certificates.md). |
| `cluster/secrets.json` | The cluster passwords: PostgreSQL, Valkey, Sentinel, etcd and the Patroni API. |
| `cluster/homelab-role` | The role of the homelab that HomeGAP records when it creates the cluster. |
| `cluster/backups/` | With `cluster.homelab_role: vote`, the daily dumps of the cluster database. HomeGAP keeps the newest seven. |
| `cluster/config/` | The configs of the homelab member, which the cluster containers read. With the shipped compose file, this is the `homegap-cluster-config` volume at `/cluster-config`. |
| `cluster/maintenance` | The maintenance hold, in cluster mode. |
| `mesh/` | The description of the mesh for the mesh agent, its status and the mesh key of the homelab. With the shipped compose file, this is the `homegap-mesh` volume at `/mesh`. |
| `locations.yml` | Your edits to the location catalog. |
| `speedtests.json` | The results of the speed tests. |

## The config file

The config file is `/config/config.yml`. Edits from the dashboard leave backups next to it, as `config.yml.bak-<epoch ms>`. See [Configuration](../configuration/index.md#edits-from-the-dashboard).

## The homelab compose folder

| Path | Content |
| --- | --- |
| `.env` | The secrets, with mode 600. |
| `secrets/ssh/` | The private SSH keys of nodes that you add by hand. |
| `backups/` | In cluster mode, the nightly `pg_dump` files. HomeGAP keeps the newest seven. |

## The image

| Path | Content |
| --- | --- |
| `/usr/local/bin/homegap` | The HomeGAP binary. |
| `/usr/local/bin/newt` | The Newt binary. |
| `/usr/share/homegap/map/world.pmtiles` | The map tiles. |
| `/usr/share/homegap/docs` | These docs. |
| `/usr/share/homegap/tunnels-version` | The tunnels version, for `deploy/upgrade.sh`. |

The image creates the system user `homegap` (uid 10001), with the home directory `/data`. The Dockerfile has no `USER` line, so the container runs as root by default.

## The node

The stack is in `install_dir` (default `/opt/homegap`):

| Path | Content |
| --- | --- |
| `compose.yml` | The compose file of the Pangolin stack. |
| `.env` | The environment of the stack, with mode 0600. |
| `config/config.yml` | The Pangolin config, with mode 0600. |
| `config/traefik/traefik_config.yml` | The static config of Traefik. |
| `config/traefik/dynamic/homegap-routes.yml` | The routes that HomeGAP writes. |
| `config/traefik/dynamic/homegap-tls.yml` | The TLS config of Traefik. |
| `config/traefik/certs/homegap.crt`, `homegap.key` | The certificate from `certificates`. |
| `config/maintenance/` | The files of the "back shortly" page. |
| `config/homegap-sysctl.conf` | The TCP tuning. HomeGAP installs it as `/etc/sysctl.d/99-homegap.conf`. |
| `secrets/node.json` | The secrets of the node, with mode 0600. |
| `.homegap-rollback` | The saved stack, during an update. |
| `cluster/` | The compose project of the cluster member, in cluster mode. |
| `exit/` | The exit connector, if exits through nodes are enabled. |

HomeGAP also writes these files on the node:

| Path | Content |
| --- | --- |
| `/etc/sysctl.d/99-homegap.conf` | The TCP tuning of the host. |
| `/etc/ssh/sshd_config.d/01-homegap.conf` | The SSH hardening from the add-node wizard. |
| `/run/homegap-probe` | The readings of the mesh probe. A reboot clears them. |
