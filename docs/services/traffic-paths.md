# Traffic paths

HomeGAP sends traffic to a service on one of several paths. This page tells you how each path works and when to use it.

## Front door, passthrough

This is the default path. The homelab Traefik claims the root domain and `*.<domain>`.

1. On each node, a Traefik TCP router on `:443` reads the TLS SNI. It matches the root and any single label under the domain.
2. The router forwards the encrypted stream into the Pangolin TCP resource `hg-frontdoor`.
3. The stream goes through the tunnel to the homelab Traefik.

The node holds no certificate for these names, so it cannot read the traffic. Your certificates stay at home.

The node serves some names itself, so the wildcard passthrough does not include them:

- The management host of each node, `pangolin-<id>.<domain>`.
- In cluster mode, the client address `pangolin.<domain>`.
- The shared names of the status page and the speed test, `status.<domain>` and `speedtest.<domain>`. The node sends their requests on to the homelab under its own names, `status-<node>.<domain>` and `speedtest-<node>.<domain>`.
- Dedicated hostnames that use `terminate`.

On port 80, the node sends a permanent redirect to HTTPS for the passthrough names.

Each passthrough resource has an internal proxy port on the node. The ports start at `pangolin.passthrough_port_base` (default 40000). HomeGAP never publishes these ports.

## Front door, terminate

With `homegap.tls: terminate` on the front door, HomeGAP uses Pangolin HTTP resources for the root and the wildcard. The node ends TLS and sends the request again to the homelab Traefik. The request keeps its host.

The nodes hold a certificate for the root name. With `homegap.wildcard`, they also hold a certificate for `*.<domain>`. HomeGAP then refuses passthrough on other hostnames under the domain. See [Certificates](../configuration/certificates.md#the-front-door-in-terminate-mode-with-a-wildcard).

Wildcard HTTP resources are a Pangolin EE feature, so every node needs a license. See [Pangolin EE licenses](../configuration/licenses.md). If a node refuses the wildcard, HomeGAP removes that node from the wildcard DNS set. Thus viewers never go to a node that answers 404.

To compare the two modes, change this one label. Use `terminate` when you need Pangolin sign-in or CrowdSec.

## Dedicated hostname

A labeled service with its own hostname gets its own resource. This resource has priority over the wildcard. A public service also gets its own geo DNS record set.

An `https` service uses passthrough by default, directly to the service. It has its own SNI route with a higher priority than the front door. The node terminates plain `http` services.

## Health resource

Each node gets a health resource, `hg-health`. In cluster mode, its name is `hg-health-<node id>`. Its hostname is `homegap-health-<node id>.<domain>`.

The health resource goes to the front door. If there is no front door, it goes to the first dedicated `http` or `https` service. The request has the host header of that service and the path from its `homegap.healthcheck` (default `/`). The Bunny monitor on port 80 reaches this resource.

## When the homelab is down

A node shows a "back shortly" page with HTTP status 503 for the names that it terminates:

- Each service with `homegap.tls: terminate`. To disable the page for a service, set `homegap.maintenance_page: "false"`.
- The shared names of the status page and the speed test.

The page starts approximately 15 seconds after the node loses the homelab.

A passthrough name has no page, because the node holds no certificate for it. The node closes the connection immediately, and the browser shows a connection error. The shared name of the status page tells your users that the homelab is unreachable. Raw ports fail.

## When a browser uses one connection for two names

The names on a node have the same IP address. If one certificate is valid for two of them, a browser can use one connection for both names. For example, Firefox opens `pangolin.example.net` and then sends the requests for `app.example.net` on the same connection. But the node ends TLS for `pangolin.example.net` and uses passthrough for `app.example.net`. Without a correction, the wrong Traefik gets the request and answers 404.

HomeGAP makes each Traefik answer 421 (Misdirected Request) for these requests. The browser then opens a new connection for the name, and the request goes to the correct location.

- On each node, the route `homegap-misdirected` matches the names that the node passes through. HomeGAP writes it in the routes file of the node.
- On the homelab, the route `homegap-misdirected` matches the names that only the nodes serve: the management host of each node, the client address, and the shared names of the status page and the speed test. Traefik gets this route from the orchestrator through its HTTP provider. See step 14 of [Install on the homelab](../getting-started/install.md). HomeGAP does not include a name that your homelab Traefik routes itself, when it can read the routes of your Traefik. If it cannot read them, it does not include the shared name of the speed test.

Each of these routes uses its own TLS options, `homegap-elsewhere`. Traefik answers 421 when the TLS options of the route are different from the TLS options of the connection. On a connection for the correct name, no other route matches, and the request goes to its usual route.

A service that ends TLS itself, with no Traefik in front of it, cannot answer 421. If a browser uses the connection of such a service for another name, the service gets the request.

## Comparison

| | Passthrough | Terminate |
| --- | --- | --- |
| What the node sees | Only the SNI. | Plaintext. |
| Where TLS ends | On the homelab Traefik, or on the service. | On the node. |
| Certificates on the node | None for your services. | A certificate for each name. See [Certificates](../configuration/certificates.md). |
| Maintenance page | Not possible. | Enabled by default. |
| EE license | Not necessary. | Necessary for wildcard resources. |
| Pangolin sign-in | Not possible. | Possible. |
| CrowdSec | Not possible. | Possible. |
