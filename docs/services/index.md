# Services

This page tells you how HomeGAP publishes the services of your homelab, and when a service needs its own labels.

## Labels are the source of truth

The orchestrator reads the `homegap.*` labels of your containers from Docker. It reads through the `docker-proxy` service, which refuses each request that changes something. The orchestrator never writes labels. When a container starts, stops or changes, the Docker event starts a pass at once. The debounce is `reconcile.debounce` (default `2s`).

## Most services need no labels

The front door carries every name under your domain. The **front door** is the homelab Traefik container with the root domain in `homegap.expose` and `homegap.wildcard: "true"`. To publish a service through the front door, add a normal Traefik router at home.

HomeGAP treats a service as the front door when its `homegap.expose` is equal to `domain` and its access is public. A raw service is never the front door.

## When a service needs labels

Label a container only when it needs its own path:

- A dedicated hostname. The service gets its own resource and its own DNS record set. This resource has priority over the wildcard. See [Traffic paths](traffic-paths.md).
- Raw TCP or UDP ports. See [Raw TCP and UDP](raw-ports.md).
- A private service, for Pangolin clients only. See [Private services](private.md).
- Pangolin sign-in in front of the service. See [Pangolin sign-in for services](authentication.md).
- Only some of the nodes serve the service. Use `homegap.regions` for this.

For all labels, see the [Label reference](labels.md). For the real addresses of viewers and for services that only Pangolin clients can open, see [Client addresses and private-only services](client-addresses.md).

## Several services on one container

One container can declare more than one service, with **named services** under `homegap.services.<name>.`. For example, a voice server can have a port list on one name and a second service with a different public port. See [Named services](labels.md#named-services).

## Who can claim a hostname

Only one `http` or `https` service can claim a hostname. If two containers have the same `homegap.expose`, the first container in the order of names wins. Each other container gets this error:

```text
hostname <name> is already claimed by <container>
```

Raw services can share a hostname with each other, and with one `http` or `https` service. On the nodes that they share, only one public service can use a public port and protocol. See [Shared hostnames and ports](labels.md#shared-hostnames-and-ports).

## How the tunnel finds the container

The tunnel connects to the container by its name, or by the value of `homegap.host`. The name must resolve on a Docker network that the container shares with the `tunnels` container.

## Errors and warnings

The **Services** page on the dashboard and the log show the label errors of each container. HomeGAP does not publish a service with errors. The errors of one container never stop the other services. The errors of a named service start with `services.<name>:`, and they drop only that service.

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
