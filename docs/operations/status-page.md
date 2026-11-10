# Status page

The status page is a public page that shows the state of your nodes. This page tells you what it shows, how it works and how to enable it.

## What the page shows

With `status_page.enabled: true`, anybody can open `https://status.<domain>/`. The page shows:

- The node that the visitor came through. This is the node that DNS selects for the location of the visitor. The page also shows the round trip of the visitor to the homelab through this node.
- The state of every node: online, degraded or offline.
- The round trip of the visitor through each other online node.

| State | Meaning |
| --- | --- |
| Online | The node works. |
| Degraded | The node carries traffic, but a check fails. |
| Offline | The node is down, paused or in removal, or its tunnel to the homelab is down. |

The page never shows addresses or hostnames. While it is open, it refreshes every minute.

The page also has a map of the nodes in their status colors. The node of the visitor is highlighted, with the round trip through each node. The map shows the homelab only as a soft area at the center of its region, for example "Home · North America". HomeGAP never sends the location of the homelab to the page.

## How the page works

Each node answers `status.<domain>` itself, with its own certificate. This name never goes through passthrough. On HTTP, the node redirects to HTTPS on the same name.

On HTTPS, the node sends each request on to the homelab under its own name, `status-<node>.<domain>`. That name goes through the front door and the tunnel to the homelab. The address bar keeps `status.<domain>`, and the orchestrator always knows which node the visitor came through. Thus the page, the node list and every ping come from the orchestrator.

For the round trip through each other node, the browser sends one request to `status-<node>.<domain>`. The request goes through that node and its tunnel to the homelab, and back. The browser measures the time with Resource Timing, from the request to the first byte. Thus the time does not include DNS and the setup of the connection.

## Enable the status page

The status page needs a front door. The first activation needs a restart of the orchestrator.

| Key | Default | Rules |
| --- | --- | --- |
| `status_page.enabled` | `false` | |
| `status_page.hostname` | `status` | A single lowercase label. |
| `status_page.title` | `Status` | 60 characters or fewer. |

### Routing

The status page uses the listener of the speed test, `speedtest.bind`. Thus route `status-<node>.<domain>` to port 8089 too. With both features on, one rule covers both. Put the labels on the `homegap-mesh` service:

```yaml
labels:
  traefik.enable: "true"
  traefik.http.routers.homegap-speedtest.rule: "Host(`speedtest.example.net`) || HostRegexp(`^(speedtest|status)-[a-z0-9-]+\\.example\\.net$`)"
  traefik.http.routers.homegap-speedtest.entrypoints: websecure
  traefik.http.routers.homegap-speedtest.tls: "true"
  traefik.http.services.homegap-speedtest.loadbalancer.server.port: "8089"
```

To put the homelab on the map, set its location on the **Locations** page. See [Maps and locations](maps.md).

<!-- SCREENSHOT: docs/img/status-page/01-page.png | the public status page with the node list and the map -->
