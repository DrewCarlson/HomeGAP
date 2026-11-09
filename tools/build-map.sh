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

pmtiles_cli() {
  local bin="$WORK/pmtiles-$PMTILES_VERSION/pmtiles"
  if [ ! -x "$bin" ]; then
    local os arch asset
    os=$(uname -s); arch=$(uname -m)
    case "$arch" in x86_64|amd64) arch=x86_64 ;; arm64|aarch64) arch=arm64 ;; esac
    mkdir -p "$(dirname "$bin")"
    if [ "$os" = Darwin ]; then
      asset="go-pmtiles-${PMTILES_VERSION}_Darwin_${arch}.zip"
      curl -fsSL -o "$WORK/$asset" "https://github.com/protomaps/go-pmtiles/releases/download/v$PMTILES_VERSION/$asset"
      unzip -oq "$WORK/$asset" pmtiles -d "$(dirname "$bin")"
    else
      asset="go-pmtiles_${PMTILES_VERSION}_Linux_${arch}.tar.gz"
      curl -fsSL "https://github.com/protomaps/go-pmtiles/releases/download/v$PMTILES_VERSION/$asset" | tar -xz -C "$(dirname "$bin")" pmtiles
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
