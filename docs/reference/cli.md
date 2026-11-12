# Command line

The `homegap` binary has one command for each role. This page lists the commands, their options and their output.

In the container, run a command with `docker compose exec orchestrator homegap <command>`. The entrypoint of the image is `tini -- homegap`, and the default command is `run`.

## run

```text
homegap [run] [--config PATH] [--dry-run] [--once]
```

Runs the orchestrator. This is the default command.

| Option | What it does |
| --- | --- |
| `--config PATH` | The config file. See [Configuration](../configuration/index.md). |
| `--dry-run` | Runs in dry run. `HOMEGAP_DRY_RUN` with `1`, `true` or `yes` does the same. |
| `--once` | Runs one pass, without the dashboard and the public listener, then stops. |

## tunnels

```text
homegap tunnels [--dir PATH] [--newt PATH]
```

Runs the tunnel supervisor. It keeps one Newt connector for each node.

| Option | Default | Environment variable |
| --- | --- | --- |
| `--dir PATH` | `/tunnels` | `HOMEGAP_TUNNELS_DIR` |
| `--newt PATH` | `/usr/local/bin/newt` | `HOMEGAP_NEWT` |

## mesh

```text
homegap mesh [--dir PATH]
```

Runs the mesh agent of the homelab. It keeps the mesh interface and the firewall of the namespace that it runs in. See [Cluster mesh](../cluster/mesh.md#the-interfaces).

| Option | Default | Environment variable |
| --- | --- | --- |
| `--dir PATH` | `/mesh` | `HOMEGAP_MESH_DIR` |

## validate

```text
homegap validate [--config PATH]
```

Checks the config file. If the file is valid, the command prints `OK: <n> node(s), domain <domain>, zone <zone>`. If not, it prints `Invalid configuration:` and the list of errors.

## plan

```text
homegap plan [--config PATH] --labels FILE
```

Prints the desired state for a set of labels, without Docker. The file is a JSON map of container names to their labels. See [Services](../services/index.md#preview-the-result-of-labels).

The output has these lines, in this order:

```text
ERROR <container>: ...
WARN  <container>: ...
node <id> (<management host>)
  public  ...
  private ...
  sni     ...
  port    ...
dns <type> <fqdn> -> <value> [<node>] <purpose> [geo] monitor=<monitor>
```

## render-node

```text
homegap render-node ID [--config PATH]
```

Prints the stack files that HomeGAP writes to a node, with the secrets redacted. The last file is the dynamic routes file.

## maintenance

```text
homegap maintenance start [--minutes N] | end
```

Asks the orchestrator in the same container to move the cluster primaries off the homelab. The orchestrator holds them there for up to N minutes (default 30). `end` ends the hold. The command uses the dashboard API, with the dashboard token if you set one. See [Cluster database](../cluster/database.md#maintenance-of-the-homelab-member).

## version and help

`homegap version` (or `--version`) prints the version of the binary. `homegap help` (or `--help`, `-h`) prints the usage.

## Exit codes and errors

| Case | Result |
| --- | --- |
| An unknown command. | The command prints the usage and exits with code 2. |
| A fatal error. | The command prints `homegap: <message>` on stderr, and `homegap: caused by ...` if there is a cause. It exits with code 1. |

With `HOMEGAP_DEBUG` set, a fatal error also prints the stack trace.

## Environment variables

| Variable | Default |
| --- | --- |
| `HOMEGAP_CONFIG` | `/config/config.yml` |
| `HOMEGAP_DRY_RUN` | None. |
| `DOCKER_SOCKET` | `/var/run/docker.sock` |

For all variables, see the [Configuration reference](../configuration/reference.md#environment-variables).
