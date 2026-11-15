# Upgrade the homelab

Use `upgrade.sh` to upgrade HomeGAP on the homelab. The script keeps access up during the upgrade. It also brings the files of the stack to the new version. This page tells you what the script does and how to use it.

## Run the script

The script is in the folder of the stack, next to `compose.yml`. Run it in that folder, instead of `docker compose pull && docker compose up -d`:

```bash
./upgrade.sh
```

| Option | What it does |
| --- | --- |
| `--tunnels` | Recreates the `tunnels` container, also if its version does not change. |
| `--no-pull` | Uses the images that are already on the host. |
| `--no-refresh` | Does not change the files of the stack. |
| `-h`, `--help` | Shows the help. |

## Run it over SSH

Over SSH, run the script in `screen` or `tmux`. If the connection drops, a script in a plain SSH session stops before it ends. The upgrade can also restart the tunnel connectors, and that ends an SSH connection that goes through HomeGAP. In `screen` or `tmux`, the upgrade continues, and you can connect again to see the result.

The script finds out how you run it:

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

## Check the image signatures

The release build signs the `homegap` image and the `homegap-cluster` image with [cosign](https://docs.sigstore.dev/cosign/system_config/installation/). A signature shows that the build workflow of the HomeGAP repository made the image. The check is optional, and HomeGAP does not do it for you.

To check an image before an upgrade:

1. Install cosign on the homelab.
2. Do the check for the `homegap` image:

    ```bash
    cosign verify ghcr.io/drewcarlson/homegap:latest \
      --certificate-oidc-issuer https://token.actions.githubusercontent.com \
      --certificate-identity-regexp '^https://github\.com/DrewCarlson/HomeGAP/\.github/workflows/build\.yml@refs/tags/v'
    ```

3. Do the same check for `ghcr.io/drewcarlson/homegap-cluster:latest`.

If the image has a signature from a release build, the command shows the signature and exits with 0. If the command fails, do not run the upgrade.

## The files of the stack

After the pull, the script runs `homegap init --refresh` from the new image. This command brings `compose.yml`, `upgrade.sh` and the example files to the new version. It never changes `.env`, `compose.override.yml` or `config/config.yml`.

| The file in the folder | What the script does |
| --- | --- |
| It has no changes from you. | The script writes the file of the new version. |
| It has changes from you, and the script has a terminal. | The script asks you. If you answer `y`, it saves your file as `<name>.previous` and writes the file of the new version. |
| It has changes from you, and you answer `n` or there is no terminal. | Your file stays. The file of the new version goes next to it as `<name>.new`. |

If `upgrade.sh` itself gets a new version, the script starts the new version and continues from there.

Then the script checks the compose files. A new version of `compose.yml` can need a variable that your `.env` does not have. In that case, the script shows the name of the variable and stops before it restarts a container. Set the variable in `.env` and run the script again.

To move your changes out of a file that HomeGAP changes, put them in `compose.override.yml`. See [Homelab stack](../reference/stack.md#changes-in-composeoverrideyml).

The script skips this step if the folder has no `compose.yml`, or if you give arguments for `docker compose`.

## What the script does

1. It pulls the new images. Then it brings the files of the stack to the new version.
2. It recreates the orchestrator. Traffic never goes through the orchestrator, so this does not stop access. The script waits up to 120 seconds for the orchestrator to start. Then it recreates the mesh agent.
3. It moves the database primary and the Valkey primary to a node, and holds them there (`homegap maintenance start --minutes 30`).
4. It recreates the cluster containers of the homelab one at a time. It waits up to 5 minutes for each container to serve again.
5. It recreates the tunnel connectors, but only if their code or Newt is different.
6. It ends the hold (`homegap maintenance end`). The primaries return to the homelab after the switch-back delay of the cluster.

If the script cannot move the primaries, it stops and does not touch the cluster containers. Correct the cause and run the script again.

If the orchestrator or a cluster container does not serve with its new image, the script restores the previous image of that service. Then it stops. The services after it keep their previous image. The script restores the image with a tag: it gives the previous image the image name of the compose file. A later `docker compose pull` gets the new image again.

If the service does not serve with its previous image either, the script stops and shows the last lines of its log.

When the script stops early, it ends the hold. The primaries then return to the homelab when its member is healthy again. If the start of the maintenance fails, HomeGAP removes the hold at once.

## The mesh container

The script never recreates `homegap-mesh`. The mesh and every container in its network namespace restart with it. The script does recreate `homegap-mesh-agent`. The mesh interface stays up without the agent.

## The tunnels container

The `tunnels` container restarts every Newt connector at once. Thus every node loses the homelab for a few seconds.

The script recreates it only when the tunnels version of the new image is different. This version covers the code of the tunnel supervisor, its platform layer, the dependency versions and Newt. The script also recreates it when it does not run, or with `--tunnels`.

Until then, the container continues to run the previous image. Thus Docker also keeps that image.

## Standalone mode

In standalone mode, the stack has no cluster services, because the profile `cluster` is off. The script then skips the cluster steps. It does not pull the cluster image, and it does not start maintenance. It still updates the orchestrator and the tunnels.

With the profile `cluster` and no `cluster:` in the config, the orchestrator answers that the cluster is not enabled. The script then leaves the cluster containers as they are.

## After the upgrade

After the upgrade, the cluster members on the nodes follow the cluster image of the homelab, pinned to its registry digest. HomeGAP updates one node in each pass, and only while every member serves and no maintenance runs. See [Cluster database](../cluster/database.md#updates-of-the-node-members).

The script does not update the node stacks (the Pangolin images). They follow the config. See [Node updates and rollback](node-updates.md).
