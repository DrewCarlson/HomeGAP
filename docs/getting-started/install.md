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

The command writes the files of [The folder](#the-folder). It gives them to the owner of the folder. The config file `config/config.yml` starts with the settings that most homelabs need, and with dry run on.

### With the release archive

Each [release](https://github.com/DrewCarlson/HomeGAP/releases) has the archive `homegap-<version>.tar.gz`, with the same files.

1. Download the archive of the newest release.
2. Extract the archive. The files are in the folder `homegap`.
3. Copy `.env.example` to `.env`.

The archive has the same `config/config.yml` as the command writes.

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
    | `data/` | The state of HomeGAP, the SSH keys of the nodes and the cluster database. |
    | `backups/` | In cluster mode, the nightly dumps of the cluster database. |
    | `.homegap/` | A copy of each file of the stack, as HomeGAP wrote it. With the copies, HomeGAP finds the files that you changed. |

An upgrade replaces `compose.yml`, `upgrade.sh` and the example files with the files of the new version. It never changes `.env`, `config/config.yml` or `compose.override.yml`. You can run `homegap init` again at any time, for example to get a file back. See [Command line](../reference/cli.md#init).

Include `data/` and `config/` in the backup of the homelab. The folder `data/` holds every credential, so keep the backup private.

For each variable of `.env`, the image tags, the content of `data/` and the services of the stack, see [Homelab stack](../reference/stack.md).

## Install

1. Get the files. See [Get the files](#get-the-files).
2. Open `config/config.yml` in an editor.
3. In `config/config.yml`, set `domain`, `bunny.zone` and `pangolin.acme_email`.
4. Read [Certificates](../configuration/certificates.md). HomeGAP publishes CAA records for your domain, and they also apply to the Traefik of your homelab.
5. To add the nodes by hand, replace `nodes: []` with your nodes. To use the wizard of the dashboard, keep `nodes: []`. The wizard writes each node to the config file.
6. For cluster mode, remove the `#` from the line `cluster: {}`, and add `COMPOSE_PROFILES=cluster` to `.env`. See [Cluster mode](../cluster/index.md#enable-cluster-mode).
7. Keep `reconcile.dry_run: true` for the first start.
8. Set the secrets in `.env`:
    ```text
    BUNNY_API_KEY=...
    PANGOLIN_LICENSE_KEY=...
    HOMEGAP_DASHBOARD_TOKEN=...
    ```
    `PANGOLIN_LICENSE_KEY` is optional. To use it, also remove the `#` from the line `license_key_env` in the config. For `HOMEGAP_DASHBOARD_TOKEN`, use a long random string.
9. If you set no dashboard token, read the token that HomeGAP creates after the first start. See [Dashboard](../operations/dashboard.md#access).
10. Make sure that only you can read the file `.env`:
    ```bash
    chmod 600 .env
    ```
11. Make sure that the Docker network of your Traefik exists. If its name is not `traefik`, set `TRAEFIK_NETWORK` in `.env`.
12. Add the front door labels to your homelab Traefik container:
    ```yaml
    labels:
      homegap.expose: "example.net"
      homegap.wildcard: "true"
      homegap.protocol: "https"
      homegap.tls: "passthrough"
    ```
    If the HTTPS entrypoint of Traefik does not listen on 443 inside its container, also add `homegap.port: "<that port>"`.
13. Add the HTTP provider of HomeGAP to the static configuration of your homelab Traefik. HomeGAP uses it to give Traefik two routes. The first route is for the speed test and the status page, when you enable them. It sends their names to the orchestrator. See [Speed tests](../operations/speed-tests.md#routing). With the second route, Traefik answers 421 for the names that only the nodes serve. See [When a browser uses one connection for two names](../services/traffic-paths.md#when-a-browser-uses-one-connection-for-two-names).
    ```yaml
    providers:
      http:
        endpoint: http://orchestrator:8088/api/traefik/homelab
        pollInterval: 30s
    ```
    Keep your other providers. Traefik can use the file provider and the HTTP provider at the same time. The address is the dashboard of the orchestrator, on the `traefik` network. The dashboard must listen on that network: `dashboard.bind: 0.0.0.0:8088`, as in the config that `homegap init` writes. Traefik does not need the dashboard token for this address. The orchestrator accepts a request from the front door container that Traefik did not forward for another client.
14. Recreate the Traefik container, so that it gets the labels and the provider.
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
