# End-to-end tests

The `e2e/` folder has a self-contained end-to-end harness. This page tells you what the harness contains, how to run it and what it checks.

## The harness

The harness has these parts:

- A fake Ubuntu VPS, with sshd and its own Docker daemon.
- A simulated homelab, with Traefik, whoami and socat.
- The orchestrator and the tunnel supervisor.
- A stand-in for the Bunny API.

## Run the harness

1. Build the Linux binary for your Docker host. On an amd64 host, use `linkReleaseExecutableLinuxX64` instead.
    ```bash
    ./gradlew linkReleaseExecutableLinuxArm64
    ```
2. Start the harness. It provisions two fake nodes from nothing, over SSH.
    ```bash
    e2e/run.sh
    ```
3. Run the checks of the data plane:
    ```bash
    e2e/verify.sh
    ```

To test EE features, put the keys, for example `PANGOLIN_LICENSE_KEY`, in an untracked `.env` file at the root of the repository. `e2e/run.sh` exports it.

The dashboard of the harness is at `http://127.0.0.1:18088`.

## What verify.sh checks

`e2e/verify.sh` does these checks on every node:

- Passthrough of the root, with the certificate of the origin.
- The address of the viewer at the homelab, through PROXY protocol (`X-Real-Ip`).
- A private-only name returns 403 to the public.
- A private-only subfolder of the root returns 403 to the public, and the rest of the root returns 200.
- Passthrough of the wildcard.
- The management API, which the node serves itself.
- In cluster mode, the client address: the login page and the API on this node.
- The health endpoint on port 80, which Bunny probes.
- The redirect from HTTP to HTTPS.
- A dedicated passthrough service, with the certificate of the origin.
- A dedicated terminate service, with the certificate that HomeGAP ordered for the nodes.
- Raw TCP on the public port 19000.
- The certificate on the node has no wildcard and no passthrough name. The ACME store of the node is empty.

The script also checks that the zone has the two CAA records.

## The certificate of the nodes

The `pebble` service is a local ACME server in place of Let's Encrypt. It accepts each challenge without a DNS lookup. Thus the harness checks that HomeGAP writes and removes the challenge records, and it does not check that they resolve.

Pebble makes a new root certificate each time its container starts. `run.sh` reads the root and writes `work/certs/trust.pem`, which the viewer and the tunnels trust. After a new root, `run.sh` removes `work/data/acme`, so HomeGAP orders the certificate again.

## Cluster checks

`e2e/config.e2e.yml` has cluster mode on. With the cluster, run `e2e/verify-cluster.sh` after `run.sh` and `verify.sh`. It takes approximately 5 minutes, and most of that time is the switch-back hold.

```bash
e2e/verify-cluster.sh
```

The script has these sections:

1. The database: every member is `streaming`, and the primary is on the homelab.
2. A user that the dashboard creates signs in at `pangolin.<domain>` through every node.
3. A Pangolin client (the `client` service) reaches a private service through the node that it goes to. `CLIENT_NODE_IP` takes the role of DNS. Private-only names arrive from the private network.
4. The homelab is cut off from the mesh. A node becomes the primary and takes writes, and the homelab stops writes. After the repair, HomeGAP switches the primary back.
5. Exit nodes: a client that selects `Exit via <node>` or `Exit via homelab` reaches the internet through that exit. This section needs internet access.

To run only some sections, set `ONLY`, for example `ONLY="3 5"`.

### The homelab as a vote only

To test `cluster.homelab_role: vote`, start a new harness with `E2E_HOMELAB_ROLE=vote`. The role does not change on a cluster that runs, so use a different project name and an empty `e2e/work` folder:

```bash
COMPOSE_PROJECT_NAME=homegap-e2e-vote E2E_HOMELAB_ROLE=vote E2E_NO_ENV=1 e2e/run.sh
COMPOSE_PROJECT_NAME=homegap-e2e-vote e2e/verify-cluster.sh
```

`E2E_NO_ENV=1` makes `run.sh` ignore the `.env` file, so the harness does not activate a license key on the new database. In this mode, section 1 also checks that the database containers of the homelab are idle and that a dump is at home. Section 4 cuts off the homelab, then stops the database of the primary node.

Two harnesses cannot run at the same time, because they use the same addresses and ports. Run `docker compose down` in `e2e/` for the first one before you start the second.

!!! note
    If you recreate a node container of the harness, recreate the `gerbil`, `traefik` and `maintenance` containers in it, and the connector in `/opt/homegap/exit`. The Docker daemon in the fake node gives its networks new subnets, and these containers keep the old ones. A reboot of a real server does not do this.
