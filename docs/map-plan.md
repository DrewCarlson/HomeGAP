# Maps and locations plan

A visual map of the deployment in the dashboard and on the status page, and named, searchable
locations for nodes and the homelab. Nothing calls a third-party service at runtime: map data,
styles, fonts and the locations catalog ship with HomeGAP and are served by the orchestrator.
Third-party sources are only used at development time to build what ships.

## Decisions

| Question | Decision |
| --- | --- |
| Map engine | MapLibre GL JS with a self-hosted vector tile archive (PMTiles, Protomaps basemap built from OpenStreetMap). |
| Detail | Cities and regions: the archive goes to about metro zoom (maxzoom 7, or 6 if 7 is too large). |
| Homelab on the public status page | Region only: a soft area marker with a coarse label, never a precise point. |
| Location lookup | A curated, editable catalog of data-center cities. No world-cities database, no IP geolocation. |

## What ships

- **`map/world.pmtiles`**: a planet extract of the Protomaps basemap, built once with
  `pmtiles extract <build> world.pmtiles --maxzoom=7` (measured with `--dry-run` first; the target
  is under about 100 MB, else maxzoom 6). It goes in the Docker image next to the binary, not in
  the binary, and is served at `/map/world.pmtiles` with HTTP range requests.
- **Styles, fonts and sprites**: the Protomaps light and dark styles, recolored to the
  dashboard's tokens; the glyph ranges the labels need (Noto Sans) and the sprite sheet. A few MB.
- **JavaScript**: `maplibre-gl` and the `pmtiles` protocol, bundled into the dashboard build
  (about 250 KB gzipped).
- **Attribution**: "© OpenStreetMap contributors · Protomaps" on every map, as ODbL requires.
- **`locations/datacenters.yml`**: data-center cities with name, country, region code,
  coordinates and the providers there (AWS, Google Cloud, Azure, Vultr, Hetzner, DigitalOcean,
  Akamai/Linode, OVHcloud, Oracle and others). Generated once from the providers' published
  region lists by a script in `tools/`, reviewed and checked in.

A `tools/build-map.sh` script rebuilds the map assets, so anyone can refresh or enlarge them
(for example a higher maxzoom in their own image).

## Locations

- **Config**: a node's `location` gains an optional `name` ("Dallas, Texas"). A new optional
  `homelab.location` has the same fields. Configs without them keep working; nodes with only a
  region code are placed at the region's centroid, as today.
- **Catalog**: the shipped `datacenters.yml`, plus your own entries and edits in
  `<data_dir>/locations.yml`, which win over the shipped ones (edits keep the same id; removing a
  shipped entry hides it).
- **API**: `GET /api/locations` (merged catalog), `PUT /api/locations/{id}`,
  `DELETE /api/locations/{id}`.
- **Dashboard**: a **Locations** section under Config: a table with search, add/edit/remove, and
  a small map to place or move an entry.
- **Names everywhere**: node cards, map hovers, the speed test and the status page show the
  location name, falling back to the region code.

## Maps

One map component in the dashboard: themed to light and dark, draggable and zoomable, with HTML
markers on top of the vector map.

- **Overview**: every node and the homelab as a pulsing marker in its health color (healthy,
  degraded, down, unknown), pulsing faster when not healthy. Hovering shows the name, health, the
  failing checks, tunnel state, round trip and provider. Faint lines from the homelab to each node,
  colored by tunnel state. Clicking a node opens it on the Nodes page.
- **Speed test**: the homelab-to-node lines labeled with the latest round trip and throughput;
  clicking a node runs its test. Device tests show the node the device reached.
- **Add node**: the location step gets a search box over the catalog ("dal" shows "Dallas,
  Texas, US · Vultr, AWS, …"). Picking one fills the coordinates, region code and name. You can
  also click the map: the nearest catalog entry within about 50 km is offered as the name, or
  you type one.
- **Status page**: the same engine with a lighter style and zoom capped at 4, so a visitor
  downloads little. Markers for every node in their status color, the node you came through
  highlighted, and your ping to each as a label. The homelab is a soft area marker at its region
  with a coarse label ("Home · North America"), so its location is never revealed. Tiles and
  scripts come from the same origin as the page.

## Phases

1. **Map assets and serving**: build script, the PMTiles archive, styles, fonts, sprites;
   range-request serving at `/map/*` on the dashboard and public listeners; attribution.
2. **Map component and Overview**: the MapLibre wrapper for the dashboard, markers, hovers,
   theme switching; the Overview map.
3. **Locations**: config fields, the catalog (build script, shipped file, merged API, editor),
   names in the UI, the add-node lookup and map picking.
4. **Speed test map**.
5. **Status page map**, with the homelab shown by region only.
6. **Docs, tests and e2e checks**, including that no page requests anything off-origin.

## Outcome

Built as planned. The world to zoom 6 is 45 MB (zoom 7 was 190 MB), so the image ships zoom 6;
MapLibre keeps zooming by enlarging those tiles. The libraries are maplibre-gl 6.16.0, pmtiles
4.5.0 and @protomaps/basemaps 5.7.2, served from `/map/lib/` to both the dashboard and the
status page. The catalog ships about 100 cities.

## Open points (at planning time)

- The final archive size decides maxzoom 7 versus 6; I'll measure before committing to either.
- The status page's map adds a few hundred KB to a few MB per visitor (scripts plus low-zoom
  tiles), served through the front door from the homelab. If that's too much, it can fall back
  to a static map image rendered at build time.
