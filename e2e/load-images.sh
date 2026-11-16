#!/usr/bin/env bash
# Loads the HomeGAP image that run.sh built into the Docker daemon of each e2e node: it is the
# agent of the node, and a node cannot pull a local image from a registry.
set -euo pipefail
cd "$(dirname "$0")"
for node in $(docker compose ps --services | grep '^node-'); do
  docker save homegap:e2e | docker compose exec -T "$node" docker load -q
done
