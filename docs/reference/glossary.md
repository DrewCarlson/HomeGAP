# Glossary

This page defines the terms that the HomeGAP docs use.

**Agent of a node**
: The `homegap-agent` container on a node (`homegap node-agent`). It serves the raw TCP ports with PROXY protocol and the exit of the node. During an outage of the homelab, it also serves the sign-in.

**Bunny DNS**
: The DNS service of bunny.net. It sends each viewer to the nearest healthy node. See [DNS routing and health checks](../configuration/dns.md).

**Desired state**
: The Docker labels and the config file, and nothing else. The orchestrator moves the actual state toward it. See [How HomeGAP works](../getting-started/how-it-works.md).

**Device**
: One app that signed in, or one machine. A device has a WireGuard key and an address in the VPN. You can block, unblock or delete it. See [Users, machines and devices](../vpn/access.md).

**Directory**
: The list of users, machines, roles, private names and blocks. The orchestrator signs it, and the sign-in and the nodes trust only a directory with that signature. See [Security model](security.md#users-and-sign-in).

**Drift**
: A change on a node that HomeGAP did not make. The node check repairs it. See [Node updates and rollback](../operations/node-updates.md).

**Exit**
: A way to the internet for a VPN device. `Exit via <node>` leaves with the address of that node, and `Exit via homelab` leaves from home. See [Exit nodes and tunnel DNS](../vpn/exit-nodes.md).

**Extra domain**
: A domain for services in addition to `domain`, from `extra_domains` in the config file. See [Extra domains](../services/extra-domains.md).

**Front door**
: The homelab Traefik container with the root domain in `homegap.expose`. It claims the root and, with `homegap.wildcard`, `*.<domain>`. Each extra domain can have its own front door. See [Services](../services/index.md).

**Geo record set**
: A set of DNS records for one name, one for each node, with geo routing and a health monitor. See [DNS routing and health checks](../configuration/dns.md).

**Homelab**
: The home servers with Docker, the homelab Traefik and the services. It has no inbound ports.

**Machine**
: A device with no user, for example a server that needs access. It signs in with an id and a secret. See [Users, machines and devices](../vpn/access.md).

**Maintenance page**
: The page that a node shows while it cannot reach the homelab. The `homegap-maintenance` container of the node serves it.

**Management host**
: The name of one node, `node-<id>.<domain>`. See [Manage nodes](../operations/nodes.md).

**Mesh**
: The private WireGuard network `hg-mesh` between the homelab and every node. It is always on, and every route from a node to the homelab goes over it. See [Mesh](mesh.md).

**Mesh agent**
: The `homegap-mesh-agent` container on the homelab (`homegap mesh`). It keeps the mesh interface, the home end of the VPN and the firewall of the mesh namespace.

**Mesh namespace**
: The network namespace of the `homegap-mesh` container on the homelab. The mesh interface and the interface of the VPN devices are there, and the orchestrator is not. See [Security model](security.md#the-mesh-namespace).

**Named service**
: A service under `homegap.services.<name>.` on a container that can also have other services. Its key is `<host slug>--<name>`. See [Labels](../services/labels.md).

**Node**
: A VPS that runs Traefik, the maintenance page and the agent of HomeGAP. Node ids are like `na1`, `eu1` and `as1`. See [Prepare the servers](../getting-started/servers.md).

**Olm**
: The command line client of the Pangolin apps. A machine connects with it.

**Orchestrator**
: The `homegap run` process on the homelab. It reads the labels and manages the nodes, the mesh, the users and DNS. It is never in the traffic path of your services. It serves only its own pages: the dashboard and, if enabled, the status page and the speed test.

**Pangolin apps**
: The unmodified Pangolin apps for Android, iOS and desktop. A user connects a device to the VPN with one of them. See [Connect the Pangolin apps](../vpn/pangolin-apps.md).

**Pass**
: One run of the reconcile loop. See [How HomeGAP works](../getting-started/how-it-works.md).

**Passthrough**
: The TLS mode where the node reads only the SNI and forwards the encrypted stream to the homelab. See [Traffic paths](../services/traffic-paths.md).

**Port list**
: A list of raw ports in `homegap.port`, with entries like `7881/tcp`, `3478/tcp+udp` or `2222:22/tcp`. See [Labels](../services/labels.md).

**Private path**
: A route that only VPN devices use to reach a public service, over a separate Docker network. See [Client addresses and private-only services](../services/client-addresses.md).

**Private service**
: A service with `homegap.access: private`. It gets a name that only VPN devices resolve and reach. See [Private services](../services/private.md).

**Role**
: A name that connects users and machines to private services and exits. `Admin` and `Member` always exist, and you can name more. A private service with no `homegap.roles` label is for `Admin` only.

**Rollout**
: An update of the node stacks, one node at a time. See [Node updates and rollback](../operations/node-updates.md).

**Sign-in**
: The `homegap-sign-in` container on the homelab (`homegap sign-in`). A user signs in there with a HomeGAP password or through an OpenID Connect provider. `sign_in.url` is its address, and each node serves it. See [Sign-in](../vpn/sign-in.md).

**Slot**
: The permanent number of a node. It gives the mesh address of the node. See [Manage nodes](../operations/nodes.md).

**Terminate**
: The TLS mode where the node ends TLS, with the certificate that HomeGAP orders for the nodes. See [Traffic paths](../services/traffic-paths.md).

**Tunnel DNS**
: The DNS servers of `vpn.tunnel_dns`. An app with "DNS over tunnel" sends its lookups to them through the VPN. See [Exit nodes and tunnel DNS](../vpn/exit-nodes.md).

**User**
: A person who can sign in. A user has an email address, a name, roles and an optional password. See [Users, machines and devices](../vpn/access.md).

**VPN**
: The WireGuard connection of a device to the homelab. The device connects to a node, and the node forwards the packets over the mesh. The node cannot read them.
