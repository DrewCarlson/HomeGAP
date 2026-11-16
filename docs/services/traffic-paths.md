# Traffic paths

HomeGAP sends traffic to a service on one of several paths. This page tells you how each path works and when to use it.

## From a node to the homelab

Every path uses the mesh between a node and the homelab. Each service has one port on the mesh address of the homelab. The node sends the traffic of the service to that port. There, the `homegap-mesh` container forwards it to the container of the service. See [The mesh](../reference/mesh.md).

The orchestrator writes all routes of the Traefik of a node, in one routes file. Traefik loads the file again after each change, without a restart.

The ports of the web services on the mesh address start at `node_stack.mesh_port_base` (default 40000). Only the nodes can reach them, over the mesh. HomeGAP never publishes these ports.

## Front door, passthrough

This is the default path. The homelab Traefik claims the root domain and `*.<domain>`.

1. On each node, a Traefik TCP router on `:443` reads the TLS SNI. It matches the root and any single label under the domain.
2. The router forwards the encrypted stream to the port of the front door on the mesh address of the homelab.
3. The `homegap-mesh` container forwards the stream to the homelab Traefik.

The node holds no certificate for these names, so it cannot read the traffic. Your certificates stay at home.

The node serves some names itself, so the wildcard passthrough does not include them:

- The management host of each node, `node-<id>.<domain>`.
- The shared names of the status page and the speed test, `status.<domain>` and `speedtest.<domain>`. The node sends their requests on to the homelab under its own names, `status-<node>.<domain>` and `speedtest-<node>.<domain>`.
- Dedicated hostnames that use `terminate`. The name of the sign-in (`sign_in.url`) is one of them.

On port 80, the node sends a permanent redirect to HTTPS for the passthrough names.

## Front door, terminate

With `homegap.tls: terminate` on the front door, the node ends TLS for the root and the wildcard. It then sends each request over the mesh to the homelab Traefik. The request keeps its host.

The nodes hold a certificate for the root name. With `homegap.wildcard`, they also hold a certificate for `*.<domain>`. HomeGAP then refuses passthrough on other hostnames under the domain. See [Certificates](../configuration/certificates.md).

For the hop over the mesh, the node uses the protocol of the service, from `homegap.protocol`. With `https`, the node does not check the certificate of the service.

To compare the two modes, change this one label. Use `terminate` when you need the maintenance page or CrowdSec for the names of the front door.

## Dedicated hostname

A labeled service with its own hostname gets its own route on the nodes. This route has priority over the wildcard. A public service also gets its own geo DNS record set.

An `https` service uses passthrough by default, directly to the service. It has its own SNI route with a higher priority than the front door. The node terminates plain `http` services, and each `https` service with `homegap.tls: terminate`.

HomeGAP orders the certificate of the nodes only for the names that a node terminates.

## Request headers

For a name that it terminates, the node adds these headers to each request:

- `X-Forwarded-For`, with the address of the viewer.
- `X-HomeGAP-Node`, with the id of the node. A viewer cannot set this header.

A passthrough name gets no headers from the node, because the node cannot read the request. For the address of the viewer on a passthrough name, see [Client addresses and private-only services](client-addresses.md).

## Health resource

Each node has a health resource, `hg-health-<node id>`. The Bunny monitor sends its request to the IP address of the node on port 80. The node sends the request over the mesh to the front door. A node can serve no front door of `domain`. The request then goes to the front door of an extra domain, or to the first dedicated `http` or `https` service.

The request has the host header of that service and the path from its `homegap.healthcheck` (default `/`). If the node has no route for the request, it answers 502, and Bunny removes the node from the answers.

## When the homelab is down

A node shows a "back shortly" page with HTTP status 503 for the names that it terminates:

- Each service with `homegap.tls: terminate`, and each plain `http` service. To disable the page for a service, set `homegap.maintenance_page: "false"`.
- The shared names of the status page and the speed test.

The `homegap-maintenance` container of the node checks the homelab over the mesh every 2 seconds. After 3 failed checks in a row, it adds the page. When the homelab answers again, it removes the page.

A passthrough name has no page, because the node holds no certificate for it. The node closes the connection immediately, and the browser shows a connection error. The shared name of the status page tells your users that the homelab is unreachable. Raw ports fail.

## When a browser uses one connection for two names

The names on a node have the same IP address. If one certificate is valid for two of them, a browser can use one connection for both names. For example, Firefox opens `status.example.net` and then sends the requests for `app.example.net` on the same connection. But the node ends TLS for `status.example.net` and uses passthrough for `app.example.net`. Without a correction, the wrong Traefik gets the request and answers 404.

HomeGAP makes each Traefik answer 421 (Misdirected Request) for these requests. The browser then opens a new connection for the name, and the request goes to the correct location.

- On each node, the route `homegap-misdirected` matches the names that the node passes through. HomeGAP writes it in the routes file of the node.
- On the homelab, the route `homegap-misdirected` matches the names that only the nodes serve. These are the management host of each node, the name of the sign-in, and the shared names of the status page and the speed test. Traefik gets this route from the orchestrator through its HTTP provider. See [Install on the homelab](../getting-started/install.md). HomeGAP does not include a name that your homelab Traefik routes itself, when it can read the routes of your Traefik. If it cannot read them, it does not include the shared name of the speed test.

Each of these routes uses its own TLS options, `homegap-elsewhere`. Traefik answers 421 when the TLS options of the route are different from the TLS options of the connection. On a connection for the correct name, no other route matches, and the request goes to its usual route.

A service that ends TLS itself, with no Traefik in front of it, cannot answer 421. If a browser uses the connection of such a service for another name, the service gets the request.

## Comparison

| | Passthrough | Terminate |
| --- | --- | --- |
| What the node sees | Only the SNI. | Plaintext. |
| Where TLS ends | On the homelab Traefik, or on the service. | On the node. |
| Certificates on the node | None for your services. | A certificate for each name. See [Certificates](../configuration/certificates.md). |
| Address of the viewer | With `homegap.proxy_protocol`. | In `X-Forwarded-For`. |
| Maintenance page | Not possible. | Enabled by default. |
| CrowdSec | Not possible. | Possible, with `node_stack.crowdsec`. |
