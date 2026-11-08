#!/usr/bin/env bash
# Builds the cluster image and loads it into every e2e node's own Docker daemon (they cannot pull
# a local image from a registry).
set -euo pipefail
cd "$(dirname "$0")"
docker build -q -t homegap-cluster:e2e ../cluster >/dev/null
for node in $(docker compose ps --services | grep '^node-'); do
  docker save homegap-cluster:e2e | docker compose exec -T "$node" docker load -q
done
