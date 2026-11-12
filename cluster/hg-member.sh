#!/bin/sh
# A HomeGAP cluster member's process, one role per container: etcd, patroni, valkey, sentinel or
# haproxy. The HomeGAP orchestrator writes each role's config into $HG_CONFIG (default /config);
# until it exists, the role waits, so the containers can be started before the orchestrator has
# rendered anything. Passwords stay in files, never in process arguments.
set -eu
ROLE=${1:?usage: hg-member etcd|patroni|valkey|sentinel|haproxy|haproxy-reload|post-bootstrap|ready ROLE}
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
    # A container that restarts often must not replace a week of dumps with dumps of one day.
    newest=$(ls -1t "$HG_BACKUP_DIR"/homegap-*.dump 2>/dev/null | head -n 1)
    if [ -n "$newest" ] && [ -n "$(find "$newest" -mmin -1200)" ]; then sleep 3600; continue; fi
    out="$HG_BACKUP_DIR/homegap-$(date -u +%Y%m%dT%H%M%SZ).dump"
    if (umask 077; gosu postgres pg_dump -h /var/run/postgresql -U postgres -Fc -f "$out.part" pangolin); then
      mv "$out.part" "$out"
      ls -1t "$HG_BACKUP_DIR"/homegap-*.dump 2>/dev/null | tail -n +8 | xargs -r rm -f
      sleep 86400
    else
      rm -f "$out.part"
      echo "hg-member $ROLE: the backup of the database failed; the next try is in 1 hour" >&2
      sleep 3600
    fi
  done
}

# The password that Sentinel asks for, from a Sentinel config file. Sentinel writes it in quotes.
sentinel_password() {
  sed -n 's/^requirepass //p' "$1" 2>/dev/null | head -n 1 | tr -d '"'
}

# valkey-cli for a Sentinel, with the Sentinel password $1 if there is one.
sentinel_cli() {
  pw=$1; shift
  if [ -n "$pw" ]; then
    timeout 3 env REDISCLI_AUTH="$pw" valkey-cli --no-auth-warning "$@"
  else
    timeout 3 valkey-cli "$@"
  fi
}

# Reloads HAProxy (PID 1 of the haproxy container) with the current config. Each server's up/down
# state is saved first and loaded by the new workers (server-state-file), so a reload never
# briefly sends traffic to a replica the old workers knew was down.
haproxy_reload() {
  haproxy -c -q -f "$CONFIG/haproxy.cfg" || { echo "hg-member haproxy: new config is invalid; keeping the running one" >&2; return 1; }
  python3 - <<'PY' || true
import socket
s = socket.socket(socket.AF_UNIX)
s.settimeout(3)
s.connect("/tmp/haproxy.sock")
s.sendall(b"show servers state\n")
data = b""
while True:
    chunk = s.recv(65536)
    if not chunk:
        break
    data += chunk
open("/tmp/haproxy.state.new", "wb").write(data)
PY
  [ -s /tmp/haproxy.state.new ] && mv -f /tmp/haproxy.state.new /tmp/haproxy.state
  cp "$CONFIG/haproxy.cfg" /tmp/haproxy.cfg.loaded
  kill -USR2 1
  echo "hg-member haproxy: reloaded $CONFIG/haproxy.cfg"
}

# Services bind the member's mesh address, which exists only once the mesh interface is up (the
# homelab's after the orchestrator configures it, a node's after wg-quick at boot).
case "$ROLE" in haproxy|haproxy-reload|post-bootstrap|ready) ;; *)
  wait_for "$CONFIG/mesh.env"
  . "$CONFIG/mesh.env"
  until python3 -c "import socket,sys; socket.socket().bind((sys.argv[1], 0))" "$HG_MESH_IP" 2>/dev/null; do
    echo "hg-member $ROLE: waiting for the mesh address $HG_MESH_IP"
    sleep 3
  done
  ;;
esac

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
    wait_for "$CONFIG/roles.sql"
    install -d -o postgres -g postgres -m 0700 /var/lib/postgresql/data
    install -o postgres -g postgres -m 0600 "$CONFIG/patroni.yml" /tmp/patroni.yml
    install -o postgres -g postgres -m 0600 "$CONFIG/roles.sql" /tmp/roles.sql
    if [ -n "${HG_BACKUP_DIR:-}" ]; then backup_loop & fi
    # Settings changes apply without a restart: Patroni (PID 1 below) re-reads its copy on SIGHUP.
    (
      while sleep 10; do
        if [ -s "$CONFIG/patroni.yml" ] && ! cmp -s "$CONFIG/patroni.yml" /tmp/patroni.yml; then
          install -o postgres -g postgres -m 0600 "$CONFIG/patroni.yml" /tmp/patroni.yml && kill -HUP 1
        fi
      done
    ) &
    exec gosu postgres patroni /tmp/patroni.yml
    ;;
  valkey)
    # Never start as a second master: follow whichever master the Sentinels report, and only
    # fall back to the configured initial master when no Sentinel knows one (a new cluster).
    wait_for "$CONFIG/valkey.env"
    . "$CONFIG/valkey.env"
    master=""
    for s in $HG_SENTINELS; do
      master=$(sentinel_cli "${HG_SENTINEL_PASSWORD:-}" -h "$s" -p 26379 SENTINEL get-master-addr-by-name homegap 2>/dev/null | head -n 1) || master=""
      # An error text is not an address.
      case "$master" in *[!0-9.]*) master="" ;; esac
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
    STATE=/var/lib/sentinel/sentinel.conf
    [ -s "$STATE" ] || install -D -m 0600 "$CONFIG/sentinel.conf" "$STATE"
    # The password is the one setting that goes from the rendered config into the kept copy.
    want=$(sentinel_password "$CONFIG/sentinel.conf")
    if [ "$want" != "$(sentinel_password "$STATE")" ]; then
      sed -i '/^requirepass /d' "$STATE"
      [ -z "$want" ] || echo "requirepass $want" >> "$STATE"
    fi
    # Sentinel also keeps the default user in its copy, and that line wins over the password.
    # Without the line, Sentinel makes the user from the password and writes it again.
    [ -z "$want" ] || sed -i '/^user default on nopass /d' "$STATE"
    # A new password needs a new start: Sentinel (PID 1 below) reads it only then.
    (
      while sleep 10; do
        [ "$(sentinel_password "$CONFIG/sentinel.conf")" = "$want" ] || kill -TERM 1
      done
    ) &
    exec valkey-sentinel "$STATE"
    ;;
  haproxy)
    wait_for "$CONFIG/haproxy.cfg"
    cp "$CONFIG/haproxy.cfg" /tmp/haproxy.cfg.loaded
    # Config changes are reloaded in place, never a restart: the master (PID 1 below) starts new
    # workers on the listening sockets it already holds, so no connection is refused.
    (
      while sleep 5; do
        if [ -s "$CONFIG/haproxy.cfg" ] && ! cmp -s "$CONFIG/haproxy.cfg" /tmp/haproxy.cfg.loaded; then
          haproxy_reload || true
        fi
      done
    ) &
    exec haproxy -W -db -f "$CONFIG/haproxy.cfg"
    ;;
  ready)
    # Exits 0 once this container's role is serving: used by deploy/upgrade.sh between restarts.
    # A database or Valkey member must be the primary or a replica that is linked to it.
    [ -s "$CONFIG/mesh.env" ] && . "$CONFIG/mesh.env"
    case "${2:-}" in
      # The health page needs no user. `etcdctl endpoint health` does, once etcd asks for one.
      etcd) curl -fsS -m 3 http://127.0.0.1:2379/health 2>/dev/null | grep -q '"health":"true"' ;;
      patroni)
        # No config: this member only votes (cluster.homelab_role: vote), so there is nothing to wait for.
        [ -s "$CONFIG/patroni.yml" ] || exit 0
        curl -fsS -m 3 "http://$HG_MESH_IP:8008/primary" >/dev/null 2>&1 ||
          curl -fsS -m 3 "http://$HG_MESH_IP:8008/replica?lag=16MB" >/dev/null 2>&1 ;;
      valkey)
        [ -s "$CONFIG/valkey.env" ] || exit 0
        . "$CONFIG/valkey.env"
        info=$(REDISCLI_AUTH=$HG_PASSWORD valkey-cli -h 127.0.0.1 info replication 2>/dev/null) || exit 1
        echo "$info" | grep -q '^role:master' || echo "$info" | grep -q '^master_link_status:up' ;;
      sentinel)
        master=$(sentinel_cli "$(sentinel_password /var/lib/sentinel/sentinel.conf)" -h 127.0.0.1 -p 26379 SENTINEL get-master-addr-by-name homegap 2>/dev/null | head -n 1)
        case "$master" in ""|*[!0-9.]*) exit 1 ;; esac ;;
      *) echo "usage: hg-member ready etcd|patroni|valkey|sentinel" >&2; exit 64 ;;
    esac
    ;;
  post-bootstrap)
    # Run by Patroni (bootstrap.post_bootstrap) as the postgres user, once, on the member that
    # creates the database cluster: Pangolin gets its own role and database, so no node ever
    # holds the superuser. Uses the local socket; the address Patroni passes is not needed.
    exec psql -v ON_ERROR_STOP=1 -h /var/run/postgresql -U postgres -d postgres -f /tmp/roles.sql
    ;;
  haproxy-reload)
    # Run by the orchestrator (docker compose exec) right after it writes a new config.
    haproxy_reload
    ;;
  *)
    echo "hg-member: unknown role '$ROLE'" >&2
    exit 64
    ;;
esac
