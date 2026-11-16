# Services

This page tells you how HomeGAP publishes the services of your homelab, and when a service needs its own labels.

## Labels are the source of truth

The orchestrator reads the `homegap.*` labels of your containers from Docker. It reads through the `docker-proxy` service, which refuses each request that changes something. The orchestrator never writes labels. When a container starts, stops or changes, the Docker event starts a pass at once. The debounce is `reconcile.debounce` (default `2s`).

## Most services need no labels

The front door carries the root and every name one label under your domain. The **front door** is the homelab Traefik container with the root domain in `homegap.expose` and `homegap.wildcard: "true"`. To publish a service through the front door, add a normal Traefik router at home.

HomeGAP treats a service as the front door when its `homegap.expose` is equal to `domain` and its access is public. A raw service is never the front door. Each extra domain can have a front door of its own. See [Extra domains](extra-domains.md).

## When a service needs labels

Label a container only when it needs its own path:

- A dedicated hostname. The service gets its own route on the nodes and its own DNS record set. This route has priority over the wildcard. See [Traffic paths](traffic-paths.md).
- Raw TCP or UDP ports. See [Raw TCP and UDP](raw-ports.md).
- A private service, for the devices of the VPN only. See [Private services](private.md).
- Only some of the nodes serve the service. Use `homegap.regions` for this.

For all labels, see the [Label reference](labels.md). For the real addresses of viewers and for names that only VPN devices can open, see [Client addresses and private-only services](client-addresses.md).

HomeGAP has no sign-in for a public service. To put a sign-in in front of a public service, use a forward-auth middleware on the Traefik of the homelab.

## Several services on one container

One container can declare more than one service, with **named services** under `homegap.services.<name>.`. For example, a voice server can have a port list on one name and a second service with a different public port. See [Named services](labels.md#named-services).

## Who can claim a hostname

Only one `http` or `https` service can claim a hostname. If two containers have the same `homegap.expose`, the first container in the order of names wins. Each other container gets this error:

```text
hostname <name> is already claimed by <container>
```

Raw services can share a hostname with each other, and with one `http` or `https` service. On the nodes that they share, only one public service can use a public port and protocol. See [Shared hostnames and ports](labels.md#shared-hostnames-and-ports).

## How HomeGAP finds the container

Each node sends the traffic of a service over the mesh to the homelab. There, the `homegap-mesh` container forwards it to the address of the container of the service. The orchestrator reads that address from Docker. Nothing resolves a name at the time of a connection.

The target is the container that has the labels. To use a different target, set `homegap.host` to one of these values:

- An IPv4 address.
- The name of a container that has a service in its own `homegap.*` labels. The name of its Compose service also works.

The `homegap-mesh` container must share a Docker network with the target. HomeGAP selects the address of the target in this order:

1. The address on a network of a front door container. In the usual stack, this is the Traefik network.
2. The only address of the target, if the target is on one network.

A target on a different network needs that network on the `homegap-mesh` service, in `compose.override.yml`. The file `compose.override.example.yml` has an example. If HomeGAP finds no address, the log has this warning, and the ports of the service stay closed:

```text
no address for the target <host>: its ports are closed
```

## When a container is gone

When a container with `homegap.*` labels stops, HomeGAP keeps its services for `reconcile.removal_grace` (default `3m`). After that time, HomeGAP removes the routes and the DNS records of the container.

The grace period prevents a short outage after a restart of the homelab. The orchestrator can start before your app containers. Without the grace period, HomeGAP removes each service and creates it again a moment later.

A new container with the same hostname in its top-level `homegap.expose` immediately replaces the container that is gone. If the orchestrator cannot list the containers at all, it uses the containers of the last list until Docker answers again.

## Errors and warnings

The **Services** page on the dashboard and the log show the label errors of each container. HomeGAP does not publish a service whose labels have errors. An error about `homegap.private_network` or `homegap.private_hosts` that comes from the Docker networks or from the names of the VPN removes only the private path. The public service still publishes. The errors of one container never stop the other services. The errors of a named service start with `services.<name>:`, and they drop only that service.

A warning does not stop the publication of the service. An unknown `homegap.*` label gives this warning, and HomeGAP ignores the label:

```text
unknown label '<key>' ignored
```

## Preview the result of labels

The `homegap plan` command shows what a set of labels produces, without Docker. The input is a JSON file that maps each container name to its labels:

```json
{
  "traefik": {
    "homegap.expose": "example.net",
    "homegap.wildcard": "true",
    "homegap.protocol": "https"
  }
}
```

The orchestrator process must be able to read the file. If you put the file in the `config/` folder next to `config.yml` and mount it at `/config/labels.json`, run this command:

```bash
docker compose exec orchestrator homegap plan --labels /config/labels.json
```

The output starts with the `ERROR` and `WARN` lines. Then, for each node, it shows the `public`, `private`, `sni` and `port` items. The `dns` records come last.

!!! note
    `homegap plan` does not read Docker networks or the Traefik API. Thus a front door with `homegap.private_network` or `homegap.private_middleware` gives errors in the plan output. These errors do not apply to the orchestrator, which reads both.
