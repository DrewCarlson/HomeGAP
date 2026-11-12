# Configuration reference

This page lists every key of the config file, with its type and its default. A key with no default is optional, unless the table says that it is necessary.

## Top level

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `project` | string | `home-gap` | The name of the project. |
| `domain` | string | None. Necessary. | The base domain that HomeGAP serves, for example `example.net`. |
| `data_dir` | string | `/data` | The data directory of the orchestrator. |
| `reconcile` | object | See below. | The reconcile loop. |
| `bunny` | object | None. Necessary. | Bunny DNS. |
| `pangolin` | object | See below. | The Pangolin stack on the nodes. |
| `certificates` | object | See below. | The certificate that HomeGAP sends to the nodes. |
| `tunnels` | object | See below. | The Newt tunnels. |
| `cluster` | object | None. | Cluster mode. Without this key, each node runs an independent Pangolin. |
| `nodes` | list | `[]` | The nodes. |
| `dashboard` | object | See below. | The dashboard. |
| `speedtest` | object | See below. | The speed tests. |
| `status_page` | object | See below. | The public status page. |
| `homelab` | object | See below. | The homelab, for the maps. |
| `map` | object | See below. | The map tiles. |

## reconcile

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `resync_interval` | duration | `60s` | The interval of the full resync. The minimum is `1s`. |
| `debounce` | duration | `2s` | The debounce of Docker events. |
| `dry_run` | boolean | `false` | Does discovery and writes the desired state to the log, but makes no changes. |
| `node_check_interval` | duration | `5m` | The interval of the SSH drift check on each node. |
| `removal_grace` | duration | `3m` | How long the services of a container stay after the container is gone. See [When a container is gone](../services/index.md#when-a-container-is-gone). |
| `dns_check_interval` | duration | `5m` | How often HomeGAP reads the full Bunny zone again when the desired records do not change. Changes to the desired records sync at once. |

## bunny

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `zone` | string | None. Necessary. | The Bunny DNS zone. It is equal to `domain` or a parent of it. |
| `api_key_env` | string | `BUNNY_API_KEY` | The environment variable with the Bunny API key. |
| `api_key_file` | string | None. | A file with the Bunny API key. |
| `ttl` | integer | `60` | The TTL of the records, in seconds. |
| `routing` | string | `geolocation` | `geolocation`, `latency` or `none`. |
| `monitor` | string | `http` | The monitor on each geo record: `http`, `ping` or `none`. |
| `api_url` | string | `https://api.bunny.net` | The URL of the Bunny API. |

## pangolin

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `pangolin_image` | string | `docker.io/fosrl/pangolin:ee-1.26.0` | The Pangolin image. In cluster mode, HomeGAP uses the PostgreSQL build of this tag. |
| `gerbil_image` | string | `docker.io/fosrl/gerbil:1.6.0` | The Gerbil image. |
| `traefik_image` | string | `docker.io/traefik:v3.7.16` | The Traefik image. |
| `maintenance_image` | string | `docker.io/library/python:3.13-alpine` | Runs the "back shortly" page on standalone nodes. Clustered nodes use the cluster image. |
| `crowdsec_image` | string | `docker.io/crowdsecurity/crowdsec:latest` | The CrowdSec image. |
| `badger_version` | string | `v1.8.0` | The version of the Badger plugin of Pangolin for Traefik. |
| `crowdsec_bouncer_version` | string | `v1.4.6` | The version of the CrowdSec bouncer. |
| `acme_email` | string | None. | The contact address of the ACME account, if `acme.email` is not set. |
| `org_id` | string | `homegap` | The id of the Pangolin org. |
| `org_name` | string | `HomeGAP` | The name of the Pangolin org. |
| `admin_email` | string | `admin@<domain>` | The email of the admin account that HomeGAP creates. |
| `license_key_env` | string | None. | The environment variable with the EE license key. |
| `license_key_file` | string | None. | A file with the EE license key. |
| `install_dir` | string | `/opt/homegap` | The directory of the stack on each node. |
| `crowdsec` | boolean | `false` | Enables the CrowdSec bouncer on the Traefik of each node. |
| `site_name` | string | `homelab` | The name of the homelab site on each node. |
| `base_blueprint` | string | None. | A Pangolin blueprint (YAML or JSON) that HomeGAP applies before the managed resources. |
| `passthrough_port_base` | integer | `40000` | The start of the internal proxy ports for passthrough resources. HomeGAP never publishes them. |
| `tcp_tuning` | boolean | `true` | Applies TCP tuning on each node. |

For the use of these keys, see [Pangolin options](pangolin.md).

## acme

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `email` | string | `pangolin.acme_email`, then the admin email. | The contact address of the ACME account. |
| `staging` | boolean | `false` | Uses the staging directory of Let's Encrypt. |
| `directory` | string | The directory of Let's Encrypt. | The address of a different ACME directory. It must start with `https://`. |
| `caa` | boolean | `true` | Publishes CAA records that let only `caa_issuer` issue certificates, and only with the DNS challenge. |
| `caa_issuer` | string | `letsencrypt.org` | The certificate authority in the CAA records. |

See [Certificates](certificates.md).

## tunnels

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `state_dir` | string | None. | The directory that the orchestrator and the tunnel supervisor share. |
| `newt_binary` | string | `/usr/local/bin/newt` | The path of the Newt binary. |
| `mtu` | integer | None. | The WireGuard MTU of every Newt connector and every Gerbil, from 1280 to 1500. Without it, the upstream default of 1280 applies. |
| `native` | boolean | `false` | Runs each Newt tunnel on a kernel WireGuard interface. |

## cluster

The `cluster` section is optional. Without it, HomeGAP runs in standalone mode. See [Cluster mode](../cluster/index.md).

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `mesh.subnet` | string | `10.77.0.0/24` | The IPv4 subnet of the mesh. It must not overlap `100.64.0.0/10`. Use /26 or larger. |
| `mesh.port` | integer | `51821` | The UDP port of the mesh on each node. It must not be 51820 or 21820. |
| `image` | string | `ghcr.io/drewcarlson/homegap-cluster:latest` | The image of the cluster members. |
| `homelab_role` | string | `database` | The role of the homelab in the cluster: `database` or `vote`. Set it before the first start of the cluster. See [The homelab as a vote only](../cluster/database.md#the-homelab-as-a-vote-only). |
| `primary.preferred` | string | `homelab` | The location of the database primary. `homelab` is the only accepted value. |
| `primary.switch_back_after` | duration | `10m` | The time that the homelab must stay caught up before it becomes the primary again. HomeGAP does not use it with `homelab_role: vote`. |
| `client_hostname` | string | `pangolin` | The first label of the client address, `<client_hostname>.<domain>`. |
| `exit_nodes.nodes` | boolean | `false` | Offers an exit through each node. |
| `exit_nodes.homelab` | boolean | `false` | Offers an exit through the homelab. |
| `exit_nodes.roles` | list | `[]` | The roles that can use the exits. `Admin` can always use them. |
| `exit_nodes.newt_image` | string | `docker.io/fosrl/newt:1.20.0` | The connector image of the exit on each node. |
| `tunnel_dns.resolvers` | list | `["8.8.8.8"]` | The DNS servers that the Pangolin apps can use through the tunnel, as IP addresses. An empty list disables them. See [DNS through the tunnel](../cluster/pangolin-apps.md#dns-through-the-tunnel). |
| `tunnel_dns.roles` | list | `["Member"]` | The roles that can use these DNS servers. `Admin` can always use them. |

## nodes[]

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `id` | string | None. Necessary. | The node id. It must match `^[a-z][a-z0-9-]{0,30}$`. |
| `region` | string | `""` | A region code: `NA`, `SA`, `EU`, `AF`, `AS`, `OC` or `ME`. |
| `provider` | string | `""` | The VPS provider, for your information. |
| `host` | string | None. Necessary. | The SSH address of the node. |
| `public_ipv4` | string | The value of `host`, if `host` is an IPv4 literal. | The public IPv4 address in DNS. |
| `public_ipv6` | string | None. | The public IPv6 address in DNS. |
| `os` | string | `""` | The OS of the node, for example `ubuntu-24.04`. |
| `ssh_user` | string | `root` | The SSH user. |
| `ssh_port` | integer | `22` | The SSH port. |
| `ssh_key_file` | string | None. Necessary. | The private SSH key, as a path in the orchestrator container. |
| `location` | object | None. | `{ latitude, longitude, name }`. The `name` shows on the maps. |
| `latency_zone` | string | None. | The Bunny latency zone, for `latency` routing. |
| `management_host` | string | `pangolin-<id>.<domain>` | The hostname of the Pangolin API of the node. |
| `dashboard_public` | boolean | `false` | Routes the Pangolin web UI publicly on the management host. Pangolin sign-in for services needs this. |
| `license_key_env` | string | None. | The environment variable with the EE license key of this node. |
| `license_key_file` | string | None. | A file with the EE license key of this node. |
| `firewall` | boolean | `true` | Lets HomeGAP manage `ufw` on the node. |
| `ssh_allow_from` | list | `[]` | The sources (IP addresses or CIDRs) that can reach SSH. An empty list means anywhere. |
| `install_docker` | boolean | `true` | Installs Docker Engine if it is not on the node. HomeGAP then limits the log of each container on the node to 30 MB. |

If a node has a `region` but no `location`, HomeGAP uses a rough center of the region:

| Region | Latitude | Longitude |
| --- | --- | --- |
| `NA` | 39.8 | -98.6 |
| `SA` | -15.6 | -56.1 |
| `EU` | 50.1 | 8.7 |
| `AF` | 1.7 | 17.3 |
| `ME` | 25.3 | 51.5 |
| `AS` | 22.0 | 79.0 |
| `OC` | -25.3 | 133.8 |

## dashboard

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `bind` | string | `127.0.0.1:8088` | The listen address of the dashboard, as `host:port`. |
| `auth_token_env` | string | None. | The environment variable with the bearer token. |
| `auth_token_file` | string | None. | A file with the bearer token. |
| `allow_config_edits` | boolean | `true` | Lets the dashboard add and remove nodes. This writes the config file again. |

Every `/api` route needs the bearer token. If neither key gives a token, HomeGAP creates one in `<data_dir>/dashboard-token`.

## speedtest

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | Enables the speed test endpoints. |
| `hostname` | string | `speedtest` | The label of the names `<hostname>.<domain>` and `<hostname>-<node>.<domain>`. |
| `bind` | string | `0.0.0.0:8089` | The listener that the homelab proxy reaches. Never publish it directly. |
| `link_ttl` | duration | `2h` | The lifetime of the test links for devices. |
| `phase_seconds` | integer | `8` | The duration of each download and upload phase, from 2 to 30. |
| `link_budget_gb` | integer | `20` | The data that one device link can download and upload in total, in GiB, from 1 to 1000. |
| `streams` | integer | `4` | The number of parallel TCP streams for tests between the homelab and a node, from 1 to 16. |

## status_page

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | Enables the public status page. |
| `hostname` | string | `status` | The label of the names `<hostname>.<domain>` and `<hostname>-<node>.<domain>`. |
| `title` | string | `Status` | The heading of the page, 60 characters or fewer. |

## homelab

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `location` | object | None. | `{ latitude, longitude, name }`: the location of the homelab, for the maps. The public status page shows only its region. |

## map

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `tiles` | string | `/usr/share/homegap/map/world.pmtiles` | The PMTiles archive of the maps. Without it, the maps show markers on a plain background. |

## Environment variables

| Variable | Default | What it does |
| --- | --- | --- |
| `HOMEGAP_CONFIG` | `/config/config.yml` | The path of the config file. |
| `HOMEGAP_DRY_RUN` | None. | With `1`, `true` or `yes`, the orchestrator runs in dry run. |
| `DOCKER_SOCKET` | `/var/run/docker.sock` | The path of the Docker socket. |
| `DOCKER_HOST` | None. | The address of the Docker socket proxy, as `tcp://host:port`. With it, the orchestrator does not use the socket. The shipped compose file sets it. |
| `HOMEGAP_TUNNELS_DIR` | `<data_dir>/tunnels` | The directory of the connector specs, for the orchestrator and the tunnel supervisor. |
| `HOMEGAP_MESH_DIR` | `<data_dir>/mesh` | The directory that the orchestrator and the mesh agent share. |
| `HOMEGAP_MESH_HOST` | `homegap-mesh` | The name of the mesh namespace on the network that it shares with the orchestrator. |
| `HOMEGAP_CLUSTER_CONFIG_DIR` | `<data_dir>/cluster/config` | The directory where the orchestrator writes the config of the cluster containers of the homelab. |
| `HOMEGAP_NEWT` | `/usr/local/bin/newt` | The path of the Newt binary, for the tunnel supervisor. |
| `HOMEGAP_DEBUG` | None. | If set, prints stack traces on fatal errors. |
| `HOMEGAP_DOCS_DIR` | `/usr/share/homegap/docs` | The directory where the dashboard finds these docs. |

The orchestrator also reads the variables that your `*_env` keys name. See [Secrets](secrets.md).
