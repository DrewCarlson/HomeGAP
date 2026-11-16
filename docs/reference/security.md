# Security model

This page tells you where HomeGAP keeps its secrets, what each part can reach and what the public can see.

## The orchestrator is the trust center

The orchestrator holds every SSH key, the Bunny API key and the key that signs the directory of users. Thus:

- Run it on a minimal host.
- Mount the secrets. Do not put them in the image.
- Keep the dashboard private. Every `/api` route needs the dashboard token or a sign-in through `dashboard.sso`, with two exceptions. The terminal WebSocket needs a one-time ticket. The Traefik of the homelab reads `/api/traefik/homelab` from the front door container. Set your own token with `dashboard.auth_token_env`. An address that sends more than 20 wrong tokens in one minute gets the answer `429` until that minute ends. For a request that the front door forwards, the address is the address of the viewer, so one viewer cannot lock a different viewer out. The dashboard loads only its own files, and no other page can show it in a frame.

!!! danger "Warning"
    If somebody gets access to the dashboard or to the orchestrator host, that person has control of every node and of every user.

## Secrets

HomeGAP makes its keys and tokens from the CSPRNG of the kernel. Each key stays in the container that made it:

| Secret | Where it is | Who can read it |
| --- | --- | --- |
| The SSH keys of the nodes, the state cache and the dashboard token | `data/orchestrator` | The orchestrator. |
| The key that signs the directory of users (`authority.key`) | `data/orchestrator/authority` | The orchestrator. |
| The mesh key and the VPN key of the homelab | `data/mesh` (mode 0600) | The mesh agent. |
| The key that signs the sessions (`issuer.key`) | `data/sign-in` | The sign-in. |
| The mesh key of a node | `/etc/wireguard` on the node | Root on the node. |
| The key of the exit of a node | `agent-state` in the install directory of the node | The agent of the node. |
| The CrowdSec bouncer key of a node | The state cache, and `.env` on the node (mode 0600) | The orchestrator and the node. |

The local state file `/data/state.json` has mode 0600. The add-node wizard keeps a setup password in memory for the job only. It never stores it. HomeGAP writes generated node keys to the data directory with mode 0600.

HomeGAP does not keep a password of a user or a secret of a machine. It shows each one time and keeps only a hash.

See [Secrets](../configuration/secrets.md).

## Access to the nodes

The orchestrator controls a node only through SSH. The add-node wizard sends a password only to a host key that you confirmed. After that, HomeGAP connects to a node only if it shows the host key from its first connection.

The dashboard can open a shell on a node. See [The dashboard](../operations/dashboard.md). The shell uses the SSH connection of the orchestrator, so the key stays on the homelab. A WebSocket carries the terminal. It accepts only a single-use ticket from the API, and only from the page of the dashboard. `dashboard.terminal: false` turns the feature off.

`ufw` on the node opens only the ports in [Ports and firewall](ports.md). HomeGAP adds new rules before it removes its old rules. It never touches rules that it did not add. The raw ports, the VPN port and the exit port are in chains of HomeGAP itself, before the rules of `ufw`.

The agent of a node (`homegap-agent`) is in the network of the host. It is root with a read-only file system and four capabilities: `NET_BIND_SERVICE`, `NET_ADMIN`, `NET_RAW` and `DAC_OVERRIDE`. It holds no SSH key, no DNS key and no Docker access.

## The homelab

Each container of the homelab stack has only the rights that its job needs:

| Container | User | Capabilities | Network |
| --- | --- | --- | --- |
| `orchestrator` | The owner of the stack folder | None. | The Traefik network and the network of the Docker proxy. |
| `homegap-sign-in` | The owner of the stack folder | None. Its file system is read-only. | The Traefik network. |
| `homegap-mesh-agent` | Root | `NET_ADMIN`, `NET_RAW`, `DAC_OVERRIDE` | The namespace of `homegap-mesh`. |
| `homegap-mesh` | Root | The defaults of Docker. It runs only `sleep`. | The network `homegap-mesh` and the Traefik network. |
| `docker-proxy` | Root | The defaults of Docker. | The network of the Docker proxy, which has no way out. |

Every container except `homegap-mesh` and `docker-proxy` has `no-new-privileges`.

### The Docker socket

The orchestrator has no Docker socket. It reads Docker through the `docker-proxy` service, which accepts only `GET` requests for the containers and the events. Only the orchestrator can reach the proxy.

!!! danger "Warning"
    Do not mount the Docker socket in the orchestrator. A socket mount gives the full Docker API, also with `:ro`. A person who controls that container then controls the Docker host.

### The sign-in

The sign-in is the part of the homelab that the internet reaches. It is a container of its own, so it has no SSH key, no DNS key and no Docker access. It mounts the config and the directory of users read-only. Only the orchestrator mounts the folder `data/orchestrator`.

### The mesh namespace

Each node is a member of the mesh, so HomeGAP treats the mesh namespace as reachable from the nodes. The orchestrator is not in it. The mesh agent is the only container there that can change the network. It loads the firewall before it starts an interface, so a failure leaves the mesh down and never open. The firewall refuses all traffic, with these exceptions:

- **In:** answers, ping, the WireGuard packets of VPN devices from a node, and the liveness probe of a device.
- **Out:** the mesh, answers, and WireGuard to the nodes. Nothing in the namespace can open a connection to the orchestrator, to a different container or to your home network.
- **From a node:** only the ports of the published services, each to its one container.
- **From a VPN device:** only the private services that a role of its user permits. A rule names the address of the device, and WireGuard accepts that address only from the key of the device.

A device that uses "Exit via homelab" reaches the internet from the namespace. It reaches no private address range and not the mesh. See [Mesh](mesh.md).

HomeGAP never publishes the IP address of the homelab. The homelab dials the mesh, so it has no inbound port from the internet.

## Users and sign-in

The orchestrator is the authority for users. It keeps the users, the machines, the roles and the block lists, and it signs them as one directory. The sign-in and each node trust only a directory with that signature, and they never accept an older version.

- **Passwords:** HomeGAP stores a password as a PBKDF2-SHA256 hash with 600,000 iterations and its own salt. A password has 12 characters or more.
- **Secrets of machines and devices:** HomeGAP stores only the SHA-256 of each secret.
- **Sessions:** the sign-in signs each session token with its own key. The directory names that key, and only such a session gives access to the homelab. A session of a user lasts 30 days. The token for the control connection of a device lasts 7 days.
- **Limits:** after five wrong passwords in 15 minutes, the sign-in refuses the address of the browser and the email address. A sign-in code lasts 10 minutes.
- **Blocks:** a block of a user or a device goes into the signed directory. The session of a blocked user gives no access, and the device loses its connection.

With `sign_in.oidc`, the provider proves the email address of the user, with its own second factor. The user must exist in HomeGAP, and HomeGAP gives the roles. See [Sign-in](../vpn/sign-in.md).

## What a node holds and can do

A node is a server at a provider, so HomeGAP gives it as little as possible. A node holds:

- The certificate of the nodes and its key, for the names that the nodes terminate.
- Its own mesh key and, with `vpn.exit_nodes.nodes`, the key of its exit.
- The public key of the homelab.
- With `vpn.exit_nodes.nodes`, the signed directory and the list of devices. They contain the password hashes of the users and the hashes of the secrets.

A node holds no SSH key, no Bunny key and no key that signs for the homelab. It cannot make a user, a role or a session that the homelab accepts. Into the homelab, it reaches only the ports of the published services.

During an outage of the homelab, a node with an exit signs devices in itself, with a HomeGAP password. It signs such a session with its own key, which the directory does not name. Thus the session gives the exit of that node and nothing at home. The firewall of the node accepts this sign-in only from a Docker network of the node, where its Traefik is. A block during an outage reaches a node only after the homelab is back.

!!! danger "Warning"
    A node ends TLS for the name of the sign-in. A person with root on a node can read the passwords that users type there. That person can also read the session tokens of the devices that connect through it. With a token, that person can act as its user, with the rights of that user, until you block the user. The password hashes on a node are also open to an offline attack. Use `sign_in.oidc` with a second factor, and rent nodes only from providers that you trust.

A node cannot read the VPN traffic to the homelab. WireGuard encrypts it from the device to the mesh namespace, and the node only forwards the packets.

## Images

The default images of Traefik, CrowdSec and the maintenance page have a version tag. A node gets a new version of them only when the default or your config changes. The agent of a node runs the HomeGAP image. Its tag is the value of `HOMEGAP_VERSION`. Without that variable, the tag is the version of the orchestrator. See [Node updates and rollback](../operations/node-updates.md).

The release build signs the HomeGAP image. HomeGAP does not check a signature before it uses an image. To check the image yourself, see [Upgrade the homelab](../operations/upgrade.md).

## Traffic

With `passthrough`, a node cannot read the traffic. It holds no certificate for a passthrough name. With the CAA records of the domain, it cannot order one. A node can see the name, the address of the client, and the size and time of the traffic.

A node can read the traffic of each name that it terminates. These are the name of the sign-in, the shared names of the status page and the speed test, and each service with `homegap.tls: terminate`. A front door with `terminate` and `homegap.wildcard` gives the nodes all web traffic of the domain. See [Certificates](../configuration/certificates.md).

The nodes send an HSTS header on each name that they terminate. A browser then uses only HTTPS for that name for one year.

`node_stack.crowdsec` gives abuse mitigation on the edge for terminated traffic only. It cannot examine passthrough streams.

## Calls to outside services

The software that HomeGAP runs makes no calls home of its own. On the Traefik of each node, HomeGAP turns off the version check and the usage data.

The system needs the calls that remain:

- The orchestrator calls the Bunny API for the DNS records.
- The orchestrator orders the certificate of the nodes from Let's Encrypt. The nodes order no certificates.
- With `sign_in.oidc`, the sign-in calls your provider.
- With `node_stack.crowdsec`, Traefik on the nodes downloads the CrowdSec bouncer plugin from the Traefik plugin catalog when it starts. CrowdSec talks to its central API for the community blocklists.

You configure your own Traefik on the homelab. Set `global.checkNewVersion` and `global.sendAnonymousUsage` to `false` in its static configuration to turn off the same calls there.

## What the public can see

- The public status page shows the id, the region, the state and the location of each node. It also uses the name `status-<node>.<domain>` of each node, which resolves to the public address of that node. It shows the homelab only as its region. Only a VPN device, which gets the page over the private path, sees the location of the homelab. Each visitor sees only the address of that visitor. See [Status page](../operations/status-page.md).
- The token of a speed test link for a device is in the fragment of the URL. Thus it is never part of a request URL, and no access log contains it. The test page sends it in a header. With `speedtest.vpn_access: true`, each VPN device that can use the private path of the front door gets a link with no action from you. See [Speed tests](../operations/speed-tests.md).
- The nodes end TLS for the shared names `status.<domain>` and `speedtest.<domain>`, which serve only these HomeGAP pages. A node then sends each request back into its own port 443 under its own name, and the front door carries it home still encrypted. On that inner connection the node does not check the certificate, because the connection does not leave the node before it enters the mesh.

## Report a security issue

To report a security issue, open a private security advisory on GitHub at `https://github.com/DrewCarlson/HomeGAP/security/advisories/new`. Do not open a public issue.
