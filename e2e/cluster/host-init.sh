#!/bin/sh
# A member's "host": brings up the WireGuard mesh (mesh0) and a firewall that admits only the
# mesh's UDP port from the other members' public addresses, so every member-to-member packet of
# the cluster (PostgreSQL, Patroni, etcd, Valkey, Gerbil's API) must travel inside the mesh.
# Then holds the namespace for the member's services.
set -eu
. /node/host.env
apk add -q wireguard-tools iptables >/dev/null 2>&1
ip link add mesh0 type wireguard
wg setconf mesh0 /node/mesh0.conf
ip addr add "$MESH_IP/24" dev mesh0
ip link set mesh0 up
for peer in $PEER_PUBLICS; do
  iptables -A INPUT -s "$peer" -p udp --dport 51821 -j ACCEPT
  iptables -A INPUT -s "$peer" -j DROP
done
echo "mesh0 up at $MESH_IP; only udp/51821 accepted from: $PEER_PUBLICS"
exec sleep infinity
