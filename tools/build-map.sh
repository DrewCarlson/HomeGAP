#!/usr/bin/env bash
# Builds the self-hosted map assets. Run at development time (and by the Docker build for the
# tiles); nothing here is fetched by HomeGAP at runtime.
#
#   tools/build-map.sh tiles   # map/world.pmtiles: the world to zoom 6 (~45 MB), from Protomaps
#   tools/build-map.sh web     # MapLibre, PMTiles and Protomaps scripts, fonts and icons, into
#                              # the dashboard's public files (checked in)
#
# MAP_MAXZOOM (default 6) and PROTOMAPS_BUILD (default: the newest daily build) change the tiles.
set -euo pipefail
cd "$(dirname "$0")/.."

PMTILES_VERSION=1.31.2
MAPLIBRE_VERSION=6.16.0
PMTILES_JS_VERSION=4.5.0
BASEMAPS_VERSION=5.7.2
# protomaps/basemaps-assets: the fonts (SIL OFL) and icon sprites the Protomaps styles use.
ASSETS_COMMIT=028c18f713baecad011301ff7a69acc39bcc2ae7
# Glyph ranges for English labels (Latin, Latin Extended, punctuation) and the Greek and Cyrillic
# place names that have no English one.
GLYPH_RANGES="0-255 256-511 512-767 768-1023 1024-1279 7680-7935 8192-8447"
FONTS=("Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic")

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

web() {
  local out=web/src/jsMain/resources/public/map
  rm -rf "$out/lib" "$out/fonts" "$out/sprites"
  mkdir -p "$out/lib" "$out/fonts" "$out/sprites"
  local npmdir="$WORK/npm"
  rm -rf "$npmdir"; mkdir -p "$npmdir"
  (cd "$npmdir" && npm pack --silent "maplibre-gl@$MAPLIBRE_VERSION" "pmtiles@$PMTILES_JS_VERSION" "@protomaps/basemaps@$BASEMAPS_VERSION" >/dev/null)
  for t in "$npmdir"/*.tgz; do mkdir -p "${t%.tgz}" && tar -xzf "$t" -C "${t%.tgz}"; done
  cp "$npmdir/maplibre-gl-$MAPLIBRE_VERSION/package/dist/maplibre-gl.mjs" \
     "$npmdir/maplibre-gl-$MAPLIBRE_VERSION/package/dist/maplibre-gl-worker.mjs" \
     "$npmdir/maplibre-gl-$MAPLIBRE_VERSION/package/dist/maplibre-gl.css" "$out/lib/"
  # Their source map comments would point at files we don't ship.
  sed -i.bak '/^\/\/# sourceMappingURL=/d' "$out/lib/maplibre-gl.mjs" "$out/lib/maplibre-gl-worker.mjs" && rm -f "$out"/lib/*.bak
  cp "$npmdir/pmtiles-$PMTILES_JS_VERSION/package/dist/pmtiles.js" "$out/lib/pmtiles.js"
  # basemaps' ES module has no imports; pmtiles' imports fflate by name, so it stays the bundled script.
  cp "$npmdir/protomaps-basemaps-$BASEMAPS_VERSION/package/dist/esm/index.js" "$out/lib/basemaps.mjs"
  sed -i.bak '/^\/\/# sourceMappingURL=/d' "$out/lib/basemaps.mjs" "$out/lib/pmtiles.js" && rm -f "$out"/lib/*.bak
  cp "$npmdir/maplibre-gl-$MAPLIBRE_VERSION/package/LICENSE.txt" "$out/lib/LICENSE-maplibre.txt"

  local raw="https://raw.githubusercontent.com/protomaps/basemaps-assets/$ASSETS_COMMIT"
  for font in "${FONTS[@]}"; do
    mkdir -p "$out/fonts/$font"
    for range in $GLYPH_RANGES; do
      curl -fsSL -o "$out/fonts/$font/$range.pbf" "$raw/fonts/${font// /%20}/$range.pbf"
    done
  done
  curl -fsSL -o "$out/fonts/OFL.txt" "$raw/fonts/OFL.txt"
  for flavor in light dark; do
    for f in "$flavor.json" "$flavor.png" "$flavor@2x.json" "$flavor@2x.png"; do
      curl -fsSL -o "$out/sprites/$f" "$raw/sprites/v4/$f"
    done
  done
  cat > "$out/VERSIONS.txt" <<EOF
maplibre-gl $MAPLIBRE_VERSION (BSD-3-Clause)
pmtiles $PMTILES_JS_VERSION (BSD-3-Clause)
@protomaps/basemaps $BASEMAPS_VERSION (BSD-3-Clause)
protomaps/basemaps-assets $ASSETS_COMMIT (fonts: SIL OFL 1.1; sprites)
Map data: OpenStreetMap contributors (ODbL), via Protomaps
EOF
  du -sh "$out"
}

case "${1:-}" in
  tiles) tiles ;;
  web) web ;;
  *) echo "usage: $0 tiles|web" >&2; exit 2 ;;
esac
