// HomeGAP's map, shared by the dashboard and the public status page. Everything it loads comes
// from the same origin (/map/...): MapLibre GL, the PMTiles protocol, the Protomaps styles and
// the world tiles. No third-party request is ever made.
import * as maplibregl from "./lib/maplibre-gl.mjs";
import * as basemaps from "./lib/basemaps.mjs";

const BASE = new URL(".", import.meta.url).href;
const ATTRIBUTION = '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors · <a href="https://protomaps.com" target="_blank" rel="noopener">Protomaps</a>';
const HEALTH = ["OK", "DEGRADED", "DOWN", "UNKNOWN"];
/** Vertical spacing of markers stacked apart when they would overlap (px). */
const ROW = 20;

let setup;
/** Loads the PMTiles protocol and the stylesheet once per page. */
function ready() {
  if (!setup) {
    setup = new Promise((resolve, reject) => {
      for (const href of [BASE + "lib/maplibre-gl.css", BASE + "homegap-map.css"]) {
        if (!document.querySelector(`link[href="${href}"]`)) {
          const link = document.createElement("link");
          link.rel = "stylesheet";
          link.href = href;
          document.head.appendChild(link);
        }
      }
      const script = document.createElement("script");
      script.src = BASE + "lib/pmtiles.js";
      script.onload = () => {
        const protocol = new window.pmtiles.Protocol();
        maplibregl.addProtocol("pmtiles", protocol.tile);
        resolve();
      };
      script.onerror = () => reject(new Error("map scripts unavailable"));
      document.head.appendChild(script);
    });
    setup.tiles = fetch(BASE + "world.pmtiles", { headers: { Range: "bytes=0-6" } })
      .then((r) => r.ok)
      .catch(() => false);
  }
  return setup;
}

const dark = () => window.matchMedia("(prefers-color-scheme: dark)").matches;

function css(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

/** Health colors from the page's own tokens (the dashboard's, or the status page's). */
function colors() {
  const d = dark();
  return {
    OK: css("--ok", d ? "#4ade80" : "#15803d"),
    DEGRADED: css("--warn", d ? "#fbbf24" : "#b45309"),
    DOWN: css("--down", d ? "#f87171" : "#c62828"),
    UNKNOWN: css("--unknown", d ? "#9ca3af" : "#6b7280"),
    accent: css("--accent", d ? "#5b8def" : "#2563eb"),
  };
}

function style(withTiles) {
  const d = dark();
  const flavor = { ...basemaps.namedFlavor(d ? "dark" : "light") };
  // Quieter land and water so markers stand out, matching the dashboard's surfaces.
  if (d) Object.assign(flavor, { background: "#0b0e13", earth: "#161b23", water: "#0d1117" });
  else Object.assign(flavor, { background: "#eef1f5", earth: "#fbfbfc", water: "#dde6f0" });
  const s = {
    version: 8,
    glyphs: BASE + "fonts/{fontstack}/{range}.pbf",
    sprite: BASE + "sprites/" + (d ? "dark" : "light"),
    sources: {},
    layers: [{ id: "background", type: "background", paint: { "background-color": flavor.background } }],
  };
  if (withTiles) {
    s.sources.protomaps = { type: "vector", url: "pmtiles://" + BASE + "world.pmtiles", attribution: ATTRIBUTION };
    s.layers = basemaps.layers("protomaps", flavor, { lang: "en" });
  }
  return s;
}

/**
 * A gentle curve from a to b ([lng, lat]) drawn on the flat map: a quadratic curve in Mercator
 * space, bowed toward the pole by a fifth of its length. It starts and ends exactly on the two
 * points (the markers), stays between them, and never crosses the dateline. (A true great circle
 * looks like a huge loop over the pole on this projection.)
 */
function arc(a, b, n = 48) {
  const toXY = ([lng, lat]) => {
    const c = Math.max(-85, Math.min(85, lat)) * (Math.PI / 180);
    return [(lng + 180) / 360, (1 - Math.log(Math.tan(Math.PI / 4 + c / 2)) / Math.PI) / 2];
  };
  const toLngLat = ([x, y]) => [x * 360 - 180, (Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180) / Math.PI];
  const p0 = toXY(a);
  const p2 = toXY(b);
  const dx = p2[0] - p0[0];
  const dy = p2[1] - p0[1];
  const len = Math.hypot(dx, dy);
  if (len === 0) return [a, b];
  // Perpendicular, pointing north (smaller y) so lines arch over the map.
  let nx = -dy / len;
  let ny = dx / len;
  if (ny > 0) { nx = -nx; ny = -ny; }
  const bow = len * 0.2;
  const c = [(p0[0] + p2[0]) / 2 + nx * bow, (p0[1] + p2[1]) / 2 + ny * bow];
  const out = [];
  for (let i = 0; i <= n; i++) {
    const t = i / n;
    const u = 1 - t;
    out.push(toLngLat([u * u * p0[0] + 2 * u * t * c[0] + t * t * p2[0], u * u * p0[1] + 2 * u * t * c[1] + t * t * p2[1]]));
  }
  out[0] = a;
  out[n] = b;
  return out;
}

/** A circle of radiusKm around [lng, lat] as a polygon ring. */
function circle(center, radiusKm, n = 64) {
  const ring = [];
  const lat = (center[1] * Math.PI) / 180;
  for (let i = 0; i <= n; i++) {
    const t = (i / n) * 2 * Math.PI;
    const dy = (radiusKm / 111.32) * Math.sin(t);
    const dx = (radiusKm / (111.32 * Math.max(0.2, Math.cos(lat)))) * Math.cos(t);
    ring.push([center[0] + dx, center[1] + dy]);
  }
  return ring;
}

/** A popup offset 14px from a marker drawn at offset `o` from its point, for every anchor. */
function popupOffset(o) {
  const d = 14;
  const at = (x, y) => [o.x + x, o.y + y];
  return {
    center: at(0, 0), top: at(0, d), bottom: at(0, -d), left: at(d, 0), right: at(-d, 0),
    "top-left": at(d * 0.7, d * 0.7), "top-right": at(-d * 0.7, d * 0.7),
    "bottom-left": at(d * 0.7, -d * 0.7), "bottom-right": at(-d * 0.7, -d * 0.7),
  };
}

function escape(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

/**
 * A popup's body from { title, subtitle, rows: [[label, value, health?]] }; all text is escaped.
 */
function popupHtml(info) {
  const rows = (info.rows || [])
    .map(([k, v, h]) => `<div class="hg-row"><span>${escape(k)}</span><b${h ? ` class="hg-h-${escape(h)}"` : ""}>${escape(v)}</b></div>`)
    .join("");
  return `<div class="hg-pop"><div class="hg-pop-title">${escape(info.title)}</div>${info.subtitle ? `<div class="hg-pop-sub">${escape(info.subtitle)}</div>` : ""}${rows}</div>`;
}

/**
 * Creates a map in `container`. Options: interactive (true), maxZoom (8), minZoom (0.6), center,
 * zoom, onClick(lng, lat) for picking. Returns a handle with setMarkers, setLines, setAreas,
 * setPick, fit and destroy.
 */
export async function createMap(container, options = {}) {
  await ready();
  const withTiles = await setup.tiles;
  container.classList.add("hg-map");
  const map = new maplibregl.Map({
    container,
    style: style(withTiles),
    center: options.center || [10, 25],
    zoom: options.zoom ?? 0.8,
    minZoom: options.minZoom ?? 0.6,
    maxZoom: options.maxZoom ?? 8,
    interactive: options.interactive !== false,
    attributionControl: false,
    dragRotate: false,
    pitchWithRotate: false,
    renderWorldCopies: true,
  });
  map.touchZoomRotate.disableRotation();
  // For inspection (tests, debugging): the MapLibre map behind this container.
  container.hgMap = map;
  map.addControl(new maplibregl.AttributionControl({ compact: true, customAttribution: withTiles ? undefined : ATTRIBUTION }), "bottom-right");
  if (options.interactive !== false) map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
  if (!withTiles) {
    const note = document.createElement("div");
    note.className = "hg-map-note";
    note.textContent = "Map tiles not installed: showing locations only";
    container.appendChild(note);
  }

  let markers = [];
  let lineLabels = [];
  let areaLabels = [];
  let pick = null;
  let lines = [];
  let areas = [];
  let destroyed = false;

  const sourcesReady = new Promise((resolve) => {
    const add = () => {
      if (!map.getSource("hg-lines")) {
        map.addSource("hg-lines", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
        map.addLayer({
          id: "hg-lines",
          type: "line",
          source: "hg-lines",
          layout: { "line-cap": "round" },
          paint: { "line-color": ["get", "color"], "line-width": 1.6, "line-opacity": 0.7, "line-dasharray": ["case", ["get", "dashed"], ["literal", [2, 2]], ["literal", [1, 0]]] },
        });
        map.addSource("hg-areas", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
        map.addLayer({ id: "hg-areas-fill", type: "fill", source: "hg-areas", paint: { "fill-color": ["get", "color"], "fill-opacity": 0.12 } });
        map.addLayer({ id: "hg-areas-line", type: "line", source: "hg-areas", paint: { "line-color": ["get", "color"], "line-width": 1, "line-opacity": 0.5, "line-dasharray": [2, 2] } });
      }
      drawLines();
      drawAreas();
      resolve();
    };
    map.on("style.load", add);
  });

  function drawLines() {
    const src = map.getSource("hg-lines");
    if (!src) return;
    const c = colors();
    src.setData({
      type: "FeatureCollection",
      features: lines.map((l) => ({
        type: "Feature",
        properties: { color: c[l.health] || c.UNKNOWN, dashed: l.health !== "OK" },
        geometry: { type: "LineString", coordinates: arc(l.from, l.to) },
      })),
    });
  }

  function drawAreas() {
    const src = map.getSource("hg-areas");
    if (!src) return;
    const c = colors();
    src.setData({
      type: "FeatureCollection",
      features: areas.map((a) => ({
        type: "Feature",
        properties: { color: c[a.health] || c.accent },
        geometry: { type: "Polygon", coordinates: [circle([a.lng, a.lat], a.radiusKm)] },
      })),
    });
  }

  // The base map follows the light/dark preference; our sources are re-added on each style load.
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const onTheme = () => {
    if (!destroyed) map.setStyle(style(withTiles));
    markers.forEach((m) => m.refresh());
  };
  media.addEventListener("change", onTheme);

  if (options.onClick) {
    map.getCanvas().style.cursor = "crosshair";
    map.on("click", (e) => options.onClick(e.lngLat.wrap().lng, e.lngLat.wrap().lat));
  }

  /**
   * Markers that would cover each other at this zoom (a node in a city and a homelab just outside
   * it, zoomed out to the world) are stacked in a column, north first, each with a short leader
   * line to its true spot. Only the drawn position moves: once zooming in has separated them they
   * sit exactly on their locations again. Known locations ("place") are never moved.
   */
  function declutter() {
    const live = markers.filter((m) => m.spread);
    const pts = live.map((m) => {
      const p = map.project(m.marker.getLngLat());
      return { m, x: p.x, y: p.y };
    });
    // Two markers clash when their dots (and so the start of their labels) would overlap.
    const clash = (a, b) => Math.hypot(a.x - b.x, a.y - b.y) < ROW;
    const group = pts.map((_, i) => i);
    const root = (i) => (group[i] === i ? i : (group[i] = root(group[i])));
    for (let i = 0; i < pts.length; i++) for (let j = i + 1; j < pts.length; j++) if (clash(pts[i], pts[j])) group[root(i)] = root(j);
    const groups = new Map();
    pts.forEach((p, i) => groups.set(root(i), [...(groups.get(root(i)) || []), p]));
    for (const g of groups.values()) {
      g.sort((a, b) => a.y - b.y || a.x - b.x);
      const cx = g.reduce((t, p) => t + p.x, 0) / g.length;
      const cy = g.reduce((t, p) => t + p.y, 0) / g.length;
      g.forEach((p, i) => {
        const dx = g.length > 1 ? cx - p.x : 0;
        const dy = g.length > 1 ? cy + (i - (g.length - 1) / 2) * ROW - p.y : 0;
        p.m.marker.setOffset([dx, dy]);
        let leader = p.m.el.querySelector(".hg-leader");
        const len = Math.hypot(dx, dy);
        if (len < 3) { leader?.remove(); return; }
        if (!leader) {
          leader = document.createElement("span");
          leader.className = "hg-leader";
          p.m.el.prepend(leader);
        }
        // From the drawn dot back to the true location.
        leader.style.width = `${len}px`;
        leader.style.transform = `rotate(${Math.atan2(-dy, -dx)}rad)`;
      });
    }
    // A label that would run over another marker's dot goes on the dot's left instead, when
    // that side is clear.
    const drawn = live.map((m) => {
      const p = map.project(m.marker.getLngLat());
      const o = m.marker.getOffset();
      return { m, x: p.x + o.x, y: p.y + o.y, w: m.el.querySelector(".hg-label")?.offsetWidth || 0 };
    });
    const covers = (a, left) => drawn.some((b) => b !== a && Math.abs(b.y - a.y) < 12 &&
      (left ? b.x > a.x - 18 - a.w - 7 && b.x < a.x - 7 : b.x > a.x + 7 && b.x < a.x + 18 + a.w + 7));
    for (const a of drawn) a.m.el.classList.toggle("hg-label-left", a.w > 0 && covers(a, false) && !covers(a, true));
  }
  map.on("zoom", declutter);
  map.on("resize", declutter);

  const handle = {
    /**
     * markers: [{ id, lng, lat, health, kind: "node"|"homelab", label, highlight, info, onClick }]
     * (info is the popup: { title, subtitle, rows }).
     */
    setMarkers(list) {
      markers.forEach((m) => m.remove());
      markers = (list || []).map((m) => {
        const el = document.createElement("div");
        el.className = `hg-mk hg-${m.kind === "homelab" ? "home" : m.kind === "place" ? "place" : "node"}`;
        el.dataset.id = m.id;
        const health = HEALTH.includes(m.health) ? m.health : "UNKNOWN";
        el.classList.add(`hg-h-${health}`);
        if (m.highlight) el.classList.add("hg-highlight");
        el.innerHTML = `<span class="hg-pulse"></span><span class="hg-dot"></span>${m.label ? `<span class="hg-label">${escape(m.label)}</span>` : ""}`;
        const marker = new maplibregl.Marker({ element: el }).setLngLat([m.lng, m.lat]).addTo(map);
        let popup = null;
        if (m.info) {
          popup = new maplibregl.Popup({ closeButton: false, closeOnClick: false, offset: 14, className: "hg-popup", maxWidth: "280px" })
            .setHTML(popupHtml(m.info));
          // Opened next to the drawn dot, which declutter() may have moved off the true point.
          const open = () => popup.setOffset(popupOffset(marker.getOffset())).setLngLat(marker.getLngLat()).addTo(map);
          el.addEventListener("mouseenter", open);
          el.addEventListener("mouseleave", () => popup.remove());
        }
        el.addEventListener("click", (e) => {
          e.stopPropagation();
          if (m.onClick) m.onClick(m.id);
          else if (popup) popup.isOpen() ? popup.remove() : popup.setOffset(popupOffset(marker.getOffset())).setLngLat(marker.getLngLat()).addTo(map);
        });
        if (m.onClick) el.style.cursor = "pointer";
        return { el, marker, spread: m.kind !== "place", remove: () => { popup?.remove(); marker.remove(); }, refresh: () => {} };
      });
      declutter();
    },
    /** lines: [{ id, from: [lng, lat], to: [lng, lat], health, label }]; labels sit at the midpoint. */
    setLines(list) {
      lines = list || [];
      lineLabels.forEach((m) => m.remove());
      lineLabels = lines.filter((l) => l.label).map((l) => {
        const pts = arc(l.from, l.to);
        const mid = pts[Math.floor(pts.length / 2)];
        const el = document.createElement("div");
        el.className = "hg-chip";
        el.textContent = l.label;
        return new maplibregl.Marker({ element: el }).setLngLat(mid).addTo(map);
      });
      sourcesReady.then(drawLines);
    },
    /** areas: [{ id, lng, lat, radiusKm, label, health }]: soft circles, e.g. a region. */
    setAreas(list) {
      areas = list || [];
      areaLabels.forEach((m) => m.remove());
      areaLabels = areas.filter((a) => a.label).map((a) => {
        const el = document.createElement("div");
        el.className = "hg-area-label";
        el.textContent = a.label;
        return new maplibregl.Marker({ element: el }).setLngLat([a.lng, a.lat]).addTo(map);
      });
      sourcesReady.then(drawAreas);
    },
    /** The picked point (add-node and the locations editor), or null to clear. */
    setPick(lng, lat) {
      pick?.remove();
      pick = null;
      if (lng == null || lat == null) return;
      const el = document.createElement("div");
      el.className = "hg-pick";
      pick = new maplibregl.Marker({ element: el, anchor: "bottom" }).setLngLat([lng, lat]).addTo(map);
    },
    /** Fits the view to [[lng, lat]...] (a single point keeps a regional zoom). */
    fit(points, maxZoom = 4) {
      if (!points || !points.length) return;
      if (points.length === 1) {
        map.jumpTo({ center: points[0], zoom: Math.min(maxZoom, 4) });
        return;
      }
      const b = new maplibregl.LngLatBounds(points[0], points[0]);
      points.forEach((p) => b.extend(p));
      // Keep each line's whole curve in view, not just its ends.
      lines.forEach((l) => arc(l.from, l.to, 8).forEach((p) => b.extend(p)));
      areas.forEach((a) => circle([a.lng, a.lat], a.radiusKm, 8).forEach((p) => b.extend(p)));
      map.fitBounds(b, { padding: 48, maxZoom, duration: 0 });
    },
    resize() { map.resize(); },
    destroy() {
      destroyed = true;
      media.removeEventListener("change", onTheme);
      map.remove();
    },
  };
  return handle;
}
