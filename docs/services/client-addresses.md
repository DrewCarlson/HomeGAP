# Client addresses and private-only services

This page tells you how to give the homelab the real address of each viewer. It also tells you how to make a Traefik allowlist that only the devices of the VPN pass.

## The problem

Every connection reaches the homelab from the `homegap-mesh` container. Thus the homelab Traefik sees one address for all viewers. HomeGAP has three answers:

- For a name that a node terminates, the node puts the address of the viewer in the `X-Forwarded-For` header.
- `homegap.proxy_protocol` sends the address of each viewer on a passthrough name and on a raw TCP port.
- `homegap.private_network` sends the traffic of VPN devices from a separate network.

## Real client addresses

### Passthrough names

With `homegap.proxy_protocol: "true"` on the front door, the node adds a PROXY protocol v2 header to the encrypted stream. TLS stays passthrough, and the node reads only the SNI name. The same label works on a dedicated `https` service in `passthrough` mode.

The homelab Traefik must trust the header only from the `homegap-mesh` container. Give `homegap-mesh` a fixed address on the Traefik network, in `compose.override.yml`:

```yaml
services:
  homegap-mesh:
    networks:
      traefik: { ipv4_address: 172.32.0.250 }
```

Then trust that address on the entrypoint of the homelab Traefik:

```yaml
entryPoints:
  websecure:
    proxyProtocol:
      trustedIPs: ["172.32.0.250/32"]
```

Connections without the header continue to work, for example from your LAN and for the health request of each node.

!!! warning "Caution"
    If you add the label before the entrypoint trusts the header, every TLS handshake through the nodes fails. Configure the entrypoint first.

### Terminated names

A node that ends TLS for a name sends the request with `X-Forwarded-For`. To trust it on the homelab Traefik, add `forwardedHeaders.trustedIPs` to the entrypoint, with the same fixed address.

### Raw ports

On a raw service, `homegap.proxy_protocol: "true"` applies to the TCP ports. The agent of the node serves these ports and sends the header. The service must accept PROXY protocol v2. A UDP port has no PROXY protocol, and its service sees the address of the `homegap-mesh` container. See [Raw TCP and UDP](raw-ports.md#client-addresses).

### Where the label works

`homegap.proxy_protocol` is an error on a private service, on a terminated service and on a raw service with UDP ports only.

## Private-only services

A private-only service is a name of the front door that only VPN devices can open. The name stays on the homelab Traefik, with its certificate and its routers. This is different from a [private service](private.md), which has no public path.

With `homegap.private_network: "homegap-private"`, HomeGAP uses a second Docker network that only `homegap-mesh` and the front door share. Compose adds the project name to the start of the names of project networks. Thus HomeGAP matches a network with exactly the name in the label, or a name that ends in `_<label>`.

VPN devices resolve each private name to an address of the VPN. The homelab sends the traffic for that address to the front door, with these settings:

- The destination is the address of the front door on the private network.
- The ports are `homegap.port` (443 by default), plus 80 for `https`.
- The roles come from `homegap.roles`. Without the label, only the `Admin` role gets the private path.
- The id in the plan is `hg-private--<slug of the name>`.

The connection leaves `homegap-mesh` from the private network. Thus the homelab Traefik sees it come from that subnet, and public traffic never has an address in that range. On the private-only routers, accept only that range and your LAN:

```yaml
http:
  middlewares:
    private-only:
      ipAllowList:
        sourceRange: ["172.33.0.0/24", "192.168.1.0/24"]
```

Put the middleware on the routers of the private-only services, in labels or in the files of Traefik.

For the users, the roles and the devices of the VPN, see [Sign-in and VPN](../vpn/index.md) and [Users, machines and devices](../vpn/access.md).

### Public traffic never uses the private network

The `homegap-mesh` container is on both networks. To keep public traffic off the private network, HomeGAP sends it to the address of the front door on its other network, unless you set `homegap.host`.

HomeGAP gives an error in these cases:

| Case | Result |
| --- | --- |
| The front door is only on the private network. | An error. Public traffic needs another network that the front door shares with `homegap-mesh`. |
| The front door is on more than one other network. | An error. Set `homegap.host` to the address of the front door on the network that it shares with `homegap-mesh`. |
| The front door has no address on the named network. | An error. HomeGAP removes only the private path. Public traffic does not change. |

## Which names get a private path

Two labels select the names:

- `homegap.private_hosts` gives a list of names.
- `homegap.private_middleware` finds the names from the routers of Traefik.

You can use the two labels together or one of them alone. Without both, the private name is the hostname of the service.

HomeGAP adds some names itself, when the front door has a private path:

- The shared name of the status page.
- The shared name of the speed test, with `speedtest.vpn_access`.
- The name of the dashboard, with `dashboard.access: private`. See [The dashboard for VPN devices only](private.md#the-dashboard-for-vpn-devices-only).

### Find the names from the routers

With `homegap.private_middleware: "private-only@file"`, HomeGAP reads the routers of this Traefik from its API (`/api/http/routers`, all pages). It reads only this Traefik. It never reads another Traefik on the host.

Each name in a `Host(...)` rule of an enabled router that uses the middleware gets a private path. HomeGAP compares the middleware without its `@provider` part. These rules apply:

- A name outside the hostname of the front door gets a warning and no private path.
- HomeGAP cannot read the names of a router with `HostRegexp`, or of a router without `Host`. It gives a warning. List those names in `homegap.private_hosts`.

A service in a subfolder of the root gives the root its private path. An example is a router with ``Host(`example.net`) && PathPrefix(`/grafana`)``. VPN devices then reach the whole root over the private network. Its public routers continue to answer them.

### The Traefik API

The orchestrator must be able to reach the API of Traefik on the Traefik network. With `api.insecure: true` in the static config of Traefik, the API answers on port 8080. You do not publish a port for it. To use a different URL, set `homegap.traefik_api`.

Until the API answers for the first time, HomeGAP does not change the services. The log has the warning `cannot read the Traefik API at ...`, with the text "services and private paths are left as they are until it answers". `homegap plan` shows the error `the Traefik API at ... has not answered; set homegap.traefik_api if it is elsewhere`. After the first answer, a failed read keeps the routers from the last good read.

### Refused names

A VPN device must reach some names directly, not through the tunnel. These are the name of the sign-in (`sign_in.url`), the name in `vpn.endpoint` and the management host of each node. HomeGAP refuses a private name that covers one of them, with this error:

```text
homegap.private_hosts: '<name>' covers <host>, which VPN devices must reach directly; name the services instead
```

A device that resolves such a name into the tunnel loses its sign-in on the next reconnect.

Thus HomeGAP refuses `*.example.net`. A sub-zone such as `*.home` is correct.

## Example

The `compose.override.yml` of the HomeGAP stack has the fixed address and the private network:

```yaml
services:
  homegap-mesh:
    networks:
      traefik: { ipv4_address: 172.32.0.250 }
      homegap-private: {}

networks:
  homegap-private:
    ipam: { config: [ { subnet: 172.33.0.0/24 } ] }
```

The compose file of the homelab Traefik has the labels and joins the network. Compose names the network of the HomeGAP stack `homegap_homegap-private`:

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
      homegap.roles: "Member"
    networks: [traefik, homegap-private]

networks:
  traefik:
    external: true
  homegap-private:
    name: homegap_homegap-private
    external: true
```

The `homegap.private_hosts` label is optional.

## Configure client addresses and private-only services

1. Add the `homegap-private` network to `compose.override.yml`.
2. Attach `homegap-mesh` to this network in the same file.
3. Give `homegap-mesh` a fixed address on the Traefik network.
4. Run `docker compose up -d` in the folder of the HomeGAP stack. Compose recreates `homegap-mesh`, and the mesh drops for a moment.
5. Attach the homelab Traefik to the private network.
6. Add `proxyProtocol.trustedIPs` to the entrypoint of Traefik.
7. If you have terminated services, add `forwardedHeaders.trustedIPs` with the same address.
8. Restart Traefik. These settings are in the static config.
9. Add the labels to the front door.
10. Add the allowlist middleware to the private-only routers.
11. Set `homegap.roles` to the roles that get the private path.
12. Do the checks in the next section.

## Check the result

From the public internet, do these checks:

- A private-only name returns 403.
- A public service shows your real IP address in `X-Forwarded-For`. A whoami service can show this header.

From a device with a Pangolin app, connected to the VPN, do these checks:

- The private-only name works.
- The service sees an address in the private subnet.

## Troubleshooting

### On the VPN, a private-only name still returns 403

The name has no private path. On the **Config** page, look at `privateNames` in the desired state of a node. Also look at the log.

Make sure that the router uses the middleware by its name, on the router itself. HomeGAP reads the middleware list of the router. Or list the name in `homegap.private_hosts`.

Make sure that the user has a role of `homegap.roles` on the front door. A device gets a private name only if one of its roles permits it.

### Public traffic comes from the private subnet

The target of the front door is its private address. Set `homegap.host`, or keep the front door on only one other network.
