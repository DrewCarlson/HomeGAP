#!/bin/sh
# A HomeGAP cluster member's process, one role per container: etcd, patroni, valkey, sentinel or
# haproxy. The HomeGAP orchestrator writes each role's config into $HG_CONFIG (default /config);
# until it exists, the role waits, so the containers can be started before the orchestrator has
# rendered anything. Passwords stay in files, never in process arguments.
set -eu
ROLE=${1:?usage: hg-member etcd|patroni|valkey|sentinel|haproxy}
CONFIG=${HG_CONFIG:-/config}

wait_for() {
  until [ -s "$1" ]; do
    echo "hg-member $ROLE: waiting for $1 (written by the HomeGAP orchestrator)"
    sleep 5
  done
}

# Nightly logical backups of the cluster database, newest seven kept (homelab member only).
backup_loop() {
  install -d -o postgres -g postgres -m 0700 "$HG_BACKUP_DIR"
  sleep 300
  while true; do
    out="$HG_BACKUP_DIR/homegap-$(date -u +%Y%m%dT%H%M%SZ).dump"
    if gosu postgres pg_dump -h /var/run/postgresql -U postgres -Fc -f "$out.part" postgres; then
      mv "$out.part" "$out"
      ls -1t "$HG_BACKUP_DIR"/homegap-*.dump 2>/dev/null | tail -n +8 | xargs -r rm -f
    else
      rm -f "$out.part"
    fi
    sleep 86400
  done
}

# Services bind the member's mesh address, which exists only once the mesh interface is up (the
# homelab's after the orchestrator configures it, a node's after wg-quick at boot).
if [ "$ROLE" != haproxy ]; then
  wait_for "$CONFIG/mesh.env"
  . "$CONFIG/mesh.env"
  until python3 -c "import socket,sys; socket.socket().bind((sys.argv[1], 0))" "$HG_MESH_IP" 2>/dev/null; do
    echo "hg-member $ROLE: waiting for the mesh address $HG_MESH_IP"
    sleep 3
  done
fi

case "$ROLE" in
  etcd)
    wait_for "$CONFIG/etcd.env"
    set -a
    . "$CONFIG/etcd.env"
    set +a
    exec etcd
    ;;
  patroni)
    wait_for "$CONFIG/patroni.yml"
    install -d -o postgres -g postgres -m 0700 /var/lib/postgresql/data
    install -o postgres -g postgres -m 0600 "$CONFIG/patroni.yml" /tmp/patroni.yml
    if [ -n "${HG_BACKUP_DIR:-}" ]; then backup_loop & fi
    exec gosu postgres patroni /tmp/patroni.yml
    ;;
  valkey)
    # Never start as a second master: follow whichever master the Sentinels report, and only
    # fall back to the configured initial master when no Sentinel knows one (a new cluster).
    wait_for "$CONFIG/valkey.env"
    . "$CONFIG/valkey.env"
    master=""
    for s in $HG_SENTINELS; do
      master=$(timeout 3 valkey-cli -h "$s" -p 26379 SENTINEL get-master-addr-by-name homegap 2>/dev/null | head -n 1) || master=""
      if [ -n "$master" ]; then break; fi
    done
    [ -n "$master" ] || master=$HG_INITIAL_MASTER
    umask 077
    {
      echo "bind $HG_MESH_IP 127.0.0.1"
      echo "port 6379"
      echo "dir ${HG_VALKEY_DIR:-/data}"
      echo "requirepass $HG_PASSWORD"
      echo "masterauth $HG_PASSWORD"
      echo "replica-announce-ip $HG_MESH_IP"
      echo "replica-priority $HG_PRIORITY"
      if [ "$master" != "$HG_MESH_IP" ]; then echo "replicaof $master 6379"; fi
    } > /tmp/valkey.conf
    echo "hg-member valkey: starting $( [ "$master" = "$HG_MESH_IP" ] && echo "as master" || echo "as a replica of $master")"
    exec valkey-server /tmp/valkey.conf
    ;;
  sentinel)
    # Sentinel rewrites its config with what it learns, so the state is kept in the data volume
    # and the rendered template is only the starting point.
    wait_for "$CONFIG/sentinel.conf"
    [ -s /var/lib/sentinel/sentinel.conf ] || install -D -m 0600 "$CONFIG/sentinel.conf" /var/lib/sentinel/sentinel.conf
    exec valkey-sentinel /var/lib/sentinel/sentinel.conf
    ;;
  haproxy)
    wait_for "$CONFIG/haproxy.cfg"
    exec haproxy -W -db -f "$CONFIG/haproxy.cfg"
    ;;
  *)
    echo "hg-member: unknown role '$ROLE'" >&2
    exit 64
    ;;
esac
