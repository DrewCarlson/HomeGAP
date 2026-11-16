# Upgrade the homelab

Use `upgrade.sh` to upgrade HomeGAP on the homelab. The script keeps access up during the upgrade. It also brings the files of the stack to the new version. This page tells you what the script does and how to use it.

## Run the script

The script is in the folder of the stack, next to `compose.yml`. Run it in that folder, instead of `docker compose pull && docker compose up -d`:

```bash
./upgrade.sh
```

| Option | What it does |
| --- | --- |
| `--no-pull` | Uses the images that are already on the host. |
| `--no-refresh` | Does not change the files of the stack. |
| `-h`, `--help` | Shows the help. |

Each other argument goes to every `docker compose` call of the script. Such an argument also skips the refresh of the files.

## Run it over SSH

Over SSH, run the script in `screen` or `tmux`. If the connection drops, a script in a plain SSH session stops before it ends. In `screen` or `tmux`, the upgrade continues, and you can connect again to see the result.

The script checks how you run it:

| How you run it | What the script does |
| --- | --- |
| On the homelab itself | It runs. |
| Over SSH, in `screen` or `tmux` | It shows one line and runs. |
| Over SSH, without `screen` or `tmux` | It stops before it changes anything, and shows the command for `screen` and for `tmux`. |

For example:

```bash
screen -S homegap-upgrade ./upgrade.sh
```

To run the script in a plain SSH session anyway, for example from an automation tool, set `HG_ALLOW_SSH=1`:

```bash
HG_ALLOW_SSH=1 ./upgrade.sh
```

## Check the image signature

The release build signs the `homegap` image with [cosign](https://docs.sigstore.dev/cosign/system_config/installation/). A signature shows that the build workflow of the HomeGAP repository made the image. The check is optional, and HomeGAP does not do it for you.

To check the image before an upgrade:

1. Install cosign on the homelab.
2. Run this command:

    ```bash
    cosign verify ghcr.io/drewcarlson/homegap:latest \
      --certificate-oidc-issuer https://token.actions.githubusercontent.com \
      --certificate-identity-regexp '^https://github\.com/DrewCarlson/HomeGAP/\.github/workflows/build\.yml@refs/tags/v'
    ```

If the image has a signature from a release build, the command shows the signature and exits with 0. If the command fails, do not run the upgrade.

## What the script does

1. It pulls the new images of the orchestrator, the mesh agent and the sign-in.
2. It brings the files of the stack to the new version. See [The files of the stack](#the-files-of-the-stack).
3. It makes each data folder that is missing: `orchestrator`, `mesh`, `identity`, `sign-in` and `vpn`.
4. It recreates the orchestrator. Traffic never goes through the orchestrator, so this does not stop access. The script waits up to 120 seconds for the orchestrator to start.
5. It recreates the mesh agent (`homegap-mesh-agent`). The mesh interface stays up without the agent.
6. It recreates the sign-in (`homegap-sign-in`). A connected device keeps its tunnel and connects to the sign-in again.
7. It runs `docker compose up -d --remove-orphans`. This brings the remainder of the stack to the compose file. It also removes each container of a service that the compose file does not have.

If the compose files are not valid, the script stops before it restarts a container.

### If the orchestrator does not start

If the orchestrator does not start with its new image, the script shows the last 20 lines of its log. Then it restores the previous image of the orchestrator and stops. The mesh agent and the sign-in keep their containers.

The script restores the image with a tag: it gives the previous image the image name of the compose file. A later `docker compose pull` gets the new image again.

If the orchestrator does not start with its previous image either, the script stops. Read the log with `docker compose logs orchestrator`.

## The files of the stack

After the pull, the script runs `homegap init --refresh` from the new image. This command brings `compose.yml`, `upgrade.sh` and the example files to the new version. It never changes `.env`, `compose.override.yml` or the `config` folder.

| The file in the folder | What the script does |
| --- | --- |
| It has no changes from you. | The script writes the file of the new version. |
| It has changes from you, and the script has a terminal. | The script asks you. If you answer `y`, it saves your file as `<name>.previous` and writes the file of the new version. |
| It has changes from you, and you answer `n` or there is no terminal. | Your file stays. The file of the new version goes next to it as `<name>.new`. |

If `upgrade.sh` itself gets a new version, the script starts the new version and continues from there.

Then the script checks the compose files. A new version of `compose.yml` can need a variable that your `.env` does not have. In that case, the script shows the message of `docker compose` and stops before it restarts a container. Set the variable in `.env` and run the script again.

To move your changes out of a file that HomeGAP changes, put them in `compose.override.yml`. See [Homelab stack](../reference/stack.md).

The script skips this step if the folder has no `compose.yml`, or if you give arguments for `docker compose`. If the refresh fails, the script shows a warning and continues with the files that are there.

## The mesh container

The mesh container (`homegap-mesh`) holds the network namespace of the mesh. It has a fixed image, so an upgrade does not give it a new one. The last step of the script recreates it only when its part of the compose files changes.

!!! warning "Caution"
    When the mesh container starts again, the mesh agent starts again with it. The mesh is down for a few seconds, and the nodes cannot reach the homelab during that time.

## After the upgrade

The script does not update the stacks of the nodes. The orchestrator does that. The image of the agent of a node comes from `agent.image`, and its default tag is the version of the orchestrator. Thus a new version of the orchestrator starts a rolling update of the nodes. See [Node updates and rollback](node-updates.md).
