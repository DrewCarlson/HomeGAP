#!/usr/bin/env bash
# Loads the images that run.sh built into the Docker daemon of each e2e node (a node cannot pull
# a local image from a registry): the cluster image and the HomeGAP image, which is the agent of
# the node.
set -euo pipefail
cd "$(dirname "$0")"
docker build -q -t homegap-cluster:e2e ../cluster >/dev/null
for node in $(docker compose ps --services | grep '^node-'); do
  docker save homegap-cluster:e2e homegap:e2e | docker compose exec -T "$node" docker load -q
done
