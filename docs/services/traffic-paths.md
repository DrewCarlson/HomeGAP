# Traffic paths

HomeGAP sends traffic to a service on one of several paths. This page tells you how each path works and when to use it.

## Front door, passthrough

This is the default path. The homelab Traefik claims the root domain and `*.<domain>`.

1. On each node, a Traefik TCP router on `:443` reads the TLS SNI. It matches the root and any single label under the domain.
2. The router forwards the encrypted stream into the Pangolin TCP resource `hg-frontdoor`.
3. The stream goes through the tunnel to the homelab Traefik.

The node never sees plaintext. Your certificates stay at home.

The node serves some names itself, so the wildcard passthrough does not include them:

- The management host of each node, `pangolin-<id>.<domain>`.
- In cluster mode, the client address `pangolin.<domain>`.
- The status page, `status.<domain>`.
- Dedicated hostnames that use `terminate`.

On port 80, the node sends a permanent redirect to HTTPS for the passthrough names.

Each passthrough resource has an internal proxy port on the node. The ports start at `pangolin.passthrough_port_base` (default 40000). HomeGAP never publishes these ports.

## Front door, terminate

With `homegap.tls: terminate` on the front door, HomeGAP uses Pangolin HTTP resources for the root and the wildcard. The node ends TLS and sends the request again to the homelab Traefik. The request keeps its host.

The node holds the same wildcard certificate as the homelab. See [Certificates](../configuration/certificates.md).

Wildcard HTTP resources are a Pangolin EE feature, so every node needs a license. See [Pangolin EE licenses](../configuration/licenses.md). If a node refuses the wildcard, HomeGAP removes that node from the wildcard DNS set. Thus viewers never go to a node that answers 404.

To compare the two modes, change this one label. Use `terminate` when you need Pangolin sign-in or CrowdSec.

## Dedicated hostname

A labeled service with its own hostname gets its own resource. This resource has priority over the wildcard. A public service also gets its own geo DNS record set.

An `https` service uses passthrough by default, directly to the service. It has its own SNI route with a higher priority than the front door. The node terminates plain `http` services.

## Health resource

Each node gets a health resource, `hg-health`. In cluster mode, its name is `hg-health-<node id>`. Its hostname is `homegap-health-<node id>.<domain>`.

The health resource goes to the front door. If there is no front door, it goes to the first dedicated `http` or `https` service. The request has the host header of that service and the path from its `homegap.healthcheck` (default `/`). The Bunny monitor on port 80 reaches this resource.

## When the homelab is down

For passthrough names, the node shows a "back shortly" page with HTTP status 503. The page starts approximately 15 seconds after the node loses the homelab. Raw ports and resources that the node terminates fail.

## Comparison

| | Passthrough | Terminate |
| --- | --- | --- |
| What the node sees | Only the SNI. | Plaintext. |
| Where TLS ends | On the homelab Traefik, or on the service. | On the node. |
| Certificates on the node | None for your services. | A certificate for each name. See [Certificates](../configuration/certificates.md). |
| EE license | Not necessary. | Necessary for wildcard resources. |
| Pangolin sign-in | Not possible. | Possible. |
| CrowdSec | Not possible. | Possible. |
