# The dashboard

The orchestrator serves a dashboard on `dashboard.bind`. This page tells you how to reach it safely and what each page shows.

!!! danger "Warning"
    If the dashboard is public, anybody can control every node. Reach it only through a private Pangolin resource or an SSH tunnel. See [Private services](../services/private.md).

## Access

A bearer token protects the `/api` routes. Two routes have their own check. The terminal WebSocket uses a one-time ticket, which the token gets. The Traefik of the homelab can read `/api/traefik/homelab` from the front door container without the token. Set the token with `dashboard.auth_token_env` or `dashboard.auth_token_file`. If the config names no token, or the token is not available, HomeGAP creates one. It keeps that token in the file `dashboard-token` in its data directory, with mode 0600. To read the token, run this command:

```bash
docker compose exec orchestrator cat /data/dashboard-token
```

The dashboard asks for the token in a sign-in dialog and keeps it in the browser. A change of the token needs a restart of the orchestrator.

To remove the token from the browser, click **Sign out** at the bottom of the menu, or search for "Sign out". Do this on a computer that other people use.

The dashboard updates live. It uses one server-sent event stream (`/api/events`). After the first snapshot, the stream sends only the parts that change. When the browser tab is hidden for 30 seconds, the dashboard closes the stream. It opens the stream again when you return to the tab.

The orchestrator writes to the stream approximately every 25 seconds, and after 30 seconds at most. If the dashboard gets nothing for 60 seconds, it opens a new stream. The orchestrator keeps 32 streams open at most. A dashboard above that number gets an error until a different one closes.

If the dashboard cannot reach the orchestrator for more than 5 seconds, a dialog says so. The dialog shows the error and the time to the next try, and it has **Reconnect now**. It closes by itself when the orchestrator answers again. Until then, the page shows the last data from the orchestrator.

## The layout

The menu has two groups: **Operate** (Overview, Nodes, Services, Access, Speed test) and **Plumbing** (DNS, Tunnels, Activity, Diagnostics, Config). A number next to a page is the count of items that need attention there. On a tablet the menu is a rail of icons. On a phone it is one row of pages under the header. Swipe the row sideways to see all pages. When you scroll the page, the dashboard hides the header, and **Search** moves into the row.

The box at the bottom of the menu shows the last pass, and two controls:

- **Resync** queues a pass now.
- **Pause** stops all changes everywhere. The dashboard asks you to confirm first. HomeGAP then only observes, and every page shows a banner with **Resume**. While changes are paused, the box offers **Run one pass**: one pass that applies changes, after which HomeGAP is paused again. A re-provision or a switch of the primary while paused asks if you want to apply just that change or resume everything.

Each page starts with a headline that states the answer, for example "5 of 6 nodes serve.". A node with a warning still serves, and the headline gives the count of such nodes. Under the headline, a status line shows the most important message. When there are more, **+N more** opens the other messages.

Select a node, a service or another item to open it in the **Inspector**. On a desktop the Inspector is a drawer on the right that does not block the page, so you can select a different row. On a tablet it is a drawer over the page, and on a phone it is a sheet at the bottom. The Inspector holds the actions for the item. When an item has more than three actions, one is a button and the others are in **More**. Esc, the close button or the back button closes it. On a phone you can also drag the sheet down.

Press ⌘K, Ctrl+K or `/` to search everything: nodes, services, DNS records, tunnels, pages, actions such as **Resync now**, and on the orchestrator the log, the config keys and the secrets. The search shows the names of config keys and secrets, never their values.

Actions that are risky ask first. An action that you cannot undo, such as **Decommission**, also asks you to type the name. After you remove a node or a person, a message with **Undo** shows for 30 seconds. A later message does not replace it. **Dismiss** removes it. A removed person stays until then, and **Undo** keeps them. **Undo** on a removed node adds it back with its config.

## The pages

### Overview

The whole system at a glance:

- The gauges: nodes, tunnels, services, DNS, and in cluster mode the mesh links and the replica lag.
- **Needs attention**: what is wrong now, worst first. Select an item to inspect it.
- The network: the traffic path (the homelab, then one row for each node with its tunnel and its names) or the map. The names in a row open the **Services** page. The page remembers your choice. See [Maps and locations](maps.md).
- The Pangolin API probe of each node over the last hour.
- Each service through each node, in milliseconds.
- The system and the recent changes.

<img src="../img/dashboard/overview.png" alt="The Overview page with the gauges, Needs attention and the traffic path">

### Nodes

The table shows the health of each node, its Pangolin API probe over the last hour, its tunnel and its certificate. Under the health, the table names the check that fails, the step of a job that runs (for example "writing the stack (4 of 7)") or the count of differences from the config. **All** and **Problems** filter the table. During an update, a strip above the table shows the part of each node in the rollout.

Select a node to open it in the Inspector. The Inspector shows its checks, with the checks that fail at the top. Then it shows its graphs (the Pangolin API probe, the requests and the egress through its proxy, the mesh round trip and the tunnel), its system facts, its software version, its differences from the config and its last 10 provisioning runs. The actions on a node are **Re-provision**, **Pause** or **Resume**, **Diagnostics**, **Speed test**, **Change location** and **Remove**. **Change location** moves the node to a known city or a point on the map. Geo DNS and the maps use the new place from the next pass. After a removal, the action is **Decommission**. During a held rollout, the node also has **Retry update** and **Skip update**. See [Manage nodes](nodes.md) and [Node updates and rollback](node-updates.md).

**+ Add node** opens the wizard over the page. When a run fails, **Retry** runs each step again and keeps the user that the failed run made. If something blocked the new SSH connections of the orchestrator, the button shows a time, for example "Retry in 2:59". A retry before that time fails for the same reason. See [Add a node](../getting-started/add-node.md). In cluster mode, the page also has the **Database** and **Mesh** panels. See [Cluster mesh](../cluster/mesh.md) and [Cluster database](../cluster/database.md).

<img src="../img/dashboard/nodes.png" alt="The Nodes page with the fleet table and a node in the Inspector">

### Services

Each service, with its response time through each node. Amber is slow (over 1 s). Rose is failing, with the status code. A dash means that the node does not serve it. The orchestrator measures this on each pass with one request through the proxy of each node.

Filter by text, or show only the problems. Select a service to see how each node serves it, how it is published and its source labels. Each service has **Pause** or **Resume**, and **Logs**, which opens the **Diagnostics** page. An `http` or `https` service also has **Open**.

<img src="../img/dashboard/services.png" alt="The Services page with the response time through each node">

### DNS

The records that HomeGAP manages, grouped into record sets. A healthy record set shows as one line. A record set with a problem also shows each member, with its monitor and its state. Filter by text, or show only the problems. A plain record is in sync only when each nameserver of the zone serves it. Geo record sets give a different answer in each location, so HomeGAP does not compare them. The geo steering strip shows which nodes answer now and which are withdrawn, and for how long. The Inspector shows when HomeGAP last changed the record set.

<img src="../img/dashboard/dns.png" alt="The DNS page with the record sets">

The CAA record sets belong to certificate management. They are read-only, and they link to the **Certificates** tab.

With [extra domains](../services/extra-domains.md), the page also has the **Domains** table. It shows each domain with its DNS zone, its front door and the state of the zone. If HomeGAP cannot sync a zone, the **State** column shows the error.

### Certificates

A tab of the **DNS** page. It shows the certificate that the nodes share, which node has it, what a visitor gets on each name through each node, the orders, and who can issue for the domain. See [Certificates](../configuration/certificates.md#the-certificates-page).

### Tunnels

One card for each tunnel: its state, its connector, its restarts and its throughput over the last hour. The Inspector shows the Newt version and the last times the connector stopped, with the reason. **Restart connector** stops Newt and starts it again. Traffic through that node pauses for a few seconds.

<img src="../img/dashboard/tunnels.png" alt="The Tunnels page with one connector for each node and a connector in the Inspector">

### Access

Who can sign in to the Pangolin apps, their invitations and their devices. The address of the Pangolin app is at the end of the page. **Show QR** opens a QR code of the address for phones. Select a person to change their roles, then **Save**. An open invitation has **New link**, which makes a new invite link and stops the old one, and **Revoke**. Select a device to see when it was last seen. This page is available only in cluster mode. See [Users and devices](../cluster/access.md).

### Speed test

Latency and throughput through the real traffic path. **Test all nodes** tests each node in turn on the orchestrator, so the run continues when you close the page. The gauge is white for ping, cyan for download and violet for upload. The bars of a node scale to the next whole Gb/s over its faster direction: a node at 200 Mb/s has a scale of 1 Gb/s, and a node at 1.2 Gb/s has a scale of 2 Gb/s. See [Speed tests](speed-tests.md).

<img src="../img/dashboard/speedtest.png" alt="The Speed test page with the live gauge and the results">

### Activity

The reconcile log, newest first. Passes that change nothing show as one row with their count. Filter by level, domain or text. Each entry belongs to a numbered pass. Select an entry to see its details, and **Show pass #N** to see only that pass. **Load older** gets older entries. The orchestrator keeps the last 10,000 entries in its data directory, so the log continues after a restart.

<img src="../img/dashboard/activity.png" alt="The Activity page with the reconcile log">

### Diagnostics

Container logs and a read-only snapshot of each node. The diagnostics bundle also shows as a report: one verdict for each section. See [Diagnostics and troubleshooting](troubleshooting.md).

<img src="../img/dashboard/diagnostics.png" alt="The Diagnostics page with the logs of a container">

### Config

The page shows:

- The differences between the desired state and the actual state.
- Each referenced secret, as present or missing. The page never shows the values.
- The desired state and the config file. Each opens when you select it, and each has **Copy**.

**Locations** in the head of the page opens the Locations page over it. See [Maps and locations](maps.md).

<img src="../img/dashboard/config.png" alt="The Config page with the diff and the secrets">

## Terminal

The dashboard can open a shell on a node. The **Terminal** action is in the menu of a node on the **Nodes** page and in the Inspector of the node. The terminal opens in a panel at the bottom of the dashboard, and the panel stays open when you go to a different page.

The shell runs on the orchestrator, through the SSH connection that the orchestrator already has to the node. The browser gets no key and no password. You are the management user of the node, in the install directory of the stack.

- Each session has a tab. You can have 2 sessions for one node and 6 sessions in total.
- A session continues when you close or reload the page. When you open the dashboard again, the tab is there and shows the last output. The panel is then a bar at the bottom. Select the tab to open the panel.
- If you open the same session in a second window, that window takes the session.
- **Commands** types a common command for you, for example the list of containers or the log of a container.
- A session ends when the shell exits, or when you close its tab. HomeGAP also ends a session that had no browser for 15 minutes, and each session after 8 hours. A restart of the orchestrator ends all sessions.
- HomeGAP ends the sessions of a node that you remove. If you set `dashboard.terminal: false`, HomeGAP ends all sessions.

The activity log shows when a terminal opens and closes, with the node. It does not record what you type or what the shell prints.

!!! danger "Warning"
    A terminal gives a shell with root access on the node to each person who has the dashboard token. To turn the terminal off, set `dashboard.terminal: false`.

## Node checks

The Nodes page shows these checks:

| Check | What it shows |
| --- | --- |
| `ssh` | If the orchestrator can reach the node over SSH. |
| `docker` | If Docker is on the node. |
| `pangolin`, `gerbil` and `traefik` | One check for each of these containers: `running`, with its Docker health in parentheses. `starting` is a warning, and `unhealthy` is failing. |
| `tls-mgmt` and `tls-root` | The certificate that the node serves for its management host and for the root name, with the days that remain. No certificate is failing. The certificate that HomeGAP renews is a warning when its renewal is late. A different certificate is a warning with fewer than 14 days. |
| `gerbil-udp` | If a process listens on 51820/udp. The dashboard names it **WireGuard port**. |
| `pangolin-api` | The Pangolin API probe: the time of a request through the node to the Integration API of Pangolin. The limit is the fastest probe of the node since the start of the orchestrator, plus 300 ms. A probe over the limit is a warning. |
| `tunnel` | If the homelab site on the node is online, with the Newt version. |
| `mode` | In dry run, it shows that HomeGAP does no remote access. |
| `cluster-proxy` | In cluster mode, where HAProxy sends PostgreSQL and Valkey. See [Cluster database](../cluster/database.md#the-cluster-proxy-check). |
| `ee-license` | The state of the EE license. |

## Banners

The dashboard shows a banner in these cases:

- HomeGAP runs in dry-run mode.
- Changes are paused. The banner has **Resume**.
- A node update fails and holds the rollout. See [Node updates and rollback](node-updates.md).
- Two or more nodes are configured, and none of them answers over SSH. The banner tells you that the homelab lost every node. The log records the start and the end. The nodes continue to serve. After the homelab reaches a node again, a banner tells you how long the loss was. That banner stays for one hour.

## Documentation

The **Documentation** link at the bottom of the menu opens these docs at `/docs/`, for the version that runs.
