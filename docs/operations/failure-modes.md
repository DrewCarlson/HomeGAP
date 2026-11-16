# Failure modes

This page tells you what happens when a part of the system fails, and how to recover. The orchestrator is never in the traffic path. Thus a failure of the orchestrator does not affect live traffic.

## The homelab, a node and the mesh

| Failure | Effect | Recovery |
| --- | --- | --- |
| A node is down. | Bunny removes the node from DNS when its health check fails. Its viewers resolve to a different node. | The orchestrator repairs the node or provisions it again. DNS recovers by itself. |
| The mesh link of one node is down. | That node cannot reach the homelab. Its `mesh` check fails, and the services through it fail. A site that the node terminates shows the "back shortly" page. The health check of Bunny fails for the node, so Bunny removes it from DNS. The other nodes continue to serve. | Repair the network. WireGuard connects again by itself. Make sure that `mesh.port`/udp is open on the node. |
| The homelab is down, or it has no internet connection. | Public services are down, because the homelab is the single origin. See [When the homelab is down](#when-the-homelab-is-down). | Restore the homelab. The mesh links connect again by themselves. |
| The orchestrator is down. | Live traffic does not change. New label changes and config changes wait. The dashboard is not available. | Start the orchestrator again. It reconciles to the desired state. |
| The homelab restarts, and the orchestrator starts before the app containers. | HomeGAP keeps the services of each container that is gone for `reconcile.removal_grace` (default 3 minutes). | The containers start, and nothing changes. |
| The homelab cannot reach a node over SSH, but the nodes serve. | With two or more nodes, and no answer from any of them, the dashboard shows a banner. Provisioning and the node checks wait. | Check the internet connection of the homelab. See [Manage nodes](nodes.md#when-the-homelab-loses-every-node). |

### When the homelab is down

The maintenance service on each node checks every 2 seconds that the homelab answers over the mesh. After three checks with no answer, the node changes what it serves. This takes approximately 15 seconds.

| Traffic | What the node does |
| --- | --- |
| A site that the node terminates | It shows a "back shortly" page with status 503. The label `homegap.maintenance_page: "false"` disables the page for a service. |
| A passthrough site | It closes the connection immediately. The node has no certificate for the name, so it cannot show a page. |
| A raw TCP or UDP port | The connection fails. |
| The status page and the speed test | The node shows the "back shortly" page. |

When the homelab answers two checks in a row, the node removes the page.

The sign-in and the VPN change as follows:

- A node answers the sign-in address itself. The Traefik of each node checks the sign-in of the homelab every 5 seconds, and uses the agent of the node when that check fails.
- A user can sign in at a node with a HomeGAP password. The OpenID Connect provider is not available at a node. With `sign_in.passwords: false`, no user can sign in during the outage. A machine can.
- A device that signs in at a node gets the exits only. "Exit via `<node>`" continues to work. Private services of the homelab and "Exit via homelab" do not work.
- A block of a user or a device does not reach a node until the homelab is up again.
- The homelab does not know a device that a node created. After the outage, the user of that device signs in again.

See [Connect the Pangolin apps](../vpn/pangolin-apps.md).

## The sign-in

| Failure | Effect | Recovery |
| --- | --- | --- |
| The `homegap-sign-in` container is down, and the homelab is up. | The nodes answer the sign-in address themselves, as during an outage of the homelab. A new sign-in gets the exits only. Public services continue to work. | Start the container: `docker compose up -d homegap-sign-in`. |
| The OpenID Connect provider is down. | A sign-in through the provider fails. A sign-in with a HomeGAP password works, unless `sign_in.passwords` is `false`. | Restore the provider. |
| The config has no `sign_in.url`. | There is no sign-in and no VPN. The **Access** page is not available. | Set `sign_in.url`. See [Sign-in](../vpn/sign-in.md). |

## Updates and removal

| Failure | Effect | Recovery |
| --- | --- | --- |
| A node update fails. | HomeGAP rolls that node back to its previous stack. The rollout to the other nodes stops. | Click **Retry update** or **Skip update**, or change the config. See [Node updates and rollback](node-updates.md). |
| The first provisioning of a node fails. | The node gets no DNS records. | HomeGAP tries again after 5 minutes. To try immediately, click **Re-provision**. |
| The teardown of a removed node fails. | The node stays on the **Nodes** page as `teardown failed`. Its stack can still run. | Decommission the node again, or forget it. See [Manage nodes](nodes.md#if-the-teardown-fails). |
| The upgrade of the homelab fails at the orchestrator. | `upgrade.sh` restores the previous image of the orchestrator and stops. | Read the log of the orchestrator. See [Upgrade the homelab](upgrade.md). |

## DNS and certificates

| Failure | Effect | Recovery |
| --- | --- | --- |
| Bunny has an outage. | DNS resolution has problems. | None. Bunny is the single external dependency of the traffic path. |
| A new record is not on every Bunny nameserver. | The **DNS** page shows the record as not in sync. | HomeGAP checks again on every pass. If the record does not arrive, save it again in the Bunny dashboard. |
| The order of the certificate of the nodes fails. | The nodes keep the certificate that they have. A node without one serves a default certificate, so a browser shows a warning for the names that the node terminates. | The **Certificates** tab of the **DNS** page shows the cause. See [Certificates](../configuration/certificates.md). |

## Access to the nodes

| Failure | Effect | Recovery |
| --- | --- | --- |
| A login blocker on a node (fail2ban, sshguard or CrowdSec) bans the orchestrator. | SSH from the orchestrator times out, or the server cuts it before login. Setup or provisioning for that node fails. | Remove the ban from the console of the provider or from a different network. Then try again. |
| The IP address of the orchestrator changes, and the config has `ssh_allow_from`. | Provisioning and the SSH checks fail for those nodes. Live traffic does not change. | Open SSH again from the console of the provider (`ufw allow <port>/tcp`). Then update `ssh_allow_from`. |

## The state of the orchestrator

| Failure | Effect | Recovery |
| --- | --- | --- |
| The file `state.json` in the data directory is gone. | The orchestrator starts from an empty state. It loses the pauses, the update holds and the slot of each node. | Restore the file from a backup of the data directory. |
| The file `state.json` is there, but HomeGAP cannot read it. | HomeGAP uses `state.json.bak`, the copy from its last start. It keeps the damaged file as `state.json.corrupt`. Without a good copy, the orchestrator does not start. Live traffic does not change. | Restore `state.json` from a backup. To start from an empty state, delete the file. |
