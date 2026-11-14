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

Put the labels on the `orchestrator` service. It is on the `traefik` network. For example:

```yaml
orchestrator:
  labels:
    homegap.expose: "homegap.example.net"
    homegap.access: "private"
    homegap.protocol: "http"
    homegap.port: "8088"
    homegap.host: "orchestrator"
```

The destination of the private resource is `orchestrator`, the name of the service on the `traefik` network. The port is 8088.

The dashboard must listen on an address that the `tunnels` container can reach. The example config uses `dashboard.bind: 0.0.0.0:8088`. The default `127.0.0.1:8088` is not reachable from other containers.

From a device with the Pangolin client, open `http://homegap.example.net:8088`.

## Reach the homelab host over SSH

HomeGAP can publish the SSH server of the homelab host as a private service. Then the host accepts SSH from Pangolin clients, and the internet has no path to it. You set one key in the config file. The compose file needs no change.

1. Add the key to the config file:

    ```yaml
    homelab:
      ssh:
        enabled: true
    ```

2. Save the config file. HomeGAP reads it in the next pass. The activity log shows `ensured private resource` for `ssh.<domain>`.
3. On a device, connect the Pangolin app.
4. Connect to the alias:

    ```bash
    ssh user@ssh.example.net
    ```

| Key | Default | What it does |
| --- | --- | --- |
| `homelab.ssh.enabled` | `false` | Publishes the SSH server of the host as a private service. |
| `homelab.ssh.hostname` | `ssh` | The first label of the alias, `<hostname>.<domain>`. |
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

The `tunnels` container opens the connection to the SSH server. When it starts, it reads the gateway of its default route, which is the address of the Docker host. It writes that address to the file `host-gateway` in the tunnels directory. The orchestrator uses the address as the destination of the service.

The **Services** page shows the service with the container name `homelab`. If the log shows "homelab.ssh waits for the address of the host from the tunnels container", start the `tunnels` container.

### If the connection fails

Check that the container can reach the SSH server of the host. The command prints `open`:

```bash
docker compose exec tunnels bash -c 'timeout 3 bash -c "</dev/tcp/$(cat /tunnels/host-gateway)/22" && echo open'
```

If the command does not print `open`, look at the host:

- `sshd` must listen on an address that the container can reach. The default `ListenAddress` (all addresses) is correct.
- The firewall of the host must accept port 22 from the Docker networks. With `ufw`, run `sudo ufw allow from 172.16.0.0/12 to any port 22 proto tcp`.

### A different service on the host

For a service of the host that is not SSH, use labels on a container, with the address of the host in `homegap.host`. The LAN address of the host works. The name `host.docker.internal` also works, if the `tunnels` service has this entry in `compose.override.yml`:

```yaml
services:
  tunnels:
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

!!! danger "Warning"
    If the front door has a wildcard (`homegap.wildcard`), public DNS also answers for the alias, with the addresses of the nodes. A device that does not resolve the alias through the Pangolin app connects to the SSH server of a node. That server then receives your user name and your password or key. Before you accept a host key, compare its fingerprint with the key of the homelab host.

To read the fingerprint of the host key, run this command on the homelab host:

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```
