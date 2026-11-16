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

## How a raw port works

The kernel of the node forwards a raw port. The Traefik of the node has no part in it.

1. A packet arrives at the public port of the node.
2. A rule in the chain `HG-RAW` of the node changes its destination to the same port on the mesh address of the homelab.
3. The packet goes over the mesh to the homelab.
4. The `homegap-mesh` container forwards it to the port of the container of the service.

HomeGAP writes the full set of rules of a node in one step, and the node applies the rules again at boot. The chain `HG-RAW-FWD` accepts the forwarded connections before the rules of `ufw`. Thus a new port needs no `ufw` rule and no restart of a container.

The hostname also gets a geo DNS record set. Raw services that share a hostname share one record set. See [Shared hostnames and ports](labels.md#shared-hostnames-and-ports).

The container must have an address that the `homegap-mesh` container can reach. See [How HomeGAP finds the container](index.md#how-homegap-finds-the-container).

### Public ports

The public port of an entry is its `public:` part. Without it, the public port is the same as the container port. For a service with a single port, `homegap.public_port` also sets the public port.

HomeGAP refuses public ports that the node uses itself. For the list, see the [Label reference](labels.md#rules-for-the-extension-labels).

### Use port 22 for a service

A node uses its SSH port itself, so a service cannot have it as a public TCP port. The default SSH port is 22. To give port 22 to a service, for example the SSH of a git server, first move the SSH server of each node that serves the service.

1. Set `ssh_port` of each node to a different port, for example `2222`. See [Node updates and rollback](../operations/node-updates.md).
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

### Ids in the plan

The output of `homegap plan` shows one item for each port and protocol:

| Service | Id |
| --- | --- |
| A top-level service with one port and one protocol | `hg-<host>-<proto>`, for example `hg-game-example-net-udp`. |
| Any other top-level service | `hg-<host>-<public port>-<proto>`, for example `hg-voice-example-net-7882-udp`. |
| A named service | `hg-<host>--<name>-<public port>-<proto>`, for example `hg-rtc-example-net--alt-17000-udp`. |

## Client addresses

A raw service sees the address of the `homegap-mesh` container as the source of each connection, on TCP and on UDP.

`homegap.proxy_protocol: "true"` sends the address of each viewer to the service, on the TCP ports only. The service must accept PROXY protocol v2. The kernel cannot add this header, so the agent of the node (`homegap-agent`) serves these ports:

1. The agent accepts each connection on the public port.
2. It opens a connection to the same port on the mesh address of the homelab.
3. It sends the PROXY protocol header, and then it passes the bytes in both directions.

The agent opens a new port and closes a removed port without a restart. Open connections continue. There is no PROXY protocol for UDP. See [Client addresses and private-only services](client-addresses.md).

## Private raw services

With `homegap.access: private`, a raw service gets no public port and no DNS record. Only the devices of the VPN can reach it, on the ports of the container. A `public:` part has no effect there. See [Private services](private.md).

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
    homegap.roles: "Member"
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
