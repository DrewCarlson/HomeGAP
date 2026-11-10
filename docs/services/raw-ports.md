# Raw TCP and UDP

HomeGAP can publish a service that does not use HTTP, for example a game server or an SSH jump host. This page tells you how raw TCP and UDP services work.

## The labels

A raw service needs these labels:

- `homegap.protocol: tcp` or `homegap.protocol: udp`.
- `homegap.port`, the port of the container. This label is necessary for raw services.
- `homegap.expose`, with a hostname for the service.

`homegap.wildcard` is an error on a raw service. `homegap.tls` has no effect. To limit the nodes that serve the service, use `homegap.regions`.

## What HomeGAP creates

A public raw service gets its own public port on each of its nodes. For this port, HomeGAP creates:

- A Traefik entrypoint.
- A published Gerbil port.
- A `ufw` rule.
- A geo DNS record set for the hostname.

The Pangolin resource has the niceId `hg-<key>-tcp` or `hg-<key>-udp`. The key comes from the hostname.

The public port is the value of `homegap.public_port`, or else the value of `homegap.port`. HomeGAP refuses the ports that the node uses itself. For the list, see the [Label reference](labels.md#rules-for-the-extension-labels).

## Health checks and client addresses

`homegap.healthcheck` applies only to TCP services. A UDP service has no health check of its target.

`homegap.proxy_protocol: "true"` works on TCP services. The service must accept PROXY protocol v2. See [Client addresses and private-only services](client-addresses.md).

!!! warning "Caution"
    A new raw port changes the stack of the node: a new entrypoint and a new Gerbil port. Thus HomeGAP updates the nodes one at a time. See [Node updates and rollback](../operations/node-updates.md).

## Private raw services

With `homegap.access: private`, a raw service gets no public port and no DNS record. Only Pangolin clients can reach it. See [Private services](private.md).

## Examples

A public game server on UDP port 27015, only on `eu1`:

```yaml
game:
  labels:
    homegap.expose: "game.example.net"
    homegap.protocol: "udp"
    homegap.port: "27015"
    homegap.regions: "eu1"
```

A private SSH jump host on TCP port 22. Port 22 is reserved on the nodes, but a private service has no public port, so HomeGAP accepts it:

```yaml
ssh-jump:
  labels:
    homegap.expose: "ssh.example.net"
    homegap.port: "22"
    homegap.protocol: "tcp"
    homegap.access: "private"
    homegap.regions: "na1,as1"
```
