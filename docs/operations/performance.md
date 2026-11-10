# Performance

Each request crosses two TCP legs (viewer to node, and node to homelab) and one WireGuard tunnel. This page tells you which settings have the largest effect on throughput.

## The three main settings

| Setting | Effect |
| --- | --- |
| `pangolin.tcp_tuning: true` (default) | Writes `/etc/sysctl.d/99-homegap.conf` on each node. See [TCP tuning on the nodes](#tcp-tuning-on-the-nodes). |
| `tunnels.mtu: 1420` | Sets the WireGuard MTU on both ends: Newt `--mtu` and Gerbil `--mtu`. The upstream default of 1280 decreases throughput. |
| `tunnels.native: true` | Runs each main tunnel of Newt on a kernel WireGuard interface, instead of the userspace network stack of Newt. This gives the largest gain in most setups. |

### TCP tuning on the nodes

The file sets BBR congestion control, the `fq` qdisc, socket buffers up to 64 MiB, MTU probing and a higher tolerance for reordering. BBR keeps long paths with packet loss fast.

Docker containers do not inherit the TCP settings of the host. Thus HomeGAP also applies the same settings in the network namespace of Gerbil and Traefik on the node. The **Nodes** page shows them as `edge tcp`.

The node fact `tcp_cc` shows the congestion control that the kernel really uses. Some VPS kernels refuse BBR.

### The MTU

If the path has PPPoE or another tunnel, use a lower MTU. To check a value, run this command from the homelab. Replace `<mtu - 28>` with the MTU minus 28:

```bash
ping -M do -s <mtu - 28> 203.0.113.10
```

### Native mode

With `tunnels.native: true`, the `tunnels` container needs:

- `cap_add: [NET_ADMIN]`.
- `devices: ["/dev/net/tun:/dev/net/tun"]`.
- WireGuard in the kernel of the host.

## The homelab side in native mode

In native mode, tune the `tunnels` container itself. TCP settings apply to each network namespace, so the `/etc/sysctl.d` of the host does not reach containers. The defaults (cubic and a 4 MiB send buffer) limit every download from the homelab to a distant node.

Set the sysctls on the `tunnels` service:

```yaml
sysctls:
  net.ipv4.tcp_congestion_control: bbr   # needs `modprobe tcp_bbr` on the host
  net.ipv4.tcp_rmem: "4096 131072 67108864"
  net.ipv4.tcp_wmem: "4096 65536 67108864"
  net.ipv4.tcp_slow_start_after_idle: "0"
  net.ipv4.tcp_mtu_probing: "1"
  net.ipv4.tcp_reordering: "10"          # tolerate reordering on long routes
  net.ipv4.tcp_max_reordering: "1000"
```

!!! warning "Caution"
    For `bbr`, the host must load the `tcp_bbr` module (`modprobe tcp_bbr`). Without it, the container does not start.

To check the settings, run this command:

```bash
docker compose exec tunnels sysctl net.ipv4.tcp_congestion_control net.ipv4.tcp_wmem
```

On a path with a round trip of approximately 170 ms, these settings took one setup from approximately 115 Mbps to approximately 250 Mbps.

Also enable `fq` on the host (`net.core.default_qdisc = fq`). BBR uses it for pacing.

## Userspace mode

In userspace mode, Newt uses its own TCP stack, so these settings do not apply. A higher `net.core.rmem_max` and `net.core.wmem_max` on the host (for example `7500000`) still helps its UDP socket.

## Measure the result

To measure the throughput through the real path, use the speed tests. See [Speed tests](speed-tests.md).
