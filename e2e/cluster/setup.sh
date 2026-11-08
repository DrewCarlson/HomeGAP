#!/usr/bin/env bash
# Renders the config for the clustered-Pangolin prototype: two nodes (c1, c2) that share one
# PostgreSQL and Valkey, each with its own Pangolin region so a site connected through a node is
# pinned to that node's exit node. Test-only values; nothing here is used outside this harness.
set -euo pipefail
cd "$(dirname "$0")"
DOMAIN=test.example.net
mkdir -p work
[ -s work/secret ] || openssl rand -hex 24 > work/secret
[ -s work/setup-token ] || openssl rand -hex 16 > work/setup-token
SECRET=$(cat work/secret)
# Compose reads .env next to compose.yml (test values only; git-ignored).
printf 'SETUP_TOKEN=%s\n' "$(cat work/setup-token)" > .env

for spec in c1:11 c2:21; do
  NODE=${spec%%:*}
  IP=172.31.0.${spec##*:}
  D=work/$NODE/config
  mkdir -p "$D/traefik" "$D/dynamic" "$D/certificates" work/$NODE/certs
  cp ../work/certs/wild.pem ../work/certs/wild.key work/$NODE/certs/

  cat > "$D/config.yml" <<EOF
gerbil:
  start_port: 51820
  base_endpoint: "$IP"
  exit_node_name: "$NODE"
app:
  dashboard_url: "http://pangolin.$DOMAIN:3000"
  log_level: "info"
  telemetry:
    anonymous_usage: false
domains:
  homegap:
    base_domain: "$DOMAIN"
    cert_resolver: "letsencrypt"
    prefer_wildcard_cert: true
postgres:
  connection_string: "postgresql://postgres:cluster-e2e@db-c1:5432/postgres"
server:
  secret: "$SECRET"
  cors:
    origins: ["http://pangolin.$DOMAIN:3000"]
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
  host: "valkey"
  port: 6379
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
    address: ":3000"
serversTransport:
  insecureSkipVerify: true
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
          - url: "http://pangolin:3002"
    api-service:
      loadBalancer:
        servers:
          - url: "http://pangolin:3000"
tls:
  stores:
    default:
      defaultCertificate:
        certFile: /certs/wild.pem
        keyFile: /certs/wild.key
EOF
done
echo "rendered work/c1 and work/c2"
