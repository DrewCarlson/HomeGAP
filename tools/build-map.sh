#!/usr/bin/env bash
# Builds the self-hosted map's tiles: map/world.pmtiles, the world to zoom 6 (~45 MB), from a
# Protomaps build. Run at development time and by the Docker build; HomeGAP never fetches map data
# at runtime. (The map's scripts come from npm and its fonts and icons from basemaps-assets, both
# fetched by the web build: see web/build.gradle.kts.)
#
#   tools/build-map.sh tiles
#
# MAP_MAXZOOM (default 6) and PROTOMAPS_BUILD (default: the newest daily build) change the tiles.
set -euo pipefail
cd "$(dirname "$0")/.."

PMTILES_VERSION=1.31.2
WORK=${TMPDIR:-/tmp}/homegap-map-build
mkdir -p "$WORK"

# The SHA-256 of each release archive. Change them together with PMTILES_VERSION.
pmtiles_sha256() {
  case "$1" in
    go-pmtiles-1.31.2_Darwin_arm64.zip) echo 40528f7f616fcbf91207cd48c8fc023d213f6d86c0cbf1f748732803d1880f3d ;;
    go-pmtiles-1.31.2_Darwin_x86_64.zip) echo 1f0dc02eee6c58312dd6c509faee1b5c32f0596568af1bf51f1b034e7a88a65b ;;
    go-pmtiles_1.31.2_Linux_arm64.tar.gz) echo f8bd47e7ea866863489cad588fbaf2f31f42e5821f7a03f009b3769f05801cb1 ;;
    go-pmtiles_1.31.2_Linux_x86_64.tar.gz) echo 3ed7dbf4ec2e6dfe5e25b6f70d1ffc932729f93c86db353bf514dd71010a312f ;;
  esac
}

pmtiles_cli() {
  local bin="$WORK/pmtiles-$PMTILES_VERSION/pmtiles"
  if [ ! -x "$bin" ]; then
    local os arch asset
    os=$(uname -s); arch=$(uname -m)
    case "$arch" in x86_64|amd64) arch=x86_64 ;; arm64|aarch64) arch=arm64 ;; esac
    mkdir -p "$(dirname "$bin")"
    if [ "$os" = Darwin ]; then asset="go-pmtiles-${PMTILES_VERSION}_Darwin_${arch}.zip"; else asset="go-pmtiles_${PMTILES_VERSION}_Linux_${arch}.tar.gz"; fi
    curl -fsSL -o "$WORK/$asset" "https://github.com/protomaps/go-pmtiles/releases/download/v$PMTILES_VERSION/$asset"
    local want got
    want=$(pmtiles_sha256 "$asset")
    if command -v sha256sum >/dev/null; then got=$(sha256sum "$WORK/$asset"); else got=$(shasum -a 256 "$WORK/$asset"); fi
    [ -n "$want" ] && [ "${got%% *}" = "$want" ] || { echo "checksum mismatch for $asset" >&2; exit 1; }
    if [ "$os" = Darwin ]; then
      unzip -oq "$WORK/$asset" pmtiles -d "$(dirname "$bin")"
    else
      tar -xzf "$WORK/$asset" -C "$(dirname "$bin")" pmtiles
    fi
  fi
  echo "$bin"
}

tiles() {
  local build=${PROTOMAPS_BUILD:-}
  if [ -z "$build" ]; then
    build=$(curl -fsSL https://build-metadata.protomaps.dev/builds.json | grep -o '"key": *"[0-9]*\.pmtiles"' | tail -n 1 | grep -o '[0-9]*\.pmtiles')
  fi
  mkdir -p map
  "$(pmtiles_cli)" extract "https://build.protomaps.com/$build" map/world.pmtiles.tmp --maxzoom="${MAP_MAXZOOM:-6}"
  mv -f map/world.pmtiles.tmp map/world.pmtiles
  echo "map/world.pmtiles from $build ($(du -h map/world.pmtiles | cut -f1))"
}

case "${1:-}" in
  tiles) tiles ;;
  *) echo "usage: $0 tiles" >&2; exit 2 ;;
esac
