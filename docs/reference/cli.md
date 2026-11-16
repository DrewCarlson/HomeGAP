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

## mesh

```text
homegap mesh [--dir PATH]
```

Runs the mesh agent of the homelab, in the `homegap-mesh-agent` container. It keeps the mesh interface, the interface of the VPN devices and the firewall of the namespace that it runs in. See [Mesh](mesh.md).

| Option | Default | Environment variable |
| --- | --- | --- |
| `--dir PATH` | `/mesh` | `HOMEGAP_MESH_DIR` |

The agent reads the connected devices of the VPN from the folder `/vpn`. `HOMEGAP_VPN_DIR` sets a different folder. If the mesh folder is not a mount, the command prints a warning on stderr.

## node-agent

```text
homegap node-agent [--dir PATH] [--state PATH]
```

Runs the agent of a node, on the node. HomeGAP starts it in the `homegap-agent` container of the node stack, so you do not run it yourself.

| Option | Default | What it is |
| --- | --- | --- |
| `--dir PATH` | `/agent` | The folder with the instructions from the orchestrator. |
| `--state PATH` | `/agent-state` | The folder that the agent writes, for example the key of its exit. |

## sign-in

```text
homegap sign-in [--config PATH] [--dir PATH] [--identity PATH] [--port N]
```

Runs the public sign-in of the homelab, in the `homegap-sign-in` container. It reads only the `sign_in` and `vpn` parts of the config. With no config file, it accepts passwords only. See [Sign-in](../vpn/sign-in.md).

| Option | Default | Environment variable |
| --- | --- | --- |
| `--dir PATH` | `/sign-in` | `HOMEGAP_SIGN_IN_DIR` |
| `--identity PATH` | `/identity` | `HOMEGAP_IDENTITY_DIR` |
| `--port N` | `8090` | None. |

With `sign_in.oidc`, the command needs the client secret in the variable that `sign_in.oidc.client_secret_env` names. Without it, the command stops with an error.

## validate

```text
homegap validate [--config PATH]
```

Checks the config file. If the file is valid, the command prints `OK: <n> node(s), domain <domain>, zone <zone>`. If not, it prints `Invalid configuration:` and the list of errors.

## plan

```text
homegap plan [--config PATH] --labels FILE
```

Prints the desired state for a set of labels, without Docker. The file is a JSON map of container names to their labels. See [Services](../services/index.md).

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

Prints the stack files that HomeGAP writes to a node, with the secrets redacted. The last file is the dynamic routes file. The command reads only the config file, so the output has no services from labels.

## user

```text
homegap user list
homegap user add EMAIL [--name NAME] [--role ROLE]...
homegap user password EMAIL
homegap user block EMAIL
homegap user unblock EMAIL
homegap user remove EMAIL
```

Manages the users of the sign-in. Run it in the orchestrator container. The command calls the dashboard API of the orchestrator in the same container, with your dashboard token or the token that HomeGAP created. Thus the orchestrator must run. See [Users, machines and devices](../vpn/access.md).

| Action | What it does |
| --- | --- |
| `list` | Prints one line for each user: the email address, the roles and `active` or `blocked`. |
| `add` | Creates the user and prints a new password. You can repeat `--role`. Without `--role`, the user gets the role `Member`. |
| `password` | Gives the user a new password and prints it. |
| `block` | Blocks the user. A blocked user cannot sign in, and each session of the user ends. |
| `unblock` | Ends the block. |
| `remove` | Removes the user. |

HomeGAP does not keep a password. `add` and `password` print it one time:

```text
password: <password>
HomeGAP does not keep the password. Give it to the user now.
```

## machine

```text
homegap machine list
homegap machine add NAME [--role ROLE]...
homegap machine remove NAME
```

Manages the machines. A machine is a device with no user, for example a server that needs access. The command uses the dashboard API in the same way as `homegap user`.

| Action | What it does |
| --- | --- |
| `list` | Prints one line for each machine: the name, the id and the roles. |
| `add` | Creates the machine and prints its id and its secret. You can repeat `--role`. Without `--role`, the machine gets the role `Member`. |
| `remove` | Removes the machine. |

A machine name has 1 to 60 characters: letters, digits, spaces, `.`, `-` and `_`. HomeGAP does not keep the secret. `add` prints it one time:

```text
id: <id>
secret: <secret>
HomeGAP does not keep the secret. Give it to the machine now.
```

## init

```text
homegap init [--dir PATH] [--yes] [--refresh]
```

Writes the files of the homelab stack to a folder. Run it in a container of its own, with your folder at `/work`:

```bash
docker run --rm -it -v "$PWD:/work" ghcr.io/drewcarlson/homegap init
```

The command writes `compose.yml`, `upgrade.sh` and the example files. If they are missing, it also makes `.env`, `config/config.yml` and the five folders in `data/`. If the owner of the folder is not the user `1000:1000`, it writes `HOMEGAP_UID` and `HOMEGAP_GID` to the new `.env`. It never changes a file of your own. See [Install on the homelab](../getting-started/install.md).

If a file of the stack has changes from you, the command asks before it replaces the file. Each line of the output has a result and a path:

| Result | Meaning |
| --- | --- |
| `created` | The folder has no such file. The command makes it. |
| `updated` | The file has no changes from you. The command writes the content of this version. |
| `unchanged` | The file already has the content of this version. |
| `replaced` | Your answer is yes. Your file is now `<name>.previous`. |
| `kept` | Your answer is no, or there is no terminal. The file of this version is `<name>.new`. |

| Option | What it does |
| --- | --- |
| `--dir PATH` | The folder. The default is `/work`. |
| `--yes` | Replaces each file that has changes from you, with no question. |
| `--refresh` | Changes only the files of the stack. It makes no file of your own. `upgrade.sh` uses it. |

## version and help

`homegap version` (or `--version`) prints the version of the binary. `homegap help` (or `--help`, `-h`) prints the usage.

## Exit codes and errors

| Case | Result |
| --- | --- |
| An unknown command. | The command prints the usage and exits with code 2. |
| A fatal error. | The command prints `homegap: <message>` on stderr, and `homegap: caused by ...` if there is a cause. It exits with code 1. |

With `HOMEGAP_DEBUG` set, a fatal error also prints the stack trace.

## Environment variables

| Variable | Default | Use |
| --- | --- | --- |
| `HOMEGAP_CONFIG` | `/config/config.yml` | The config file. |
| `HOMEGAP_DRY_RUN` | None. | `1`, `true` or `yes` starts `run` in dry run. |
| `DOCKER_SOCKET` | `/var/run/docker.sock` | The Docker socket that the orchestrator reads. |
| `DOCKER_HOST` | None. | The address of a Docker API. The stack sets it to `tcp://docker-proxy:2375`. |
| `HOMEGAP_MESH_DIR` | `/mesh` | The folder that the orchestrator and the mesh agent share. |
| `HOMEGAP_IDENTITY_DIR` | `/identity` | The folder with the signed directory of users. |
| `HOMEGAP_SIGN_IN_DIR` | `/sign-in` | The folder of the sign-in. |
| `HOMEGAP_VPN_DIR` | `/vpn` | The folder that the sign-in and the mesh agent share. |
| `HOMEGAP_STACK_FILES` | `/usr/share/homegap/deploy` | The folder in the image with the stack files, for `init`. |

Each of the four folders has a fixed path in the image. If that path is not a folder, HomeGAP uses a folder of the same name in the data directory.

For all variables, see the [Configuration reference](../configuration/reference.md).
