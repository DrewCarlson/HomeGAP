#!/usr/bin/env bash
# Makes the archive of the homelab stack files for a release: the same files that the image
# holds in /usr/share/homegap/deploy (see the Dockerfile), in one folder with the name "homegap".
#
#   tools/stack-archive.sh homegap-0.1.0.tar.gz
set -euo pipefail

out=${1:?usage: tools/stack-archive.sh <archive.tar.gz>}
root=$(cd "$(dirname "$0")/.." && pwd)
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

mkdir -p "$stage/homegap/config"
cp "$root/deploy/compose.yml" "$root/deploy/upgrade.sh" "$root/deploy/.env.example" \
  "$root/deploy/compose.override.example.yml" "$stage/homegap/"
cp "$root/config/config.example.yml" "$stage/homegap/config/"
chmod 755 "$stage/homegap/upgrade.sh"
tar -czf "$out" -C "$stage" homegap
