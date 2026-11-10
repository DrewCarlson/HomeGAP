# Label reference

This page lists every `homegap.*` label, with its values, its default and its rules.

## Values

Label values are text. For a boolean label, use `true`, `1`, `yes` or `on`, or `false`, `0`, `no` or `off`. The case is not important. Any other value gives the error `<key> must be true/false`.

For a label with a fixed set of values, any other value gives the error `<key> must be one of ... (got '<value>')`.

## Core labels

| Label | Values | Default | What it does |
| --- | --- | --- | --- |
| `homegap.expose` | A hostname. | None. This label is necessary. | Sets the hostname to expose, for example `app.example.net`, or the root `example.net`. HomeGAP changes it to lowercase. |
| `homegap.wildcard` | Boolean. | `false` | On the root entry, also claims `*.<domain>`. |
| `homegap.protocol` | `http`, `https`, `tcp`, `udp` | `http` | Sets the protocol of the service. |
| `homegap.port` | 1 to 65535. | `443` for `https`, `80` for `http`. | Sets the port of the container. It is necessary for `tcp` and `udp`. |
| `homegap.tls` | `passthrough`, `terminate` | `passthrough` | With `passthrough`, the node sees only the SNI. With `terminate`, the node ends TLS, and Pangolin sign-in is possible. |
| `homegap.regions` | Node ids, separated by commas. | `all` | Limits the service to these nodes, for example `na1,as1`. The value `all` or an empty value selects every node. |
| `homegap.access` | `public`, `private` | `public` | With `private`, only the Pangolin client can reach the service. |
| `homegap.auth` | `none`, `sso`, `pin` | `none` | Puts Pangolin sign-in in front of the service. |
| `homegap.healthcheck` | An HTTP path that starts with `/`, or `tcp`. | None. | Sets the health check of the Pangolin target. On the front door, it also sets the path of the health resource that Bunny probes. |

### Rules for the core labels

- `homegap.expose` must be a valid hostname. It must be inside `domain`, or you get the error "... is outside the managed domain ...".
- `homegap.wildcard` is valid only when `homegap.expose` is the root domain. It is an error on `tcp` and `udp` services.
- The node always terminates plain `http`. If you set `homegap.tls: passthrough` on an `http` service, you get the warning `plain-http services cannot be passed through; the node terminates TLS (tls=terminate)`.
- On `tcp` and `udp` services, `homegap.tls` has no effect. HomeGAP gives a warning.
- An unknown node id in `homegap.regions` gives the warning "regions reference unknown node(s): ...".
- `homegap.auth` needs `homegap.tls: terminate`. Without it, you get the error "...requires homegap.tls=terminate (the node cannot authenticate encrypted traffic)".
- `homegap.auth: pin` needs `homegap.pin_env`.
- Without `homegap.healthcheck`, Bunny still probes `/` through the front door. Raw UDP services ignore this label.

## Extension labels

| Label | Default | What it does |
| --- | --- | --- |
| `homegap.host` | The container name. | Sets the host that the tunnel connects to. The container name must resolve on a network that the container shares with `tunnels`. |
| `homegap.public_port` | The value of `homegap.port`. | Sets the public port on the nodes, for `tcp` and `udp` only. |
| `homegap.roles` | None. | Lists the Pangolin roles, separated by commas, that can use `auth=sso`, a private resource or a private path. |
| `homegap.pin_env` | None. | Names an environment variable on the orchestrator that holds the 6-digit PIN for `auth=pin`. |
| `homegap.pause` | `false` | With `true`, HomeGAP keeps the Pangolin resources and DNS records of this service as they are. It observes them, but it never changes them. |
| `homegap.proxy_protocol` | `false` | Sends the address of each viewer to the service with PROXY protocol v2. |
| `homegap.private_network` | None. | Names a Docker network that only `tunnels` and this service share. Pangolin clients reach the service at its address on that network. |
| `homegap.private_hosts` | The hostname of the service, if `homegap.private_middleware` is not set. | Lists the names, separated by commas, that Pangolin clients reach through the private network. |
| `homegap.private_middleware` | None. | Gives a private path to each name that this Traefik routes through this middleware, for example `private-only@file`. |
| `homegap.traefik_api` | `http://<container name>:8080` | Sets the URL of the API of this Traefik, for `homegap.private_middleware`. |

### Rules for the extension labels

- `homegap.public_port` on an `http` or `https` service gives the warning "... only applies to tcp/udp".
- The node uses these ports itself: `22`, `80`, `443`, `3000`, `3001`, `3002`, `3003`, `3004`, `3005`, `8080`, `21820` and `51820`. A public `tcp` or `udp` service cannot use them. The error is `public port N is reserved on nodes; set homegap.public_port`.
- HomeGAP removes `Admin` from the roles that it sends to Pangolin, because Pangolin refuses it there. The `Admin` role can reach everything.
- The PIN must be a number from 100000 to 999999. If there is no PIN, or the PIN is not valid, HomeGAP writes an error to the log.
- `homegap.proxy_protocol` works only on `https` services in `passthrough` mode and on `tcp` services, and only on public services. See [Client addresses and private-only services](client-addresses.md).
- `homegap.private_network` works only on public `http` and `https` services.
- `homegap.private_hosts` and `homegap.private_middleware` need `homegap.private_network`.
- A name in `homegap.private_hosts` can be relative to the hostname of the service (`jellyfin`, `*.home`) or a full name. You can use `*` and `?`.
- `homegap.private_middleware` compares the middleware without its `@provider` part. HomeGAP reads the routers from the Traefik API.

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
