# Client addresses and private-only services

This page tells you how to give the homelab the real address of each viewer. It also tells you how to make a Traefik allowlist that only Pangolin clients pass. The result is like Cloudflare WARP with two tunnels, but with one container.

## The problem

Every connection reaches the homelab from the `tunnels` container. Thus the homelab Traefik sees one address for all viewers. Two labels on the front door solve this:

- `homegap.proxy_protocol` sends the real address of each public viewer.
- `homegap.private_network` sends the traffic of Pangolin clients from a separate network.

## Real client addresses

With `homegap.proxy_protocol: "true"` on the front door, the node adds a PROXY protocol v2 header to the encrypted stream. TLS stays passthrough, and the node reads only the SNI name.

The Pangolin TCP resource gets `proxy-protocol: true`, version 2. Standalone nodes get the transport `pp-transport-v2` from the routes file of HomeGAP. Clustered nodes use the definition of Pangolin.

The homelab Traefik must trust the header only from the `tunnels` container. Give `tunnels` a fixed address on the `traefik` network, for example `172.32.0.250`. Then trust that address on the entrypoint:

```yaml
entryPoints:
  websecure:
    proxyProtocol:
      trustedIPs: ["172.32.0.250/32"]
```

Connections without the header from a trusted address continue to work, for example from your LAN and from the health probes of the nodes. Terminated services get `X-Forwarded-For` instead. To trust it, add `forwardedHeaders.trustedIPs` with the same address.

!!! warning "Caution"
    If you add the label before the entrypoint trusts the header, every TLS handshake through the nodes fails. Configure the entrypoint first.

## Private-only services

With `homegap.private_network: "homegap-private"`, HomeGAP uses a second Docker network that only `tunnels` and the front door share. Compose adds the project name to the start of the names of project networks. Thus HomeGAP matches a network with exactly the name in the label, or a name that ends in `_<label>`.

Pangolin clients resolve each private name to a private resource with these settings:

- The destination is the address of the front door on the private network.
- The ports are `homegap.port` (443 by default), plus 80 for `https`.
- The roles come from `homegap.roles`.
- The niceId is `hg-private--<slug of the name>`.

The connection leaves `tunnels` from the private network. Thus the homelab Traefik sees it come from that subnet, and public traffic never has an address in that range. On the private-only routers, accept only that range and your LAN:

```yaml
http:
  middlewares:
    private-only:
      ipAllowList:
        sourceRange: ["172.33.0.0/24", "192.168.1.0/24"]
```

Put the middleware on the routers of the private-only services, in labels or in the files of Traefik.

### Public traffic never uses the private network

The `tunnels` container is on both networks. Thus Docker DNS can answer the container name with the private address. To prevent this, HomeGAP sends public traffic to the address of the front door on its other network, unless you set `homegap.host`.

HomeGAP gives an error in these cases:

| Case | Result |
| --- | --- |
| The front door is only on the private network. | An error. Public traffic needs another network that the front door shares with `tunnels`. |
| The front door is on more than one other network. | An error. Set `homegap.host` to the address of the front door on the network that it shares with `tunnels`. |
| The front door has no address on the named network. | An error. HomeGAP leaves out only the private path. Public traffic does not change. |

## Which names get a private path

Two labels select the names:

- `homegap.private_hosts` gives a list of names.
- `homegap.private_middleware` finds the names from the routers of Traefik.

You can use the two labels together or one of them alone.

### Find the names from the routers

With `homegap.private_middleware: "private-only@file"`, HomeGAP reads the routers of this Traefik from its API (`/api/http/routers`, all pages). It reads only this Traefik. It never reads another Traefik on the host.

Each name in a `Host(...)` rule of an enabled router that uses the middleware gets a private path. HomeGAP compares the middleware without its `@provider` part. These rules apply:

- A name outside the hostname of the front door gets a warning and no private path.
- HomeGAP cannot read the names of a router with `HostRegexp`, or of a router without `Host`. It gives a warning. List those names in `homegap.private_hosts`.

A service in a subfolder of the root gives the root its private path. An example is a router with ``Host(`example.net`) && PathPrefix(`/grafana`)``. Pangolin clients then reach the whole root over the private network. Its public routers continue to answer them.

### The Traefik API

The orchestrator must be able to reach the API of Traefik on the `traefik` network. With `api.insecure: true` in the static config of Traefik, the API answers on port 8080. You do not publish a port for it. To use a different URL, set `homegap.traefik_api`.

Until the API answers for the first time, HomeGAP does not change the services. The log has the warning `cannot read the Traefik API at ...`, with the text "services and private paths are left as they are until it answers". `homegap plan` shows the error `the Traefik API at ... has not answered; set homegap.traefik_api if it is elsewhere`. After the first answer, a failed read keeps the routers from the last good read.

### Refused names

HomeGAP refuses a name that covers the client address `pangolin.<domain>` or the management host of a node, with an error. A Pangolin client tries to reach its own server through the tunnel for such a name. Then it loses the connection on its next reconnect.

Thus HomeGAP refuses `*.example.net`. A sub-zone such as `*.home` is correct.

## Example

This compose example has the front door, the `tunnels` service and the private network:

```yaml
services:
  traefik:
    labels:
      homegap.expose: "example.net"
      homegap.wildcard: "true"
      homegap.protocol: "https"
      homegap.proxy_protocol: "true"
      homegap.private_network: "homegap-private"
      homegap.private_middleware: "private-only@file"
      homegap.private_hosts: "*.home"
    networks: [traefik, homegap-private]
  tunnels:
    networks:
      traefik: { ipv4_address: 172.32.0.250 }
      homegap-private: {}

networks:
  homegap-private:
    ipam: { config: [ { subnet: 172.33.0.0/24 } ] }
```

The `homegap.private_hosts` label is optional.

## Configure client addresses and private-only services

1. Create the `homegap-private` network.
2. Attach `tunnels` and the homelab Traefik to this network.
3. Give `tunnels` a fixed address on the `traefik` network.
4. Add `proxyProtocol.trustedIPs` to the entrypoint of Traefik.
5. If you have terminated services, add `forwardedHeaders.trustedIPs` with the same address.
6. Restart Traefik. These settings are in the static config.
7. Add the labels to the front door.
8. Add the allowlist middleware to the private-only routers.
9. Set `homegap.roles` to the roles that get the private path.
10. Do the checks in the next section.

## Check the result

From the public internet, do these checks:

- A private-only name returns 403.
- A public service shows your real IP address in `X-Real-Ip`. A whoami service can show this header.

From a device with the Pangolin client, do these checks:

- The private-only name works.
- The service sees an address in the private subnet.

## Troubleshooting

### On the VPN, a private-only name still returns 403

The name has no private path. On the **Config** page, look at the desired state for `hg-private--` resources. Also look at the log.

Make sure that the router uses the middleware by its name, on the router itself. HomeGAP reads the middleware list of the router. Or list the name in `homegap.private_hosts`.

### Public traffic comes from the private subnet

The target of the front door is its private address. Set `homegap.host`, or keep the front door on only one other network.
