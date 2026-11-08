#!/usr/bin/env bash
# Renders the config for the clustered-Pangolin prototype. Each node (c1, c2) is one network
# namespace, like a VPS host: Pangolin, Gerbil, Traefik, PostgreSQL and Valkey share it, and a
# WireGuard mesh (mesh0, 10.77.0.0/24) is the only path between nodes for cluster traffic. c1 holds
# the PostgreSQL and Valkey primaries, c2 streaming replicas. Each node has its own Pangolin region
# so the homelab connector created through a node stays pinned to that node.
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
# Compose reads .env next to compose.yml (test values only; git-ignored).
printf 'SETUP_TOKEN=%s\nDB_PASSWORD=%s\n' "$(cat work/setup-token)" "$DBPW" > .env

# Mesh keys, generated once with wireguard-tools in a throwaway container.
if [ ! -s work/mesh/keys ]; then
  docker run --rm docker.io/library/alpine:3.20 sh -c \
    'apk add -q wireguard-tools >/dev/null 2>&1; for n in c1 c2; do k=$(wg genkey); echo "$n $k $(echo "$k" | wg pubkey)"; done' \
    > work/mesh/keys
fi
key() { awk -v n="$1" -v f="$2" '$1 == n { print $f }' work/mesh/keys; }

primary_conn="postgresql://postgres:$DBPW@10.77.0.1:5432/postgres"

for spec in c1:11:1:c2:21:2 c2:21:2:c1:11:1; do
  IFS=: read -r NODE PUB MESH PEER PEER_PUB PEER_MESH <<< "$spec"
  D=work/$NODE/config
  mkdir -p "$D/traefik" "$D/dynamic" "$D/certificates" work/$NODE/certs
  cp ../work/certs/wild.pem ../work/certs/wild.key work/$NODE/certs/

  cat > work/$NODE/mesh0.conf <<EOF
[Interface]
PrivateKey = $(key "$NODE" 2)
ListenPort = 51821

[Peer]
PublicKey = $(key "$PEER" 3)
Endpoint = 172.31.0.$PEER_PUB:51821
AllowedIPs = 10.77.0.$PEER_MESH/32
PersistentKeepalive = 25
EOF
  chmod 600 work/$NODE/mesh0.conf
  printf 'MESH_IP=10.77.0.%s\nPEER_PUBLIC=172.31.0.%s\n' "$MESH" "$PEER_PUB" > work/$NODE/host.env

  cat > "$D/config.yml" <<EOF
gerbil:
  start_port: 51820
  base_endpoint: "172.31.0.$PUB"
  exit_node_name: "$NODE"
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
  connection_string: "$primary_conn"
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
  region: "$NODE"
redis:
  host: "10.77.0.1"
  port: 6379
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
echo "rendered work/c1 and work/c2"
