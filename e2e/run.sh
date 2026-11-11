#!/usr/bin/env bash
# Builds the e2e image from the local linuxArm64/linuxX64 binary and starts the harness.
set -euo pipefail
cd "$(dirname "$0")"
./setup.sh
# Optional local secrets (e.g. PANGOLIN_LICENSE_KEY for EE features); the file is never committed.
if [ -f ../.env ]; then set -a; . ../.env; set +a; fi
ARCH=$(docker version --format '{{.Server.Arch}}')
case "$ARCH" in arm64|aarch64) BIN=build/bin/linuxArm64/releaseExecutable/homegap.kexe ;; *) BIN=build/bin/linuxX64/releaseExecutable/homegap.kexe ;; esac
docker build -q -f image.Dockerfile --build-arg BINARY="$BIN" -t homegap:e2e .. >/dev/null
docker build -q -t homegap-cluster:e2e ../cluster >/dev/null
docker compose up -d --build
./load-cluster-image.sh
echo "dashboard: http://127.0.0.1:18088  (token: e2e/work/data/dashboard-token; logs: docker compose -f e2e/compose.yml logs -f orchestrator)"
