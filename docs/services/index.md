# Services

This page tells you how HomeGAP publishes the services of your homelab, and when a service needs its own labels.

## Labels are the source of truth

The orchestrator reads the `homegap.*` labels of your containers through the Docker socket. The socket mount is read-only, and the orchestrator never writes labels. When a container starts, stops or changes, the Docker event starts a pass at once. The debounce is `reconcile.debounce` (default `2s`).

## Most services need no labels

The front door carries every name under your domain. The **front door** is the homelab Traefik container with the root domain in `homegap.expose` and `homegap.wildcard: "true"`. To publish a service through the front door, add a normal Traefik router at home.

HomeGAP treats a service as the front door when its `homegap.expose` is equal to `domain` and its access is public.

## When a service needs labels

Label a container only when it needs its own path:

- A dedicated hostname. The service gets its own resource and its own DNS record set. This resource has priority over the wildcard. See [Traffic paths](traffic-paths.md).
- A raw TCP or UDP port. See [Raw TCP and UDP](raw-ports.md).
- A private service, for Pangolin clients only. See [Private services](private.md).
- Pangolin sign-in in front of the service. See [Pangolin sign-in for services](authentication.md).
- Only some of the nodes serve the service. Use `homegap.regions` for this.

For all labels, see the [Label reference](labels.md). For the real addresses of viewers and for services that only Pangolin clients can open, see [Client addresses and private-only services](client-addresses.md).

## One container for each hostname

Only one container can claim a hostname. If two containers have the same `homegap.expose`, the first container in the order of names wins. Each other container gets this error:

```text
hostname <name> is already claimed by <container>
```

## How the tunnel finds the container

The tunnel connects to the container by its name, or by the value of `homegap.host`. The name must resolve on a Docker network that the container shares with the `tunnels` container.

## Errors and warnings

The **Services** page on the dashboard and the log show the label errors of each container. HomeGAP does not publish a container with errors. The errors of one container never stop the other services.

A warning does not stop the publication of the container. An unknown `homegap.*` label gives this warning, and HomeGAP ignores the label:

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
