# Configuration reference

This page lists every key of the config file, with its type and its default. A key with no default is optional, unless the table says that it is necessary.

## Top level

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `project` | string | `home-gap` | The name of the project. |
| `domain` | string | None. Necessary. | The base domain that HomeGAP serves, for example `example.net`. |
| `extra_domains` | list | `[]` | More domains that services can use. See [Extra domains](../services/extra-domains.md). |
| `data_dir` | string | `/data` | The data directory of the orchestrator. |
| `reconcile` | object | See below. | The reconcile loop. |
| `bunny` | object | None. Necessary. | Bunny DNS. |
| `node_stack` | object | See below. | What each node runs. |
| `acme` | object | See below. | The certificate that HomeGAP orders and sends to the nodes. |
| `mesh` | object | See below. | The private WireGuard mesh between the homelab and each node. It is always on. |
| `sign_in` | object | See below. | The sign-in of HomeGAP for the apps. |
| `vpn` | object | See below. | The VPN of the devices that sign in. |
| `agent` | object | See below. | The agent of HomeGAP on each node. |
| `nodes` | list | `[]` | The nodes. |
| `dashboard` | object | See below. | The dashboard. |
| `speedtest` | object | See below. | The speed tests. |
| `status_page` | object | See below. | The public status page. |
| `homelab` | object | See below. | The homelab: its location and SSH to its host. |
| `map` | object | See below. | The map tiles. |

## reconcile

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `resync_interval` | duration | `60s` | The interval of the full resync. The minimum is `1s`. |
| `debounce` | duration | `2s` | The debounce of Docker events. |
| `dry_run` | boolean | `false` | Does discovery and writes the desired state to the log, but makes no changes. |
| `node_check_interval` | duration | `5m` | The interval of the SSH drift check on each node. |
| `dns_check_interval` | duration | `5m` | How often HomeGAP reads the full Bunny zone again when the desired records do not change. A change to the desired records syncs at once. |
| `removal_grace` | duration | `3m` | How long the services of a container stay after the container is gone. See [Services](../services/index.md). |

## extra_domains[]

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `domain` | string | None. Necessary. | A domain for services, for example `example.org`. It cannot be inside `domain` or a different extra domain. |
| `zone` | string | The value of `domain`. | The Bunny DNS zone. It is equal to the domain or a parent of it. It uses the API key of `bunny`. |
| `caa` | boolean | `true` | With `false`, HomeGAP publishes no CAA records for this domain. Use it if the root of the domain has a service that gets its certificate in a different way. It has no effect without `acme.caa`. |

## bunny

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `zone` | string | None. Necessary. | The Bunny DNS zone. It is equal to `domain` or a parent of it. |
| `api_key_env` | string | `BUNNY_API_KEY` | The environment variable with the Bunny API key. |
| `api_key_file` | string | None. | A file with the Bunny API key. |
| `ttl` | integer | `60` | The TTL of the A and AAAA records, in seconds. The CAA records always have a TTL of 3600. |
| `routing` | string | `geolocation` | `geolocation`, `latency` or `none`. |
| `monitor` | string | `http` | The monitor on each record of a record set: `http`, `ping` or `none`. |
| `api_url` | string | `https://api.bunny.net` | The URL of the Bunny API. |

See [DNS routing and health checks](dns.md).

## node_stack

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `traefik_image` | string | `docker.io/traefik:v3.7.16` | The Traefik image. |
| `maintenance_image` | string | `docker.io/library/python:3.13-alpine` | The image that runs the maintenance page of a node. The page needs only the standard library of Python. |
| `crowdsec_image` | string | `docker.io/crowdsecurity/crowdsec:v1.8.2` | The CrowdSec image. |
| `crowdsec_bouncer_version` | string | `v1.4.6` | The version of the CrowdSec bouncer of Traefik. |
| `install_dir` | string | `/opt/homegap` | The directory of the stack on each node. |
| `crowdsec` | boolean | `false` | Enables CrowdSec and its bouncer on the Traefik of each node. |
| `crowdsec_fail_closed` | boolean | `false` | With `true`, a node refuses a request while it cannot reach the AppSec component of CrowdSec. With `false`, the request goes through. |
| `mesh_port_base` | integer | `40000` | The start of the range of ports on the mesh address of the homelab. Each service that a node reaches by name gets one. No node publishes them. |
| `tcp_tuning` | boolean | `true` | Tunes TCP on each node: BBR congestion control, the `fq` qdisc, larger socket buffers and MTU probing. The settings are in `/etc/sysctl.d/99-homegap.conf`. |

## acme

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `email` | string | `admin@<domain>` | The contact address of the ACME account. |
| `directory` | string | The directory of Let's Encrypt. | The address of a different ACME directory. It must start with `https://`. |
| `staging` | boolean | `false` | Uses the staging directory of Let's Encrypt. |
| `caa` | boolean | `true` | Publishes CAA records that let only `caa_issuer` issue certificates, and only with the DNS challenge. |
| `caa_issuer` | string | `letsencrypt.org` | The certificate authority in the CAA records. |

See [Certificates](certificates.md).

## mesh

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `subnet` | string | `10.77.0.0/24` | The IPv4 subnet of the mesh addresses. It must not overlap `100.64.0.0/10`. Use /26 or larger. |
| `port` | integer | `51821` | The UDP port of the mesh on each node. The firewall of the node opens it. |

See [The mesh](../reference/mesh.md).

## sign_in

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `url` | string | None. | The address of the sign-in, for example `https://vpn.example.net`. You put it into the apps. Each node serves it. Without it, there is no sign-in and no VPN. |
| `passwords` | boolean | `true` | Lets a user sign in with a HomeGAP password. With `false`, HomeGAP accepts only the provider of `oidc`. |
| `oidc.issuer` | string | None. Necessary with `oidc`. | The address of the OpenID Connect provider. It must start with `https://`. |
| `oidc.client_id` | string | None. Necessary with `oidc`. | The client id of HomeGAP at the provider. |
| `oidc.client_secret_env` | string | `HOMEGAP_OIDC_CLIENT_SECRET` | The environment variable of the sign-in container that holds the client secret. |
| `oidc.name` | string | `single sign-on` | The name on the button of the sign-in page. |
| `oidc.scopes` | list | `["openid", "email", "profile"]` | The scopes that HomeGAP requests. The list must contain `openid`. |

The `oidc` object is optional. See [Sign-in](../vpn/sign-in.md).

## vpn

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `client_subnet` | string | `100.90.0.0/16` | The subnet of the device addresses in the tunnel. It must be a /16. The first address is the homelab. |
| `utility_subnet` | string | `100.96.128.0/24` | The subnet that the apps use for their DNS proxy and for the addresses of private names. |
| `port` | integer | `51830` | The UDP port of each node that the apps connect to. The node forwards it to the homelab. The next port up is in use inside the tunnel. |
| `exit_port` | integer | `51840` | The UDP port of each node for the devices that use the node as their exit. The next port up is in use inside the tunnel. |
| `endpoint` | string | The name of the sign-in. | The name or address that the devices send their WireGuard packets to, for the homelab. It must go to a node. |
| `exit_nodes.nodes` | boolean | `false` | Offers "Exit via &lt;node&gt;" for each node. |
| `exit_nodes.homelab` | boolean | `false` | Offers "Exit via homelab". |
| `exit_nodes.roles` | list | `[]` | The roles that can use the exits. `Admin` can always use them. |
| `tunnel_dns.resolvers` | list | `["8.8.8.8"]` | The DNS servers that the apps reach through the tunnel, as IP addresses. An empty list disables them. |
| `tunnel_dns.roles` | list | `["Member"]` | The roles that can use these DNS servers. `Admin` can always use them. |

See [Exits and tunnel DNS](../vpn/exit-nodes.md).

## agent

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `image` | string | `ghcr.io/drewcarlson/homegap:<tag>` | The HomeGAP image that a node runs as its agent. The default tag is the value of `HOMEGAP_VERSION`. Without that variable, the tag is the version of the orchestrator. |

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
| `ssh_port` | integer | `22` | The SSH port. If you change it for a node that HomeGAP manages, HomeGAP moves the SSH server of the node to the new port. See [Node updates](../operations/node-updates.md). |
| `ssh_key_file` | string | None. Necessary. | The private SSH key, as a path in the orchestrator container. |
| `location` | object | None. | `{ latitude, longitude, name }`. The `name` shows on the maps. |
| `latency_zone` | string | None. | The Bunny latency zone, for `latency` routing. |
| `management_host` | string | `node-<id>.<domain>` | The name of the node itself, which goes only to this node. HomeGAP checks the certificate of the node there. |
| `firewall` | boolean | `true` | Lets HomeGAP manage `ufw` on the node. |
| `ssh_allow_from` | list | `[]` | The sources (IP addresses or CIDRs) that can reach SSH. An empty list means anywhere. |
| `install_docker` | boolean | `true` | Installs Docker Engine if it is not on the node. |

!!! danger "Warning"
    If you set `ssh_allow_from` and the public IP address of the orchestrator leaves the list, the orchestrator cannot connect to the node. Then correct the rule from the console of the provider.

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
| `allow_config_edits` | boolean | `true` | Lets the dashboard add and remove nodes, and set the location of a node and of the homelab. Each of these changes writes the config file again. |
| `terminal` | boolean | `true` | Lets the dashboard open a shell on a node. |
| `hostname` | string | None. | The first label of the name of the dashboard at your Traefik, `<hostname>.<domain>`. Without it, there is no route. |
| `access` | string | `private` | Who reaches `hostname`: `private` (devices of the VPN only) or `public`. |
| `sso.middleware` | string | None. Necessary with `sso`. | A forward-auth middleware of your Traefik, with its provider, for example `authelia@docker`. A user who signs in there needs no token. It needs `hostname`. |
| `sso.user_header` | string | `Remote-User` | The header with the name of the user. |
| `sso.groups_header` | string | `Remote-Groups` | The header with the groups of the user, with commas between them. |
| `sso.groups` | list | `[]` | The groups with full access. With an empty list, each user that is not read-only has full access. |
| `sso.read_only_groups` | list | `[]` | The groups that can only look. |

The `sso` object is optional. If neither `auth_token_env` nor `auth_token_file` gives a token, HomeGAP creates one in `<data_dir>/dashboard-token`. See [The dashboard](../operations/dashboard.md).

## speedtest

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | Enables the speed test endpoints. |
| `hostname` | string | `speedtest` | The label of the names `<hostname>.<domain>` and `<hostname>-<node>.<domain>`. |
| `bind` | string | `0.0.0.0:8089` | The listener that the homelab proxy reaches. Never publish it directly. |
| `link_ttl` | duration | `2h` | The lifetime of a test link for a device. |
| `phase_seconds` | integer | `8` | The duration of each download and upload phase, from 2 to 30. |
| `link_budget_gb` | integer | `20` | The data that one device link can send and receive in total, in GiB, from 1 to 1000. |
| `streams` | integer | `4` | The number of parallel TCP streams for a test between the homelab and a node, from 1 to 16. |
| `vpn_access` | boolean | `false` | With `true`, a device of the VPN opens the test page with no link from the dashboard. The front door needs `homegap.private_network`. |

See [Speed tests](../operations/speed-tests.md).

## status_page

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | Enables the public status page. |
| `hostname` | string | `status` | The label of the names `<hostname>.<domain>` and `<hostname>-<node>.<domain>`. |
| `title` | string | `Status` | The heading of the page, 60 characters or fewer. |

The listener of `speedtest.bind` serves the status page. See [Status page](../operations/status-page.md).

## homelab

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `location` | object | None. | `{ latitude, longitude, name }`: the location of the homelab, for the maps. The public status page shows only its region. |
| `ssh.enabled` | boolean | `false` | Publishes the SSH server of the homelab host as a private service, for devices of the VPN only. See [Private services](../services/private.md). |
| `ssh.hostname` | string | `ssh` | The first label of the name, `<hostname>.<domain>`. A single lowercase label. |
| `ssh.port` | integer | `22` | The port of the SSH server of the host. |
| `ssh.roles` | list | `[]` | The roles that can reach the host. `Admin` always can. |

## map

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `tiles` | string | `/usr/share/homegap/map/world.pmtiles` | The PMTiles archive of the maps. Without it, the maps show markers on a plain background. |

## Environment variables

| Variable | Default | What it does |
| --- | --- | --- |
| `HOMEGAP_CONFIG` | `/config/config.yml` | The path of the config file. |
| `HOMEGAP_DRY_RUN` | None. | With `1`, `true` or `yes`, the orchestrator runs in dry run. |
| `HOMEGAP_VERSION` | None. | The tag of the default image of the agent on each node (`agent.image`). The shipped compose file also uses it as the tag of the stack. |
| `DOCKER_SOCKET` | `/var/run/docker.sock` | The path of the Docker socket. |
| `DOCKER_HOST` | None. | The address of the Docker socket proxy, as `tcp://host:port`. With it, the orchestrator does not use the socket. The shipped compose file sets it. |
| `HOMEGAP_MESH_DIR` | `/mesh` | The directory that the orchestrator and the mesh agent share. |
| `HOMEGAP_IDENTITY_DIR` | `/identity` | The directory of the signed directory of users. The orchestrator writes it and the sign-in reads it. |
| `HOMEGAP_SIGN_IN_DIR` | `/sign-in` | The directory of the sign-in. The orchestrator reads the public key of the sign-in there. |
| `HOMEGAP_VPN_DIR` | `/vpn` | The directory that the sign-in and the mesh agent share for the devices of the VPN. |
| `HOMEGAP_DEBUG` | None. | If set, prints stack traces on fatal errors. |
| `HOMEGAP_DOCS_DIR` | `/usr/share/homegap/docs` | The directory where the dashboard finds these docs. |
| `HOMEGAP_STACK_FILES` | `/usr/share/homegap/deploy` | The directory where `homegap init` finds the files of the homelab stack. |
| `HOMEGAP_LEGO` | `lego` | The path of the Lego binary, which orders the certificate of the nodes. |

The default of each of the four shared directories is the mount point of the shipped compose file. If that directory does not exist, HomeGAP uses `<data_dir>/mesh`, `<data_dir>/identity`, `<data_dir>/sign-in` or `<data_dir>/vpn`.

The orchestrator also reads the variables that your `*_env` keys name. The sign-in container reads the variable of `sign_in.oidc.client_secret_env`. See [Secrets](secrets.md).
