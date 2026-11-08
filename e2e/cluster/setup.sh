#!/usr/bin/env bash
# Renders the config for the clustered-Pangolin prototype. Three mesh members, each one network
# namespace like a host: the homelab (hl) and two nodes (c1, c2). A WireGuard mesh (mesh0,
# 10.77.0.0/24) is the only path between members for cluster traffic.
#
# The database is dynamic: Patroni (with etcd on all three members as the vote) keeps the
# PostgreSQL primary on the homelab by default, fails over to a node when the homelab is gone, and
# HomeGAP switches it back once the homelab is stable. Valkey does the same with Sentinel. Each node
# reaches "the primary, wherever it is" through a local HAProxy, so Pangolin's settings never change.
# The nodes run Pangolin, Gerbil and Traefik, each in its own Pangolin region so the homelab
# connector created through a node stays pinned to that node.
# Test-only values; nothing here is used outside this harness.
set -euo pipefail
cd "$(dirname "$0")"
DOMAIN=test.example.net
mkdir -p work/mesh
[ -s work/secret ] || openssl rand -hex 24 > work/secret
[ -s work/setup-token ] || openssl rand -hex 16 > work/setup-token
[ -s work/db-password ] || openssl rand -hex 16 > work/db-password
SECRET=$(cat work/secret)
DBPW=$(cat work/db-password)
# Compose reads .env next to compose.yml (test values only; git-ignored). Keep generated ids.
touch .env
grep -E '^(NEWT_|OLM_)' .env > .env.keep || true
printf 'SETUP_TOKEN=%s\nDB_PASSWORD=%s\n' "$(cat work/setup-token)" "$DBPW" > .env
cat .env.keep >> .env && rm -f .env.keep

MEMBERS="hl c1 c2"
public() { case $1 in hl) echo 172.31.0.30 ;; c1) echo 172.31.0.11 ;; c2) echo 172.31.0.21 ;; esac; }
mesh() { case $1 in hl) echo 10.77.0.10 ;; c1) echo 10.77.0.1 ;; c2) echo 10.77.0.2 ;; esac; }

# Mesh keys, generated once with wireguard-tools in a throwaway container.
if ! grep -q '^hl ' work/mesh/keys 2>/dev/null; then
  docker run --rm docker.io/library/alpine:3.20 sh -c \
    'apk add -q wireguard-tools >/dev/null 2>&1; for n in hl c1 c2; do k=$(wg genkey); echo "$n $k $(echo "$k" | wg pubkey)"; done' \
    > work/mesh/keys
fi
key() { awk -v n="$1" -v f="$2" '$1 == n { print $f }' work/mesh/keys; }

for M in $MEMBERS; do
  mkdir -p work/$M
  # ---------------------------------------------------------------- mesh
  {
    printf '[Interface]\nPrivateKey = %s\nListenPort = 51821\n' "$(key "$M" 2)"
    for P in $MEMBERS; do
      [ "$P" = "$M" ] && continue
      printf '\n[Peer]\nPublicKey = %s\nAllowedIPs = %s/32\nPersistentKeepalive = 25\n' "$(key "$P" 3)" "$(mesh "$P")"
      # The homelab sits behind NAT: it dials the nodes, and nodes learn its address from that.
      if [ "$P" != hl ]; then printf 'Endpoint = %s:51821\n' "$(public "$P")"; fi
    done
  } > work/$M/mesh0.conf
  chmod 600 work/$M/mesh0.conf
  peers=$(for P in $MEMBERS; do if [ "$P" != "$M" ]; then printf '%s ' "$(public "$P")"; fi; done)
  printf 'MESH_IP=%s\nPEER_PUBLICS="%s"\n' "$(mesh "$M")" "$peers" > work/$M/host.env

  # ---------------------------------------------------------------- Patroni
  cat > work/$M/patroni.yml <<EOF
scope: homegap
name: $M
restapi:
  listen: 0.0.0.0:8008
  connect_address: $(mesh "$M"):8008
etcd3:
  hosts: 10.77.0.10:2379,10.77.0.1:2379,10.77.0.2:2379
bootstrap:
  dcs:
    ttl: 20
    loop_wait: 5
    retry_timeout: 5
    maximum_lag_on_failover: 1048576
    postgresql:
      use_pg_rewind: true
      parameters:
        wal_log_hints: "on"
        max_connections: 300
  initdb:
    - encoding: UTF8
    - data-checksums
postgresql:
  listen: 0.0.0.0:5432
  connect_address: $(mesh "$M"):5432
  data_dir: /var/lib/postgresql/data/pgdata
  bin_dir: /usr/lib/postgresql/17/bin
  pgpass: /tmp/pgpass
  authentication:
    superuser: { username: postgres, password: "$DBPW" }
    replication: { username: replicator, password: "$DBPW" }
    rewind: { username: postgres, password: "$DBPW" }
  pg_hba:
    - local all all trust
    - host all all 127.0.0.1/32 scram-sha-256
    - host all all 10.77.0.0/24 scram-sha-256
    - host replication replicator 10.77.0.0/24 scram-sha-256
    - host replication replicator 127.0.0.1/32 scram-sha-256
EOF

  # ---------------------------------------------------------------- Valkey Sentinel
  cat > work/$M/sentinel.conf <<EOF
port 26379
protected-mode no
sentinel announce-ip $(mesh "$M")
sentinel resolve-hostnames no
sentinel monitor homegap 10.77.0.10 6379 2
sentinel down-after-milliseconds homegap 5000
sentinel failover-timeout homegap 20000
sentinel parallel-syncs homegap 1
EOF
done

# -------------------------------------------------------------------- Pangolin nodes
for M in c1 c2; do
  D=work/$M/config
  mkdir -p "$D/traefik" "$D/dynamic" "$D/certificates" work/$M/certs
  cp ../work/certs/wild.pem ../work/certs/wild.key work/$M/certs/

  cat > "$D/config.yml" <<EOF
gerbil:
  start_port: 51820
  base_endpoint: "$(public "$M")"
  exit_node_name: "$M"
app:
  dashboard_url: "http://pangolin.$DOMAIN:3080"
  log_level: "info"
  telemetry:
    anonymous_usage: false
domains:
  homegap:
    base_domain: "$DOMAIN"
    cert_resolver: "letsencrypt"
    prefer_wildcard_cert: true
postgres:
  # The local HAProxy always forwards to the current primary; reads use the local member.
  connection_string: "postgresql://postgres:$DBPW@127.0.0.1:5433/postgres"
  replicas:
    - connection_string: "postgresql://postgres:$DBPW@127.0.0.1:5432/postgres"
server:
  secret: "$SECRET"
  cors:
    origins: ["http://pangolin.$DOMAIN:3080"]
    methods: ["GET", "POST", "PUT", "DELETE", "PATCH"]
    allowed_headers: ["X-CSRF-Token", "Content-Type"]
    credentials: false
traefik:
  file_mode: true
  site_types: ["newt"]
  cert_resolver: "letsencrypt"
  http_entrypoint: "web"
  https_entrypoint: "websecure"
  allow_raw_resources: true
flags:
  require_email_verification: false
  disable_signup_without_invite: true
  disable_user_create_org: true
  allow_raw_resources: true
  enable_integration_api: true
  disable_local_sites: true
  disable_basic_wireguard_sites: true
EOF

  cat > "$D/privateConfig.yml" <<EOF
app:
  region: "$M"
redis:
  host: "127.0.0.1"
  port: 6380
gerbil:
  local_exit_node_reachable_at: "http://127.0.0.1:3004"
flags:
  enable_redis: true
EOF

  cat > "$D/traefik/traefik_config.yml" <<'EOF'
providers:
  file:
    directory: "/var/dynamic"
    watch: true
experimental:
  plugins:
    badger:
      moduleName: "github.com/fosrl/badger"
      version: "v1.8.0"
log:
  level: "INFO"
certificatesResolvers:
  letsencrypt:
    acme:
      httpChallenge:
        entryPoint: web
      email: "admin@test.example.net"
      storage: "/var/certificates/acme.json"
entryPoints:
  web:
    address: ":80"
  websecure:
    address: ":443"
    http:
      encodedCharacters:
        allowEncodedSlash: true
        allowEncodedQuestionMark: true
  dashboard:
    address: ":3080"
serversTransport:
  insecureSkipVerify: true
  forwardingTimeouts:
    dialTimeout: 5s
ping:
  entryPoint: "web"
EOF

  cat > "$D/dynamic/dynamic_config.yml" <<'EOF'
http:
  middlewares:
    badger:
      plugin:
        badger:
          disableForwardAuth: true
  routers:
    next-router:
      rule: "!PathPrefix(`/api/v1`)"
      service: next-service
      entryPoints: [dashboard]
      middlewares: [badger]
    api-router:
      rule: "PathPrefix(`/api/v1`)"
      service: api-service
      entryPoints: [dashboard]
      middlewares: [badger]
    ws-router:
      rule: "PathPrefix(`/`)"
      service: api-service
      entryPoints: [dashboard]
      middlewares: [badger]
  services:
    next-service:
      loadBalancer:
        servers:
          - url: "http://127.0.0.1:3002"
    api-service:
      loadBalancer:
        servers:
          - url: "http://127.0.0.1:3000"
tls:
  stores:
    default:
      defaultCertificate:
        certFile: /certs/wild.pem
        keyFile: /certs/wild.key
EOF
done
echo "rendered work/hl, work/c1 and work/c2"
