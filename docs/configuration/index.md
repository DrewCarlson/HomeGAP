# Configuration

HomeGAP reads its configuration from one YAML file. This page tells you where the file is, when changes apply and how HomeGAP checks the file.

## The file

The orchestrator finds the file in this order:

1. The `--config PATH` option.
2. The `HOMEGAP_CONFIG` environment variable.
3. The default path, `/config/config.yml`.

The repository has a complete example in `config/config.example.yml`. For every key, see the [Configuration reference](reference.md).

The parser is strict. An unknown key is an error.

## A minimal config

```yaml
domain: example.net
bunny:
  zone: example.net
pangolin:
  acme_email: ops@example.net
nodes: []
dashboard:
  bind: 0.0.0.0:8088
```

## When changes apply

The orchestrator reads the file again on each pass. Thus most edits apply without a restart. If an edit is not valid, the orchestrator writes the error to the log, and the last good configuration stays in effect.

These changes need a restart of the orchestrator:

- A change of `speedtest.enabled` or `speedtest.bind`.
- The first activation of the status page (`status_page.enabled`).
- A change of the dashboard token. The orchestrator reads the token one time, at the start.
- A change of `dashboard.bind`.

## Check the file

To check the file, run `homegap validate`. In the container, use this command:

```bash
docker compose exec orchestrator homegap validate
```

To check a different file, add `--config PATH`. If the file is valid, the command prints `OK: <n> node(s), domain <domain>, zone <zone>`. If not, it prints `Invalid configuration:` and the list of errors.

## Edits from the dashboard

With `dashboard.allow_config_edits: true` (the default), the dashboard can add and remove nodes. Each such change writes the config file again. The config mount must be writable for this.

Each change from the dashboard starts from the file on the disk. Thus an edit that you make by hand a moment before stays in the file. If the file has an error, HomeGAP does not write it, and the dashboard shows the error.

Before each write, HomeGAP saves the previous file next to it as `<file>.bak-<epoch ms>`, with mode 0600. It keeps the newest 10 backups. The new file starts with this comment:

```text
# Managed by HomeGAP; edited from the dashboard. Previous version saved alongside as .bak-*
```

!!! warning "Caution"
    The new file contains only the values that are different from the defaults. If you edit nodes on the dashboard, HomeGAP does not keep the comments of your file. Keep a copy of your commented file if you need it.

## Durations

A duration is a number with a unit: `ms`, `s`, `m` or `h`. A number without a unit is in seconds. Examples are `60s`, `5m` and `2h`.

## Validation rules

`homegap validate` and each read of the file check these rules:

- `domain` is a plain hostname, without a leading dot and without `*`.
- `bunny.zone` is equal to `domain`, or a parent of it.
- `bunny.routing` is `geolocation`, `latency` or `none`.
- `bunny.monitor` is `http`, `ping` or `none`.
- `acme.directory` starts with `https://`.
- Each node id matches `^[a-z][a-z0-9-]{0,30}$`, and each node id is unique.
- Each `ssh_allow_from` entry is an IP address or a CIDR.
- Each node has an IPv4 address (`public_ipv4`, or a `host` that is an IPv4 literal), or a `public_ipv6`.
- With `geolocation` routing, each node has a `location` or a known region code.
- With `latency` routing, each node has a `latency_zone`.
- `reconcile.resync_interval`, `reconcile.debounce` and `reconcile.node_check_interval` are valid durations.
- `tunnels.mtu` is from 1280 to 1500.
- `dashboard.bind` is in the form `host:port`.

When the status page is enabled:

- `status_page.hostname` is a single lowercase label that matches `^[a-z][a-z0-9]{0,20}$`.
- `status_page.hostname` is different from `speedtest.hostname`, if the speed test is also enabled.
- `status_page.title` has 60 characters or fewer.
- `speedtest.bind` is in the form `host:port`, and it is different from `dashboard.bind`.

When the speed test is enabled:

- `speedtest.hostname` is a single lowercase label.
- `speedtest.phase_seconds` is from 2 to 30.
- `speedtest.link_budget_gb` is from 1 to 1000.
- `speedtest.streams` is from 1 to 16.
- `speedtest.link_ttl` is a valid duration, and `speedtest.bind` is in the form `host:port`.
- `speedtest.bind` is different from `dashboard.bind`.

In cluster mode:

- `cluster.mesh.subnet` is a valid subnet. It does not overlap `100.64.0.0/10`.
- `cluster.mesh.subnet` has space for 32 nodes (/26 or larger).
- `cluster.mesh.port` is from 1 to 65535, and it is not 51820 or 21820.
- `cluster.primary.preferred` is `homelab`.
- `cluster.primary.switch_back_after` is a valid duration.
- `cluster.image` is not empty.
