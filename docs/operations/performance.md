# Performance

This page tells you which parts of the traffic path set the throughput, and what HomeGAP does to keep it high.

## The path of a request

A web request crosses two TCP connections. The first one is from the viewer to the Traefik of the node. The second one is from that Traefik to the container on the homelab, over the WireGuard mesh. The mesh namespace of the homelab forwards the packets of the second connection. It does not end the connection.

A raw TCP or UDP port has no second connection. The kernel of the node forwards its packets over the mesh. A port with the PROXY protocol goes through the agent of the node.

## TCP tuning on the nodes

With `node_stack.tcp_tuning: true` (the default), HomeGAP writes `/etc/sysctl.d/99-homegap.conf` on each node and applies it. The file has these settings:

| Setting | Value | Effect |
| --- | --- | --- |
| `net.ipv4.tcp_congestion_control` | `bbr` | BBR keeps the throughput high on a long path with packet loss. |
| `net.core.default_qdisc` | `fq` | BBR uses `fq` to pace the packets. |
| `net.core.rmem_max`, `net.core.wmem_max` | 64 MiB | The largest socket buffers. |
| `net.ipv4.tcp_rmem`, `net.ipv4.tcp_wmem` | Up to 64 MiB | The TCP windows can grow to the size that a fast, long path needs. |
| `net.ipv4.tcp_mtu_probing` | `1` | TCP finds a packet size that works when path MTU discovery fails across the mesh. |
| `net.ipv4.tcp_slow_start_after_idle` | `0` | A connection keeps its window after an idle time. |
| `net.ipv4.tcp_reordering`, `net.ipv4.tcp_max_reordering` | `10`, `1000` | Long routes often reorder packets. TCP then does not read that as loss. |

A container does not get the TCP settings of the host. Thus HomeGAP also sets the buffers, the MTU probing, the slow start and the reordering values on the Traefik container of the node. Each connection from a node to a viewer and to the homelab starts in that container.

HomeGAP does not change the TCP settings of the containers on the homelab.

### Check the result

Some VPS kernels refuse BBR or other settings. HomeGAP then writes the warning "some TCP tuning sysctls could not be applied" to the activity log.

The node keeps three facts, which `GET /api/status` shows:

| Fact | What it shows |
| --- | --- |
| `tcp_cc` | The congestion control that the kernel of the node uses. |
| `qdisc` | The default qdisc of the node. |
| `edge_tcp` | The congestion control and the largest send buffer in the Traefik container, for example `bbr 64MiB`. |

The Inspector of a node shows `tcp_cc` and `qdisc` in the **TCP** row.

### Disable the tuning

Set `node_stack.tcp_tuning: false`. HomeGAP then removes the file from the node and the settings from the Traefik container. The values of the host stay until the next restart of the node. The change is a stack update, so it goes to one node at a time.

## VPN traffic

The traffic of a VPN device always goes through a node. The device sends its WireGuard packets to a node on `vpn.port`, and the node forwards them to the homelab over the mesh. There is no direct path from a device to the homelab. Thus the rate of a device has two limits: the path from the device to the node, and the path from the node to the homelab.

With "Exit via `<node>`", the traffic leaves for the internet from that node and does not go to the homelab. See [Exits and tunnel DNS](../vpn/exit-nodes.md).

## Measure the result

To measure the throughput through the real path, use the speed tests. The test between the homelab and a node also shows the CPU load of the two machines. Thus you can see if a CPU or the network is the limit. See [Speed tests](speed-tests.md).
