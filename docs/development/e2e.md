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
- A dedicated terminate service, with the wildcard certificate of the node.
- Raw TCP on the public port 19000.

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
