# Diagnostics and troubleshooting

This page tells you where to find logs and diagnostics, and what to check for common problems.

## Where to look first

Start with **Needs attention** on the **Overview** page. It lists what is wrong now, with the failures first. Select an item to open the page that has the details.

| Item | Meaning |
| --- | --- |
| "The homelab can't reach any node" | The config has two or more nodes, and none of them answers over SSH. Check the internet connection of the homelab. |
| "`<node>` is failing" or "`<node>` has a warning" | A check of the node fails or has a warning. The item names the first such check. See [The node checks](#the-node-checks). |
| "`<node>`: provisioning failed" | The last provisioning run of the node failed. The item shows the error. |
| "`<node>`: stack update failed" | An update failed. With "rolled back; the rollout is held", the node runs its previous stack. See [Node updates and rollback](node-updates.md). |
| "`<node>` has drifted from its config" | A file or a container of the stack is different from the stack that HomeGAP wrote. HomeGAP repairs it, unless the node is paused. |
| "`<node>` waits for its certificate" | A new node serves its default certificate until the order with its management name completes. |
| "`<node>` answers with the default certificate" | The node does not have the certificate of the nodes. Open the **Certificates** tab. |
| "The certificate order failed", "There is no certificate", "The certificate expires in N days" | See [Certificates](../configuration/certificates.md). |
| "`<name>` failing on `<node>`" or "`<name>` slow on `<node>`" | The request to the service through that node fails, or takes more than 1 second. The item shows the status code and the time. |
| "Label error on `<container>`" | HomeGAP cannot use a label of the container. The item shows the reason. |
| "`<name>`: `<node>` withdrawn" | The health monitor of Bunny for that node is offline, so Bunny does not give the node as an answer. |
| "`<name>` is not in sync" | A DNS record is different from the record that HomeGAP wants. |

## The node checks

The Inspector of a node shows its checks. See [The dashboard](dashboard.md#node-checks) for what each check reads.

| Check | When it fails |
| --- | --- |
| `ssh` | The orchestrator cannot connect. The check shows the error of SSH. See the SSH rows in [Problems and checks](#problems-and-checks). |
| `docker` | Docker does not answer on the node. |
| `traefik`, `homegap-maintenance`, `homegap-agent` | The container does not run, or Docker reports it as `unhealthy`. Read its log on the **Diagnostics** page. Then click **Re-provision**. |
| `tls-mgmt`, `tls-root` | The node serves no certificate for the name ("no certificate served"), or the certificate is past its end date. Open the **Certificates** tab of the **DNS** page. |
| `mesh` | The homelab and the node had no WireGuard handshake in the last 200 seconds ("no handshake with the homelab"). Make sure that the firewall of the provider has `mesh.port`/udp open (default 51821). Make sure that the `homegap-mesh-agent` container runs on the homelab. |

## The Diagnostics page

The **Diagnostics** page shows the logs of the containers of a node. It also runs a read-only diagnostics bundle. Nothing on the node changes, and the output contains no secrets. You can download the logs and the bundle as text.

1. Select the **Node**.
2. Select the **Container**.
3. Set **Since** to `15m`, `1h`, `6h` or `24h`.
4. Click **Show logs**, or click **Run diagnostics**.
5. Optional: click **Download**.

The page also shows the certificate that the node has and what the node serves for its names.

<!-- SCREENSHOT: docs/img/dashboard/diagnostics-bundle.png | The Diagnostics page with a diagnostics bundle, the Report panel and the Download button -->

### Log services

| Service | Container |
| --- | --- |
| `traefik` | Traefik. |
| `homegap-agent` | The agent of HomeGAP on the node. |
| `maintenance` | The maintenance page. |
| `crowdsec` | CrowdSec, with `node_stack.crowdsec`. |

### The diagnostics bundle

The bundle has these sections:

- The system: the kernel, the uptime, the memory and the free disk space.
- The containers of the stack.
- The mesh interface (`hg-mesh`) and the exit interface (`hg-exit`), without their private keys.
- The addresses, the routes and the Docker networks.
- The ports that listen, the firewall, and the NAT and forwarding rules.
- The count of connection tracking entries and the last kernel messages.
- The last 60 lines of the logs of Traefik and of the agent.

The **Report** panel gives one verdict for each section, for example "3 containers up" or "2 error lines of 60".

The API calls are `GET /api/nodes/{id}/logs?service=&since=&tail=` and `GET /api/nodes/{id}/diagnostics`. The default of `since` is `30m`. The default of `tail` is 300 lines, and the maximum is 5000.

## Logs on the homelab

Use these commands in the HomeGAP folder:

```bash
docker compose logs -f orchestrator
docker compose logs -f homegap-mesh-agent
docker compose logs -f homegap-sign-in
```

With `HOMEGAP_DEBUG=1`, the orchestrator prints stack traces for fatal errors.

## Other pages

- The **Activity** page shows the reconcile log. You can filter it by domain (`GET /api/log?domain=`).
- The **Config** page shows the differences between the desired state and the actual state (`GET /api/diff`).

## Problems and checks

| Problem | What to check |
| --- | --- |
| HomeGAP ignores a label. | The label errors on the **Services** page, for example a hostname that two containers claim. The `discovery` warnings on the **Activity** page, for example "unknown label". See [Labels](../services/labels.md). |
| A node stays in a provisioning phase, or its provisioning fails. | The **Activity** log and the `ssh` check. HomeGAP tries a failed first provisioning again after 5 minutes. |
| SSH times out, or the server cuts it before login. | A login blocker (fail2ban, sshguard or CrowdSec) can ban the orchestrator. Remove the ban from the console of the provider. Also compare `ssh_allow_from` with the public IP address of the orchestrator. |
| A name does not resolve to the nearest node. | The monitor state on the **DNS** page. Bunny removes a node that fails its health check. With one node, there is no other node to use. |
| A site shows a "back shortly" page with status 503. | The node cannot reach the homelab over the mesh. Check the `mesh` check of the node and the service on the homelab. See [Failure modes](failure-modes.md). |
| A service is `failing` on one node only. | The `mesh` check of that node. Then the log of Traefik on that node. |
| A browser shows a certificate warning, or a node serves the default certificate. | The **Certificates** tab of the **DNS** page. Read the cause of the last order. See [Certificates](../configuration/certificates.md). |
| The Pangolin app cannot sign in. | `sign_in.url` in the config, and the log of `homegap-sign-in`. See [Sign-in](../vpn/sign-in.md). |
| With the Pangolin app on, only your private services open. | The upstream DNS server in the app. It must be a server of `vpn.tunnel_dns.resolvers`. See [Connect the Pangolin apps](../vpn/pangolin-apps.md). |
| A private name returns 403 on the VPN. | See [Client addresses and private-only services](../services/client-addresses.md). |
| Downloads over long paths are slow. | See [Performance](performance.md). |
| The log shows "the mesh agent has not reported". | The `homegap-mesh-agent` container does not run, or it does not share the `mesh` data folder with the orchestrator. See [Homelab stack](../reference/stack.md). |
| The speed test or the status page gives a 404. | See [Speed tests](speed-tests.md#the-speed-test-or-the-status-page-gives-a-404). |
| The orchestrator stops at the start with a `homegap: ...` error. | The config is not valid. An unknown key also stops the start. Run `homegap validate`. See [Configuration](../configuration/index.md). |

## Undo a first test

To remove a test installation:

1. Set `reconcile.dry_run: true`. This stops all further changes.
2. In the Bunny console, remove the records whose comment starts with `managed-by:homegap`.
3. Restore your previous records.
4. On the homelab, stop the HomeGAP services:
    ```bash
    docker compose down
    ```
5. On each node, stop the stack:
    ```bash
    cd /opt/homegap && sudo docker compose down
    ```
