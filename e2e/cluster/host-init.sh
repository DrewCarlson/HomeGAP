#!/bin/sh
# A node's "host": brings up the WireGuard mesh (mesh0) and a firewall that admits only the mesh's
# UDP port from the other node, so every node-to-node packet of the cluster (PostgreSQL, Valkey,
# Gerbil's API) must travel inside the mesh. Then holds the namespace for the node's services.
set -eu
. /node/host.env
apk add -q wireguard-tools iptables >/dev/null 2>&1
ip link add mesh0 type wireguard
wg setconf mesh0 /node/mesh0.conf
ip addr add "$MESH_IP/24" dev mesh0
ip link set mesh0 up
iptables -A INPUT -s "$PEER_PUBLIC" -p udp --dport 51821 -j ACCEPT
iptables -A INPUT -s "$PEER_PUBLIC" -j DROP
echo "mesh0 up at $MESH_IP; only udp/51821 accepted from $PEER_PUBLIC"
exec sleep infinity
