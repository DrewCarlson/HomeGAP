# Private services

A private service is available only through the Pangolin client. This page tells you how HomeGAP publishes a private service in each mode.

## How a private service works

With `homegap.access: private`, the service becomes a Pangolin private resource in host mode. HomeGAP sets the resource like this:

- The alias is the value of `homegap.expose`.
- The destination is the value of `homegap.host`. The default is the container name.
- The ports are the container ports from `homegap.port`, by protocol, as `tcp-ports` and `udp-ports`. For a raw service with a port list, a `public:` part has no effect here.

A private service has no public DNS record and no public port.

`homegap.roles` lists the Pangolin roles that can reach the service. The `Admin` role can always reach it.

A private service is different from the private path of a public service. A private service has no public path. For the private path, see [Client addresses and private-only services](client-addresses.md).

## Users and connections in each mode

=== "Standalone"

    Each node that serves the service has its own private resource, in its own Pangolin. The Pangolin app connects to the Pangolin of one node, at its management host `https://pangolin-<id>.<domain>`.

    Users and roles are different on each node. You manage them in the Pangolin web UI of each node. To open that UI, use SSH port forwarding. See [Manage nodes](../operations/nodes.md).

    The **Access** page on the dashboard is for cluster mode only.

=== "Cluster"

    One private resource lists the site of every node that serves the service. The Pangolin app connects to `pangolin.<domain>`, and DNS sends it to the nearest healthy node. The app then relays through that node to the homelab.

    The app selects the node when it connects or when it changes network. You manage the users on the **Access** page. See [Users and devices](../cluster/access.md).

## Publish the dashboard as a private service

!!! danger "Warning"
    If you publish the dashboard as a public service, everybody on the internet can reach the control of every node. Publish it only as a private resource.

Put the labels on the `homegap-mesh` service, because it owns the networks of the orchestrator. It is on the `traefik` network. For example:

```yaml
homegap-mesh:
  labels:
    homegap.expose: "homegap.example.net"
    homegap.access: "private"
    homegap.protocol: "http"
    homegap.port: "8088"
    homegap.host: "orchestrator"
```

The destination of the private resource is `orchestrator`, the alias of `homegap-mesh` on the `traefik` network. The port is 8088.

The dashboard must listen on an address that the `tunnels` container can reach. The example config uses `dashboard.bind: 0.0.0.0:8088`. The default `127.0.0.1:8088` is not reachable from other containers.

From a device with the Pangolin client, open `http://homegap.example.net:8088`.
