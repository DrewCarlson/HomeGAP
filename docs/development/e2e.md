# End-to-end tests

The `e2e/` folder has a self-contained end-to-end harness. This page tells you what the harness contains, how to run it and what it checks.

## The harness

`e2e/compose.yml` has these services, on one private Docker network:

| Service | What it is |
| --- | --- |
| `node-na1`, `node-as1` | Two fake Ubuntu VPS nodes, each with sshd and its own Docker daemon. |
| `node-fr1` | A fresh server for the add-node wizard. It is not in the config. |
| `homelab-traefik` | The front door of the simulated homelab, in passthrough mode with a wildcard and a private path. |
| `whoami`, `git`, `wiki` | Web services: behind the front door, with passthrough of its own and with `terminate`. |
| `other-door`, `shop`, `notes` | The front door, a public service and a private service of the extra domain `other.example.org`. |
| `echo`, `rtc`, `proxy-echo` | Raw services: one TCP port, a port list with a named service, and a TCP port with PROXY protocol. |
| `ssh-jump` | A private raw TCP service. |
| `orchestrator`, `homegap-mesh`, `mesh-agent`, `homegap-sign-in`, `docker-proxy` | The homelab stack, with the same users and capabilities as `deploy/compose.yml`. |
| `bunny-mock` | A stand-in for the Bunny API. |
| `pebble` | A local ACME server in place of Let's Encrypt. |
| `authelia` | A real Authelia, as the OpenID Connect provider of the sign-in. |
| `viewer` | A container with `curl`, in the role of a visitor. |
| `client` | A VPN device: the unmodified `olm` client of the Pangolin apps. It is in the profile `client`, so it does not start by default. |

`e2e/config.e2e.yml` is the config of the harness. It enables the sign-in with Authelia, the exits of the nodes and of the homelab, the status page and the speed test.

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

The dashboard of the harness is at `http://127.0.0.1:18088`. Its token is in `e2e/work/data/dashboard-token`.

Two harnesses cannot run at the same time, because they use the same addresses and ports.

## What run.sh does

`e2e/run.sh` does these steps:

1. It runs `e2e/setup.sh`, which makes the test material in `e2e/work/` if it is missing. This is an SSH key, a test CA with the certificate of the homelab Traefik, and the key and two users of Authelia.
2. It builds the image `homegap:e2e` from the local binary, with `e2e/image.Dockerfile`.
3. It starts `pebble` and reads its root certificate. See [The certificate of the nodes](#the-certificate-of-the-nodes).
4. It starts all services with `docker compose up -d --build`.
5. It runs `e2e/load-images.sh`, which loads `homegap:e2e` into the Docker daemon of each fake node. The agent of a node runs this image, and a node cannot pull a local image from a registry.

## Check the sign-in through Authelia

`e2e/verify-oidc.sh` signs a device in through Authelia as a browser does. Give it a user of the harness Authelia. The last line is `approved` or `refused`.

```bash
e2e/verify-oidc.sh eve
```

The harness maps the groups of Authelia to roles (`sign_in.oidc.group_roles`) and makes users at the first sign-in. `ada` is in a group that gives `Admin`, `eve` in one that gives `Member`, and `mallory` in none, so HomeGAP refuses `mallory`.

## What verify.sh checks

`e2e/verify.sh` does these checks on every node:

- Passthrough of the root, with the certificate of the origin.
- The address of the viewer at the homelab, through PROXY protocol (`X-Real-Ip`).
- A private-only name returns 403 to the public.
- A private-only subfolder of the root returns 403 to the public, and the rest of the root returns 200.
- Passthrough of the wildcard.
- The sign-in through this node, with the certificate that HomeGAP orders for the nodes.
- The status page tells a public visitor that it is no VPN device.
- The shared names of the status page and the speed test, which name this node.
- The health endpoint on port 80, which Bunny probes.
- The redirect from HTTP to HTTPS.
- A dedicated passthrough service, with the certificate of the origin.
- A dedicated terminate service, with the certificate that HomeGAP orders for the nodes.
- The certificate on the node has the terminate name and the names of the extra domain. It has no wildcard of the main domain and no passthrough name. The node has no ACME account.
- Raw TCP on the public port 19000.
- A port list on TCP (7881 and 3478) and on UDP (3478), and a named service on 17000/udp.
- Two UDP callers at the same time on port 7882.
- The dedicated service and the front door of the extra domain.

After the nodes, the script does these checks:

- A private dashboard returns 403 to the public through each node, and 200 to the private network.
- `homelab.ssh` is a private service to the address of the host that the mesh agent found.
- The zone of the extra domain has the root, the wildcard and `shop` on each node, and the two CAA records.
- The main zone has no record of the extra domain, the two CAA records and no challenge record.
- The dashboard through the sign-in of the homelab Traefik gives 200 to the private network and 403 to the public.

Most checks print the result next to the value to expect. Compare them. The script stops with an error for four checks: the names on the certificate, the private dashboard, the address of the homelab host and the records of the zones.

## The certificate of the nodes

The `pebble` service accepts each challenge without a DNS lookup. Thus the harness checks that HomeGAP writes and removes the challenge records. It does not check that they resolve.

Pebble makes a new root certificate each time its container starts. `run.sh` reads the root and writes `work/certs/trust.pem`, which the viewer and the `client` trust. After a new root, `run.sh` removes `work/data/acme` and restarts the orchestrator, so HomeGAP orders the certificate again.

## A VPN device

The `client` service connects to `https://signin.test.example.net` as a machine. `CLIENT_NODE_IP` takes the role of DNS: it sends the device to one node. The default is the address of `node-as1`.

1. Create a machine. The command prints its id and its secret.
    ```bash
    docker compose -f e2e/compose.yml exec orchestrator homegap machine add e2e-client
    ```
2. Start the client with the id and the secret. This example sends the device to `node-na1`.
    ```bash
    OLM_ID=<id> OLM_SECRET=<secret> CLIENT_NODE_IP=172.30.0.10 docker compose -f e2e/compose.yml --profile client up -d client
    ```

The client has its local API on `127.0.0.1:9452` in the container. Use it to read the state of the client and to select an exit.
