# Private services

A private service is available only to the devices of the VPN. This page tells you how HomeGAP publishes a private service, and who can reach it.

## How a private service works

With `homegap.access: private`, a service gets no public DNS record, no public port and no route on a node. HomeGAP gives the service a **private name**, which only VPN devices resolve and reach:

- The name is the value of `homegap.expose`.
- The destination is the container, or the value of `homegap.host`.
- The ports are the ports of the container. For an `http` or `https` service, this is `homegap.port` (default 80 or 443). For a raw service, these are the container ports of the port list, by protocol. A `public:` part has no effect here.

A device reaches a private service on this path:

1. A user signs in with a Pangolin app, at the address of `sign_in.url`. See [Connect the Pangolin apps](../vpn/pangolin-apps.md).
2. The app gets the private names that the roles of the user permit. Each name has an address in `vpn.utility_subnet` (default `100.96.128.0/24`).
3. The app sends the packets for that address through the tunnel to a node. The node forwards them over the mesh to the homelab.
4. The `homegap-mesh` container sends them to the container of the service, on the same port.

HomeGAP ends no TLS for a private service. The device connects directly to the port of the container. The service sees the address of the `homegap-mesh` container as the source.

Without `sign_in.url`, there is no sign-in and no VPN, and no device can reach a private service. See [Sign-in and VPN](../vpn/index.md).

A private service is different from the private path of a public service. A private service has no public path. For the private path, see [Client addresses and private-only services](client-addresses.md).

## Who can reach a private service

The VPN refuses everything by default. A device reaches only the private names that its roles permit, and only on the ports of each service. The firewall of the `homegap-mesh` container refuses each other packet of a device, also to your home network.

`homegap.roles` lists the roles that can reach the service, separated by commas:

```yaml
notes:
  labels:
    homegap.expose: "notes.example.net"
    homegap.access: "private"
    homegap.port: "8080"
    homegap.roles: "Member,family"
```

These rules apply:

- The `Admin` role reaches each private service.
- A service without `homegap.roles` is for the `Admin` role only.
- A device has the roles of its user. A machine has its own roles.
- A blocked user, a blocked machine and a blocked device reach nothing.
- A named service takes `homegap.roles` and `homegap.access` from the top-level labels, unless it sets its own values.

You manage the users, the machines and their roles on the **Access** page of the dashboard. See [Users, machines and devices](../vpn/access.md).

## What the service needs at home

The `homegap-mesh` container must share a Docker network with the container of the service. A container on the Traefik network needs no change. For a container on a different network, add that network to the `homegap-mesh` service in `compose.override.yml`. See [How HomeGAP finds the container](index.md#how-homegap-finds-the-container).

If HomeGAP finds no address for the service, the log has this warning:

```text
no address for the private target <host>: the VPN does not serve it
```

## When the homelab is down

A private service has no path while the homelab is unreachable. A node then signs devices in for the exits only. See [Exits and tunnel DNS](../vpn/exit-nodes.md).

## The dashboard for VPN devices only

!!! danger "Warning"
    If you publish the dashboard to the public, everybody on the internet can reach the control of every node, with only the token as protection. Publish it only to VPN devices.

Do not put `homegap.*` labels on the `orchestrator` service for this. Set `dashboard.hostname` in the config file:

```yaml
dashboard:
  hostname: orc
  access: private
```

The Traefik of the homelab then serves the dashboard at `orc.<domain>`. With `dashboard.access: private`, which is the default, the name is a private name of the front door. Thus the front door needs `homegap.private_network`. See [Private-only services](client-addresses.md#private-only-services). The roles come from `homegap.roles` on the front door.

Without a private path on the front door, the dashboard has no route, and you get this error:

```text
dashboard.access is private, but the front door has no homegap.private_network: the dashboard has no route
```

For the other keys of `dashboard`, see the [Configuration reference](../configuration/reference.md).

## Reach the homelab host over SSH

HomeGAP can publish the SSH server of the homelab host as a private service. Then the host accepts SSH from VPN devices, and the internet has no path to it. You set one key in the config file. The compose file needs no change.

1. Add the key to the config file:

    ```yaml
    homelab:
      ssh:
        enabled: true
    ```

2. Save the config file. HomeGAP reads it in the next pass.
3. On a device, connect the Pangolin app.
4. Connect to the private name:

    ```bash
    ssh user@ssh.example.net
    ```

| Key | Default | What it does |
| --- | --- | --- |
| `homelab.ssh.enabled` | `false` | Publishes the SSH server of the host as a private service. |
| `homelab.ssh.hostname` | `ssh` | The first label of the private name, `<hostname>.<domain>`. |
| `homelab.ssh.port` | `22` | The port of the SSH server of the host. |
| `homelab.ssh.roles` | `[]` | The roles that can reach the host. The `Admin` role always can. |

For example, to give access to the users with the role `Member`:

```yaml
homelab:
  ssh:
    enabled: true
    roles: [Member]
```

### How HomeGAP finds the host

The mesh agent (`homegap-mesh-agent`) reads the gateway of the default route of its network namespace. This is the address of the Docker host. It writes that address to the file `host-gateway` in the `mesh` data folder. The orchestrator uses the address as the destination of the service.

The **Services** page shows the service with the container name `homelab`. If the log shows "homelab.ssh waits for the address of the host from the mesh agent", start the `homegap-mesh-agent` container.

### If the connection fails

Look at the host:

- `sshd` must listen on an address that a container can reach. The default `ListenAddress` (all addresses) is correct.
- The firewall of the host must accept the SSH port from the Docker networks.

### A different service on the host

For a service of the host that is not SSH, use labels on a container, with an IPv4 address of the host in `homegap.host`. The LAN address of the host works. A hostname does not work there, because HomeGAP resolves no name for a target.

!!! danger "Warning"
    If the front door has a wildcard (`homegap.wildcard`), public DNS also answers for the private name, with the addresses of the nodes. A device that does not resolve the name through the Pangolin app connects to the SSH server of a node. That server then receives your user name and your password or key. Before you accept a host key, compare its fingerprint with the key of the homelab host.

To read the fingerprint of the host key, run this command on the homelab host:

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```
