# Raw TCP and UDP

HomeGAP can publish a service that does not use HTTP, for example a game server, a voice server or an SSH jump host. This page tells you how raw TCP and UDP services work.

## The labels

A raw service needs these labels:

- `homegap.expose`, with a hostname for the service.
- `homegap.port`, the port or the list of ports of the container. Raw services need this label.
- The protocol: `homegap.protocol` (`tcp`, `udp` or `tcp+udp`), or a protocol on each entry of the port list.

For example, these labels publish TCP 7881 and UDP 7882 without `homegap.protocol`:

```yaml
homegap.expose: "voice.example.net"
homegap.port: "7881/tcp, 7882/udp"
```

For the format of the port list and its rules, see [Port lists](labels.md#port-lists).

`homegap.wildcard` is an error on a raw service. `homegap.tls` has no effect. To limit the nodes that serve the service, use `homegap.regions`.

## What HomeGAP creates

For each port and protocol of a public raw service, HomeGAP creates these items on each node of the service:

- A Pangolin resource.
- A Traefik entrypoint.
- A published Gerbil port.
- A `ufw` rule.

The hostname also gets a geo DNS record set. Raw services that share a hostname share one record set. See [Shared hostnames and ports](labels.md#shared-hostnames-and-ports).

### Public ports

The public port of an entry is its `public:` part. Without it, the public port is the same as the container port. For a service with a single port, `homegap.public_port` also sets the public port.

HomeGAP refuses public ports that the node uses itself. For the list, see the [Label reference](labels.md#rules-for-the-extension-labels).

### Use port 22 for a service

A node uses its SSH port itself, so a service cannot have it as a public TCP port. The default SSH port is 22. To give port 22 to a service, for example the SSH of a git server, first move the SSH server of each node that serves the service.

1. Set `ssh_port` of each node to a different port, for example `2222`. See [Move the SSH port of a node](../operations/node-updates.md#move-the-ssh-port-of-a-node).
2. Wait until the activity log has the line "moved the SSH server from port 22 to port 2222" for each node.
3. Add the labels to the service:
    ```yaml
    forgejo:
      labels:
        homegap.expose: "git.example.net"
        homegap.port: "22/tcp"
    ```

If a node still has port 22 as its SSH port, HomeGAP refuses the service with the error `public port 22/tcp is the SSH port of node <id>`. Web traffic for the same hostname does not change. It uses the front door, as before.

Port 22 on the nodes then goes to this service for each hostname, because TCP carries no hostname.

### Resource ids

| Service | niceId |
| --- | --- |
| A top-level service with one port and one protocol | `hg-<host>-<proto>`, for example `hg-game-example-net-udp`. |
| Any other top-level service | `hg-<host>-<public port>-<proto>`, for example `hg-voice-example-net-7882-udp`. |
| A named service | `hg-<host>--<name>-<public port>-<proto>`, for example `hg-rtc-example-net--alt-17000-udp`. |

The first form is the id from before port lists. Thus an upgrade does not recreate the resources of existing services.

## Health checks and client addresses

`homegap.healthcheck: tcp` applies only to the TCP ports. UDP ports have no health check of their target.

`homegap.proxy_protocol: "true"` applies only to the TCP ports. The service must accept PROXY protocol v2. See [Client addresses and private-only services](client-addresses.md).

A UDP service sees the address of the `tunnels` container, because there is no PROXY protocol for UDP. Each caller keeps its own path through the tunnel. In the end-to-end tests, two UDP callers at the same time each get their own reply, from different source ports. UDP payloads up to 1400 bytes pass in both directions, with `tunnels.mtu: 1420`.

!!! warning "Caution"
    A new raw port changes the stack of the node: a new entrypoint and a new Gerbil port. Thus HomeGAP updates the nodes one at a time. See [Node updates and rollback](../operations/node-updates.md).

## Private raw services

With `homegap.access: private`, a raw service gets no public port and no DNS record. Only Pangolin clients can reach it. The private resource lists the container ports by protocol, as `tcp-ports` and `udp-ports`. A `public:` part has no effect there. See [Private services](private.md).

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

A private SSH jump host on TCP port 22. A private service has no public port, so the SSH port of the nodes does not matter:

```yaml
ssh-jump:
  labels:
    homegap.expose: "ssh.example.net"
    homegap.port: "22"
    homegap.protocol: "tcp"
    homegap.access: "private"
    homegap.regions: "na1,as1"
```

A public SSH server on public port 2222, to port 22 of the container:

```yaml
git-ssh:
  labels:
    homegap.expose: "git.example.net"
    homegap.port: "2222:22/tcp"
```

A voice server with several ports, and a named service on the same hostname:

```yaml
rtc:
  labels:
    homegap.expose: "rtc.example.net"
    homegap.port: "7881/tcp, 7882/udp, 3478/tcp+udp"
    homegap.services.alt.expose: "rtc.example.net"
    homegap.services.alt.port: "17000:7000/udp"
```

For a full example with a chat app and its voice server, see [Fluxer](examples/fluxer.md).
