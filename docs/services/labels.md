# Label reference

This page lists every `homegap.*` label, with its values, its default and its rules. It also tells you how to declare several services on one container.

## Values

Label values are text. For a boolean label, use `true`, `1`, `yes` or `on`, or `false`, `0`, `no` or `off`. The case is not important. Any other value gives the error `<key> must be true/false`.

For a label with a fixed set of values, any other value gives the error `<key> must be one of ... (got '<value>')`.

## Core labels

| Label | Values | Default | What it does |
| --- | --- | --- | --- |
| `homegap.expose` | A hostname. | None. Each service needs it. | Sets the hostname to expose, for example `app.example.net`, or the root `example.net`. HomeGAP changes it to lowercase. |
| `homegap.wildcard` | Boolean. | `false` | On the root entry, also claims `*.<domain>`. |
| `homegap.protocol` | `http`, `https`, `tcp`, `udp`, `tcp+udp` | `http`, or raw if the port list has protocols. | Sets the protocol of the service. For a raw service, it is the default protocol of the ports. |
| `homegap.port` | A port from 1 to 65535. For raw services, a port list. | `443` for `https`, `80` for `http`. | Sets the port of the container. Raw services need it. See [Port lists](#port-lists). |
| `homegap.tls` | `passthrough`, `terminate` | `passthrough` | With `passthrough`, the node sees only the SNI. With `terminate`, the node ends TLS, and Pangolin sign-in is possible. |
| `homegap.regions` | Node ids, separated by commas. | `all` | Limits the service to these nodes, for example `na1,as1`. The value `all` or an empty value selects every node. |
| `homegap.access` | `public`, `private` | `public` | With `private`, only the Pangolin client can reach the service. |
| `homegap.auth` | `none`, `sso`, `pin` | `none` | Puts Pangolin sign-in in front of the service. |
| `homegap.maintenance_page` | Boolean. | `true` with `terminate`. | While the homelab is unreachable, the nodes show a "back shortly" page for the service. See [Traffic paths](traffic-paths.md#when-the-homelab-is-down). |
| `homegap.healthcheck` | An HTTP path that starts with `/`, or `tcp`. | None. | Sets the health check of the Pangolin target. On the front door, it also sets the path of the health resource that Bunny probes. |

### Rules for the core labels

- `homegap.expose` must be a valid hostname. It must be inside `domain`, or you get the error "... is outside the managed domain ...".
- `homegap.wildcard` is valid only when `homegap.expose` is the root domain or the root of an [extra domain](extra-domains.md). It is an error on raw services.
- The node always terminates plain `http`. If you set `homegap.tls: passthrough` on an `http` service, you get the warning `plain-http services cannot be passed through; the node terminates TLS (tls=terminate)`.
- On raw services, `homegap.tls` has no effect. HomeGAP gives a warning.
- An unknown node id in `homegap.regions` gives the warning "regions reference unknown node(s): ...".
- `homegap.auth` needs `homegap.tls: terminate`. Without it, you get the error "...requires homegap.tls=terminate (the node cannot authenticate encrypted traffic)".
- `homegap.auth: pin` needs `homegap.pin_env`.
- `homegap.maintenance_page` needs `homegap.tls: terminate`. A passthrough service has no page, because the node holds no certificate for its name. If you set the label to `true` on a passthrough service, you get the error "...needs homegap.tls=terminate...".
- If the front door has `homegap.tls: terminate` and `homegap.wildcard`, HomeGAP refuses `homegap.tls: passthrough` on another hostname one label under the domain. See [Certificates](../configuration/certificates.md#the-front-door-in-terminate-mode-with-a-wildcard).
- Without `homegap.healthcheck`, Bunny still probes `/` through the front door. On raw services, the label applies only to the TCP ports.
- An HTTP path in `homegap.healthcheck` gives an HTTP check only on a service that the node terminates. On a passthrough service or a raw TCP port, the check of the Pangolin target is a TCP check.
- `homegap.maintenance_page: true` on a raw service gives the warning "... has no effect on raw ... services".

## Extension labels

| Label | Default | What it does |
| --- | --- | --- |
| `homegap.host` | The container name. | Sets the host that the tunnel connects to. The container name must resolve on a network that the container shares with `tunnels`. |
| `homegap.public_port` | The value of `homegap.port`. | Sets the public port on the nodes, for a raw service with a single port. |
| `homegap.roles` | None. | Lists the Pangolin roles, separated by commas, that can use `auth=sso`, a private resource or a private path. |
| `homegap.pin_env` | None. | Names an environment variable on the orchestrator that holds the 6-digit PIN for `auth=pin`. |
| `homegap.pause` | `false` | With `true`, HomeGAP keeps the Pangolin resources and DNS records of this service as they are. It observes them, but it never changes them. |
| `homegap.proxy_protocol` | `false` | Sends the address of each viewer to the service with PROXY protocol v2. |
| `homegap.private_network` | None. | Names a Docker network that only `tunnels` and this service share. Pangolin clients reach the service at its address on that network. |
| `homegap.private_hosts` | The hostname of the service, if `homegap.private_middleware` is not set. | Lists the names, separated by commas, that Pangolin clients reach through the private network. |
| `homegap.private_middleware` | None. | Gives a private path to each name that this Traefik routes through this middleware, for example `private-only@file`. |
| `homegap.traefik_api` | `http://<container name>:8080` | Sets the URL of the API of this Traefik, for `homegap.private_middleware`. The form is `http://<host>[:<port>]` or `https://<host>[:<port>]`, with no path. The host must be a container name or a private IPv4 address. For other values, HomeGAP gives a warning and uses the default. |

### Rules for the extension labels

- `homegap.public_port` on an `http` or `https` service gives the warning "... only applies to tcp/udp".
- A hostname that HomeGAP routes itself is an error. These are the management host of a node, the client address of the cluster, and the names of the status page and the speed test. The error is `hostname N is reserved for HomeGAP`.
- With more than one port, or with an entry that has its own `public:` part, `homegap.public_port` is an error. Write `public:port` in `homegap.port` instead.
- The node uses these ports itself: `22`, `80`, `443`, `2379`, `2380`, `3000`, `3001`, `3002`, `3003`, `3004`, `3005`, `5432`, `5433`, `6379`, `6380`, `8008`, `8080`, `8082`, `8090`, `21820`, `26379` and `51820`. A public raw service cannot use them as public ports. The error is `public port N is reserved on nodes; publish it on another port (public:port in homegap.port, or homegap.public_port)`. In cluster mode, `cluster.mesh.port`/udp is reserved too. HomeGAP does not check private services, because they publish nothing on the nodes.
- HomeGAP removes `Admin` from the roles that it sends to Pangolin, because Pangolin refuses it there. The `Admin` role can reach everything.
- The PIN must be a number from 100000 to 999999. If there is no PIN, or the PIN is not valid, HomeGAP writes an error to the log.
- `homegap.proxy_protocol` works only on `https` services in `passthrough` mode and on raw services with TCP ports, and only on public services. On each other service, it is an error. On a raw service, it applies only to the TCP ports. See [Client addresses and private-only services](client-addresses.md).
- `homegap.private_network` works only on public `http` and `https` services. On each other service, it is an error.
- `homegap.private_hosts` and `homegap.private_middleware` need `homegap.private_network`.
- A name in `homegap.private_hosts` can be relative to the hostname of the service (`jellyfin`, `*.home`). It can also be a full name, if that name is the hostname of the service or a name under it. HomeGAP reads each other name as a relative name. You can use `*` and `?`.
- `homegap.private_middleware` compares the middleware without its `@provider` part. HomeGAP reads the routers from the Traefik API.

## Port lists

For a raw service, `homegap.port` takes a list of entries, separated by commas. Each entry has this form:

```text
[public:]port[/tcp|/udp|/tcp+udp]
```

| Entry | Meaning |
| --- | --- |
| `7881` | Port 7881, with the protocol from `homegap.protocol`. |
| `7881/tcp` | Port 7881 on TCP. |
| `3478/tcp+udp` | Port 3478 on TCP and on UDP. HomeGAP also reads `udp+tcp` as `tcp+udp`. |
| `2222:22/tcp` | Public port 2222 on the nodes, to port 22 of the container, on TCP. |

These rules apply:

- A list with a protocol on an entry is a raw service, also without `homegap.protocol`. For example, `homegap.port: "7881/tcp, 7882/udp"` is enough.
- `homegap.protocol` gives the protocol of the entries without their own protocol. With `tcp+udp`, each such port is on TCP and UDP. The protocol of an entry has priority.
- Without `homegap.protocol`, each entry needs a protocol if one entry has one. Otherwise, you get the error `'<entry>' needs a protocol (/tcp, /udp or /tcp+udp), or set homegap.protocol`.
- An `http` or `https` service takes only one port number. A list gives the error `must be one port number for http services`, or `for https services`.
- An entry that is not a port from 1 to 65535 gives an error with examples of valid entries.
- The same public port and protocol twice in one service gives the error `is listed twice`.

The protocol of the service is the sum of its ports: `tcp`, `udp` or `tcp+udp`. The **Services** page shows the port list in the **Published as** column, for example `7881/tcp, 7882/udp`. See [Raw TCP and UDP](raw-ports.md).

## Named services

One container can declare several services. Put the labels of each service under `homegap.services.<name>.`, for example `homegap.services.alt.expose` and `homegap.services.alt.port`. Every label works under this prefix, for example `tls`, `access`, `auth`, `healthcheck`, `proxy_protocol` and `private_network`.

These rules apply:

- The name has lowercase letters, digits and dashes. It starts and ends with a letter or a digit, and it has 31 characters or fewer (`^[a-z0-9]([a-z0-9-]{0,29}[a-z0-9])?$`).
- Each named service needs its own `expose`. Without it, you get the error `services.<name>: homegap.expose is required`.
- The top-level labels of the container are still a service of their own, when `homegap.expose` is set.
- A named service takes these top-level labels as its defaults: `homegap.host`, `homegap.regions`, `homegap.access`, `homegap.roles` and `homegap.pause`. It can set its own values.
- The errors and warnings of a named service start with `services.<name>:`. An error drops only that service. The other services of the container still publish.
- An unknown label under a named service gives a warning with the full name of the label.
- The key of a named service is `<host slug>--<name>`. HomeGAP uses this key for pause, on the dashboard and in the DNS purpose. The **Services** page shows the container as `<container> · <name>`.

## Shared hostnames and ports

When two services claim the same thing, the first container in the order of names wins. HomeGAP refuses the later one with an error:

- Only one `http` or `https` service can use a hostname.
- Raw services can share a hostname with each other, and with one `http` or `https` service. DNS has one record set for the hostname, on every node that serves any of these services.
- Two top-level services on one hostname have the same key. HomeGAP refuses the second one with the error `...already claimed by <container>; give this service a name under homegap.services.`. Give the second service a name.
- On the nodes that two public services share (by `homegap.regions`), only one service can use a public port and protocol. The error is `public port 7882/udp is already used by <container> (<name>)`. On different nodes, the same port is valid.
- A raw service on the root domain is never the front door.

## Examples

The front door claims the root and the wildcard, with end-to-end TLS:

```yaml
traefik:
  labels:
    homegap.expose: "example.net"
    homegap.wildcard: "true"
    homegap.protocol: "https"
    homegap.tls: "passthrough"
```

A private raw TCP service for SSH, on its own subdomain, on two nodes:

```yaml
ssh-jump:
  labels:
    homegap.expose: "ssh.example.net"
    homegap.port: "22"
    homegap.protocol: "tcp"
    homegap.access: "private"
    homegap.regions: "na1,as1"
```

A public game server on UDP port 27015, in one region:

```yaml
game:
  labels:
    homegap.expose: "game.example.net"
    homegap.protocol: "udp"
    homegap.port: "27015"
    homegap.regions: "eu1"
```

A voice server with a port list, and a named service on the same hostname with a different public port:

```yaml
rtc:
  labels:
    homegap.expose: "rtc.example.net"
    homegap.port: "7881/tcp, 7882/udp, 3478/tcp+udp"
    homegap.services.alt.expose: "rtc.example.net"
    homegap.services.alt.port: "17000:7000/udp"
```
