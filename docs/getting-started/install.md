# Install on the homelab

This page tells you how to put the HomeGAP stack in a folder on the homelab and start it. You do not edit the compose file of the stack: your settings go in files of your own. The first start is a dry run, so that you can examine the changes before HomeGAP makes them.

## The image

The image is `ghcr.io/drewcarlson/homegap`, for `linux/amd64` and `linux/arm64`. The same image runs the orchestrator (`homegap run`), the mesh agent (`homegap mesh`), the sign-in (`homegap sign-in`) and the agent of each node. It also holds the files of the stack.

## Get the files

Make an empty folder for the stack on the homelab. Then get the files in one of these two ways.

### With the image

Run `homegap init` in the folder:

```bash
docker run --rm -it -v "$PWD:/work" ghcr.io/drewcarlson/homegap init
```

The command writes the files of [The folder](#the-folder), and it makes the data folders. It gives them to the owner of the folder. If the owner is not user 1000 and group 1000, the command adds `HOMEGAP_UID` and `HOMEGAP_GID` to `.env`. The config file `config/config.yml` starts with the settings that most homelabs need, and with dry run on.

### With the release archive

Each [release](https://github.com/DrewCarlson/HomeGAP/releases) has the archive `homegap-<version>.tar.gz`, with the same files.

1. Download the archive of the newest release.
2. Extract the archive. The files are in the folder `homegap`.
3. Go to the folder `homegap`.
4. Copy `.env.example` to `.env`.
5. Make the data folders:
    ```bash
    mkdir -p data/orchestrator data/mesh data/identity data/sign-in data/vpn
    ```
6. Make the folder of the orchestrator private:
    ```bash
    chmod 700 data/orchestrator
    ```
7. If `id -u` or `id -g` does not print `1000`, set `HOMEGAP_UID` and `HOMEGAP_GID` in `.env` to your values.

The orchestrator runs as the owner of the folder, not as root. Docker makes a missing folder as root, so the data folders must exist before the first start.

## The folder

=== "Configuration files"

    These are the files that you work with.

    | Path | Content |
    | --- | --- |
    | `.env` | The secrets and the variables of the stack. Use mode 600, and never commit this file. |
    | `config/config.yml` | The config file, with the settings that most homelabs need. |
    | `compose.override.yml` | Your changes to the stack. This file is optional, and you make it yourself. |
    | `compose.yml` | The compose file of the stack. Do not edit it. |
    | `upgrade.sh` | The upgrade script. See [Upgrade the homelab](../operations/upgrade.md). |
    | `.env.example`, `compose.override.example.yml`, `config/config.example.yml` | Examples of the variables, the usual changes to the stack and the common config keys. |

=== "HomeGAP files"

    HomeGAP writes these folders. You do not edit them.

    | Path | Content |
    | --- | --- |
    | `data/orchestrator/` | The state of the orchestrator, the SSH keys of the nodes, the key that signs the directory of users, and the dashboard token. |
    | `data/mesh/` | The description of the mesh for the mesh agent, and the mesh key of the homelab. |
    | `data/identity/` | The signed directory of users, machines and private services. |
    | `data/sign-in/` | The key and the devices of the sign-in. |
    | `data/vpn/` | The devices that are connected. |
    | `.homegap/` | A copy of each file of the stack, as HomeGAP wrote it. With the copies, HomeGAP finds the files that you changed. |

An upgrade replaces `compose.yml`, `upgrade.sh` and the example files with the files of the new version. It never changes `.env`, `config/config.yml` or `compose.override.yml`. You can run `homegap init` again at any time, for example to get a file back. See [Command line](../reference/cli.md).

Include `data/` and `config/` in the backup of the homelab. The folder `data/` holds every credential, so keep the backup private.

For each variable of `.env`, the image tags, the content of `data/` and the services of the stack, see [Homelab stack](../reference/stack.md).

## Install

1. Get the files. See [Get the files](#get-the-files).
2. Open `config/config.yml` in an editor.
3. In `config/config.yml`, set `domain`, `bunny.zone` and `acme.email`.
4. Read [Certificates](../configuration/certificates.md). HomeGAP publishes CAA records for your domain, and they also apply to the Traefik of your homelab.
5. To add the nodes by hand, replace `nodes: []` with your nodes. To use the wizard of the dashboard, keep `nodes: []`. The wizard writes each node to the config file.
6. Keep `reconcile.dry_run: true` for the first start.
7. Set the secrets in `.env`:
    ```text
    BUNNY_API_KEY=...
    HOMEGAP_DASHBOARD_TOKEN=...
    ```
    For `HOMEGAP_DASHBOARD_TOKEN`, use a long random string.
8. If you set no dashboard token, read the token that HomeGAP creates after the first start. It is in the file `data/orchestrator/dashboard-token`.
9. Make sure that only you can read the file `.env`:
    ```bash
    chmod 600 .env
    ```
10. Make sure that the Docker network of your Traefik exists. If its name is not `traefik`, set `TRAEFIK_NETWORK` in `.env`.
11. Add the front door labels to your homelab Traefik container:
    ```yaml
    labels:
      homegap.expose: "example.net"
      homegap.wildcard: "true"
      homegap.protocol: "https"
      homegap.tls: "passthrough"
    ```
    If the HTTPS entrypoint of Traefik does not listen on 443 inside its container, also add `homegap.port: "<that port>"`.
12. Add the HTTP provider of HomeGAP to the static configuration of your homelab Traefik:
    ```yaml
    providers:
      http:
        endpoint: http://orchestrator:8088/api/traefik/homelab
        pollInterval: 30s
    ```
13. Recreate the Traefik container, so that it gets the labels and the provider.
14. Start the services:
    ```bash
    docker compose up -d
    ```
15. Look at the log of the orchestrator:
    ```bash
    docker compose logs -f orchestrator
    ```
16. Check the config:
    ```bash
    docker compose exec orchestrator homegap validate
    ```
    The command prints `OK: N node(s), domain ..., zone ...`, or the list of errors.

The config loader is strict. A key that HomeGAP does not know stops the start.

### The HTTP provider

HomeGAP uses the HTTP provider to give your Traefik its own routes. One route sends the names of the speed test and the status page to the orchestrator, when you enable them. With a second route, Traefik answers 421 for the names that only the nodes serve. See [Speed tests](../operations/speed-tests.md) and [Traffic paths](../services/traffic-paths.md).

Keep your other providers. Traefik can use the file provider and the HTTP provider at the same time. The address is the dashboard of the orchestrator, on the `traefik` network. The dashboard must listen on that network: `dashboard.bind: 0.0.0.0:8088`, as in the config that `homegap init` writes. Traefik does not need the dashboard token for this address.

## Open the dashboard

!!! danger "Warning"
    Never expose the dashboard publicly. The dashboard has control of every node.

The stack publishes the dashboard only on the loopback address of the homelab, at port 8088. For a first look, use an SSH tunnel from your workstation.

1. On your workstation, open the SSH tunnel:
    ```bash
    ssh -L 8088:127.0.0.1:8088 <homelab>
    ```
2. Open `http://localhost:8088` in your browser. The dashboard asks for the token: your `HOMEGAP_DASHBOARD_TOKEN`, or the token that HomeGAP created.

For the long term, give the dashboard a name at your Traefik, for the devices of the VPN only. See [The dashboard](../operations/dashboard.md).

## Disable dry run

In dry run, the orchestrator does full discovery and writes the desired state to the log. It makes no SSH, API or DNS writes.

1. Open the **Config** page on the dashboard.
2. Examine the diff between the desired state and the actual state.
3. Make sure that each secret in the config shows as present. The page never shows the values.
4. Set `reconcile.dry_run: false` in `config/config.yml`. The orchestrator reads the file again on each pass, so you do not restart it.

If you use `HOMEGAP_DRY_RUN` instead, remove it from `.env`. Then run `docker compose up -d` to recreate the container.

## Add the first node

The stack runs, but no node carries traffic until you add one.

1. Create a server. See [Prepare the servers](servers.md).
2. Add the node with the wizard of the dashboard. See [Add a node](add-node.md).
3. Wait until the **Nodes** page shows the node as healthy.
4. Check the front door through the node. See [Publish a service](first-service.md#check-the-service).

After the stack of the node is up, HomeGAP creates the DNS records of the node. Your domain then resolves to the node, and the node sends each request over the mesh to your homelab Traefik.

## Sign-in and VPN

Without `sign_in.url`, there is no sign-in and no VPN. To let the Pangolin apps connect, set `sign_in.url` and add users. See [Sign-in](../vpn/sign-in.md).

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
