#!/usr/bin/env bash
# Builds the e2e image from the local linuxArm64/linuxX64 binary and starts the harness.
set -euo pipefail
cd "$(dirname "$0")"
./setup.sh
# Optional local secrets (e.g. PANGOLIN_LICENSE_KEY for EE features); the file is never committed.
# E2E_NO_ENV=1 leaves it out (a license key binds to the first cluster database that activates it).
if [ -f ../.env ] && [ -z "${E2E_NO_ENV:-}" ]; then set -a; . ../.env; set +a; fi
# E2E_HOMELAB_ROLE=vote runs the cluster with a homelab that only votes (cluster.homelab_role).
# The role is fixed when a cluster is created, so use it on a fresh harness (its own project
# name and work directory keep it apart from a default one: see docs/development/e2e.md).
if [ "${E2E_HOMELAB_ROLE:-database}" = vote ]; then
  awk '{ print } /^cluster:$/ { print "  homelab_role: vote" }' config.e2e.yml > work/config.vote.yml
  export E2E_CONFIG=./work/config.vote.yml
fi
ARCH=$(docker version --format '{{.Server.Arch}}')
case "$ARCH" in arm64|aarch64) BIN=build/bin/linuxArm64/releaseExecutable/homegap.kexe ;; *) BIN=build/bin/linuxX64/releaseExecutable/homegap.kexe ;; esac
docker build -q -f image.Dockerfile --build-arg BINARY="$BIN" -t homegap:e2e .. >/dev/null
docker build -q -t homegap-cluster:e2e ../cluster >/dev/null
# The local ACME server makes a new root when it starts. The viewer and the tunnels trust the test
# CA and that root. After a new root, HomeGAP orders the certificate of the nodes again.
docker compose up -d pebble viewer
for _ in $(seq 1 30); do
  docker compose exec -T viewer curl -sk https://pebble:15000/roots/0 > work/certs/acme-root.new 2>/dev/null && grep -q BEGIN work/certs/acme-root.new && break
  sleep 1
done
grep -q BEGIN work/certs/acme-root.new || { echo "the local ACME server did not answer" >&2; exit 1; }
NEW_ROOT=""
if ! cmp -s work/certs/acme-root.new work/certs/acme-root.pem; then
  mv work/certs/acme-root.new work/certs/acme-root.pem
  rm -rf work/data/acme
  NEW_ROOT=1
fi
rm -f work/certs/acme-root.new
cat work/certs/ca.pem work/certs/acme-root.pem > work/certs/trust.pem
docker compose up -d --build
if [ -n "$NEW_ROOT" ]; then docker compose restart orchestrator tunnels >/dev/null; fi
./load-cluster-image.sh
echo "dashboard: http://127.0.0.1:18088  (token: e2e/work/data/dashboard-token; logs: docker compose -f e2e/compose.yml logs -f orchestrator)"
