package homegap.status

/**
 * The public status page: light and dark, with nothing loaded from anywhere but itself (the map's
 * scripts, fonts and tiles come from this origin's `/map/`). It reads
 * `/status/nodes` from the node it came through, then times one request to each other online
 * node's `/status/ping` (each crosses that node and its tunnel to the homelab). The round trip is
 * read from Resource Timing (request sent to first byte), so connection setup isn't counted.
 */
object StatusPage {
    val HTML: String = $$"""
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>Status</title>
<style>
:root {
  --bg: #f4f6f8; --fg: #14181d; --muted: #5f6b78; --faint: #8a95a2; --card: #ffffff; --line: #e3e7ec;
  --ok: #12a150; --ok-soft: #e3f6eb; --warn: #d98a00; --warn-soft: #fdf1da; --off: #9aa3ad; --off-soft: #eef0f2;
  --bad: #d23f31; --bad-soft: #fbe6e3; --accent: #3b6ef5; --bar: #dfe5ec; --shadow: 0 1px 2px rgba(16,24,40,.04), 0 4px 16px rgba(16,24,40,.05);
  /* The map's names for the same colors. */
  --down: var(--bad); --unknown: var(--off); --surface: var(--card); --text: var(--fg); --border: var(--line); --text-2: var(--muted);
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0d1014; --fg: #e8ecf1; --muted: #a0aab6; --faint: #6f7a86; --card: #151a20; --line: #232a33;
    --ok: #34d17a; --ok-soft: #12291c; --warn: #f2b33d; --warn-soft: #2d2412; --off: #6c7682; --off-soft: #1c2128;
    --bad: #ff6b5e; --bad-soft: #2e1714; --accent: #7aa2ff; --bar: #232b35; --shadow: none;
  }
}
* { box-sizing: border-box; }
html, body { margin: 0; background: var(--bg); color: var(--fg); }
body { font: 15px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; -webkit-font-smoothing: antialiased; }
main { max-width: 760px; margin: 0 auto; padding: 40px 16px 56px; }
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 22px; }
h1 { font-size: 24px; letter-spacing: -.01em; margin: 0; }
.updated { color: var(--faint); font-size: 13px; }
button { font: inherit; font-size: 14px; color: var(--fg); background: var(--card); border: 1px solid var(--line); border-radius: 999px; padding: 6px 14px; cursor: pointer; }
button:hover { border-color: var(--faint); }
button:disabled { opacity: .55; cursor: default; }
.banner { display: flex; align-items: center; gap: 12px; padding: 14px 18px; border-radius: 14px; font-weight: 600; margin-bottom: 16px; }
.banner.ok { background: var(--ok-soft); color: var(--ok); }
.banner.warn { background: var(--warn-soft); color: var(--warn); }
.banner.bad { background: var(--bad-soft); color: var(--bad); }
.banner .dot { width: 10px; height: 10px; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 16px; box-shadow: var(--shadow); }
.hero { display: grid; grid-template-columns: 1fr auto; gap: 16px; padding: 22px 24px; margin-bottom: 16px; align-items: center; }
.eyebrow { color: var(--muted); font-size: 13px; text-transform: uppercase; letter-spacing: .06em; font-weight: 600; }
.via { font-size: 30px; font-weight: 700; letter-spacing: -.02em; margin-top: 4px; overflow-wrap: anywhere; }
.where { color: var(--muted); margin-top: 2px; }
.big { text-align: right; }
.big .num { font-size: 40px; font-weight: 700; letter-spacing: -.02em; font-variant-numeric: tabular-nums; line-height: 1.1; }
.big .unit { font-size: 16px; color: var(--muted); font-weight: 500; margin-left: 3px; }
.quality { font-size: 13px; font-weight: 600; margin-top: 4px; }
.list { padding: 6px 0; }
.list h2 { font-size: 13px; color: var(--muted); text-transform: uppercase; letter-spacing: .06em; margin: 12px 24px 6px; }
.row { display: grid; grid-template-columns: 14px 1fr minmax(90px, 180px) 76px; gap: 14px; align-items: center; padding: 12px 24px; border-top: 1px solid var(--line); }
.row:first-of-type { border-top: 0; }
.dot { width: 9px; height: 9px; border-radius: 50%; background: var(--off); justify-self: center; }
.dot.online { background: var(--ok); box-shadow: 0 0 0 4px var(--ok-soft); }
.dot.degraded { background: var(--warn); box-shadow: 0 0 0 4px var(--warn-soft); }
.dot.offline { background: var(--off); box-shadow: 0 0 0 4px var(--off-soft); }
.name { font-weight: 600; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.badge { font-size: 11px; font-weight: 600; color: var(--accent); border: 1px solid currentColor; border-radius: 999px; padding: 0 7px; line-height: 18px; }
.meta { color: var(--muted); font-size: 13px; }
.meta .state.degraded { color: var(--warn); }
.meta .state.offline { color: var(--faint); }
.bar { height: 6px; border-radius: 3px; background: var(--bar); overflow: hidden; }
.bar > i { display: block; height: 100%; width: 0; border-radius: 3px; background: var(--ok); transition: width .5s ease; }
.bar.pending > i { width: 35%; background: var(--faint); opacity: .35; animation: slide 1.1s ease-in-out infinite; }
@keyframes slide { from { transform: translateX(-100%); } to { transform: translateX(300%); } }
.ms { text-align: right; font-variant-numeric: tabular-nums; font-weight: 600; }
.ms small { color: var(--muted); font-weight: 500; }
.ms.na { color: var(--faint); font-weight: 500; }
footer { color: var(--faint); font-size: 13px; margin-top: 18px; line-height: 1.6; }
.error { padding: 18px 24px; color: var(--bad); }
.mapcard { margin-bottom: 16px; overflow: hidden; }
#map { height: 320px; }
@media (max-width: 560px) {
  main { padding-top: 24px; }
  .hero { grid-template-columns: 1fr; }
  .big { text-align: left; }
  .row { grid-template-columns: 14px 1fr 64px; padding: 12px 16px; }
  .row .bar { display: none; }
  .list h2 { margin-left: 16px; }
}
@media (prefers-reduced-motion: reduce) { .bar.pending > i { animation: none; } .bar > i { transition: none; } }
</style>
</head>
<body>
<main>
  <header>
    <div><h1 id="title">Status</h1><div class="updated" id="updated">&nbsp;</div></div>
    <button id="refresh" type="button">Refresh</button>
  </header>
  <div id="banner" class="banner ok" hidden><span class="dot online"></span><span id="bannerText"></span></div>
  <section class="card hero" aria-live="polite">
    <div>
      <div class="eyebrow">You're connected through</div>
      <div class="via" id="via">&hellip;</div>
      <div class="where" id="where">&nbsp;</div>
    </div>
    <div class="big">
      <div><span class="num" id="myPing">&ndash;</span><span class="unit">ms</span></div>
      <div class="quality" id="myQuality">&nbsp;</div>
    </div>
  </section>
  <section class="card mapcard" id="mapcard" aria-label="Map of the nodes">
    <div id="map"></div>
  </section>
  <section class="card list">
    <h2>Nodes</h2>
    <div id="rows"></div>
  </section>
  <footer>
    Each time is one request from your browser to the homelab through that node and back.
    Lower is better; the node you're connected through is the one DNS picked for your location.
    The home area on the map marks only the region the service runs from.
  </footer>
</main>
<script>
(function () {
  var REGIONS = { NA: 'North America', SA: 'South America', EU: 'Europe', AF: 'Africa', AS: 'Asia', OC: 'Oceania', ME: 'Middle East' };
  var running = false;
  function el(id) { return document.getElementById(id); }
  function region(r) { return REGIONS[r] || r || ''; }
  function color(ms) { return ms < 80 ? 'var(--ok)' : ms < 200 ? 'var(--warn)' : 'var(--bad)'; }
  function quality(ms) { return ms < 60 ? 'Excellent' : ms < 120 ? 'Good' : ms < 250 ? 'Fair' : 'Slow'; }
  function fmt(ms) { return ms < 10 ? ms.toFixed(1) : String(Math.round(ms)); }

  // One request; the time from sending it to its first byte (connection setup excluded).
  async function measure(url) {
    var u = url + (url.indexOf('?') < 0 ? '?' : '&') + 'r=' + Math.random().toString(36).slice(2);
    var ctl = new AbortController();
    var timer = setTimeout(function () { ctl.abort(); }, 8000);
    var t0 = performance.now();
    try {
      var r = await fetch(u, { cache: 'no-store', mode: 'cors', credentials: 'omit', signal: ctl.signal });
      var t1 = performance.now();
      if (!r.ok) throw new Error('HTTP ' + r.status);
      var e = performance.getEntriesByName(u).pop();
      if (e && e.requestStart > 0 && e.responseStart > e.requestStart) return e.responseStart - e.requestStart;
      return t1 - t0;
    } finally { clearTimeout(timer); }
  }

  function row(n) {
    var d = document.createElement('div');
    d.className = 'row';
    var label = n.state === 'online' ? 'Online' : n.state === 'degraded' ? 'Degraded' : 'Offline';
    d.innerHTML =
      '<span class="dot ' + n.state + '"></span>' +
      '<div><div class="name"></div><div class="meta"><span class="where"></span> &middot; <span class="state ' + n.state + '">' + label + '</span></div></div>' +
      '<div class="bar' + (n.pingUrl ? ' pending' : '') + '"' + (n.pingUrl ? '' : ' style="visibility:hidden"') + '><i></i></div>' +
      '<div class="ms na">' + (n.pingUrl ? '&hellip;' : '&ndash;') + '</div>';
    d.querySelector('.name').textContent = n.id;
    if (n.current) { var b = document.createElement('span'); b.className = 'badge'; b.textContent = 'You'; d.querySelector('.name').appendChild(b); }
    d.querySelector('.where').textContent = n.place || region(n.region);
    return d;
  }

  function show(rowEl, ms, max) {
    var bar = rowEl.querySelector('.bar'), fill = bar.firstChild, cell = rowEl.querySelector('.ms');
    bar.classList.remove('pending');
    if (ms == null) { cell.className = 'ms na'; cell.textContent = 'no reply'; fill.style.width = '0'; return; }
    cell.className = 'ms';
    cell.innerHTML = fmt(ms) + ' <small>ms</small>';
    fill.style.background = color(ms);
    fill.style.width = Math.max(4, Math.min(100, ms / max * 100)) + '%';
  }

  function banner(nodes) {
    var on = nodes.filter(function (n) { return n.state === 'online'; }).length;
    var off = nodes.filter(function (n) { return n.state === 'offline'; }).length;
    var deg = nodes.length - on - off;
    var b = el('banner'), dot = b.querySelector('.dot');
    b.hidden = false;
    if (on === nodes.length) { b.className = 'banner ok'; dot.className = 'dot online'; el('bannerText').textContent = 'All ' + nodes.length + ' nodes online'; }
    else if (off === nodes.length) { b.className = 'banner bad'; dot.className = 'dot offline'; el('bannerText').textContent = 'No nodes online'; }
    else { b.className = 'banner warn'; dot.className = 'dot degraded'; el('bannerText').textContent = on + ' of ' + nodes.length + ' nodes online' + (deg ? ' · ' + deg + ' degraded' : '') + (off ? ' · ' + off + ' offline' : ''); }
  }

  async function load() {
    if (running) return;
    running = true;
    el('refresh').disabled = true;
    try {
      var r = await fetch('/status/nodes', { cache: 'no-store' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      var s = await r.json();
      document.title = s.title;
      el('title').textContent = s.title;
      var me = s.nodes.filter(function (n) { return n.current; })[0];
      el('via').textContent = me ? me.id : 'Unknown node';
      el('where').textContent = me ? (me.place ? me.place + ' · ' : '') + region(me.region) : 'Open this page at its main address to see your node.';
      banner(s.nodes);
      var rows = el('rows');
      rows.textContent = '';
      var els = s.nodes.map(function (n) { var x = row(n); rows.appendChild(x); return x; });
      window.dispatchEvent(new CustomEvent('hg-status', { detail: { s: s, results: null } }));
      if (performance.clearResourceTimings) performance.clearResourceTimings();
      var results = await Promise.all(s.nodes.map(function (n) {
        return n.pingUrl ? measure(n.pingUrl).catch(function () { return null; }) : Promise.resolve(undefined);
      }));
      var max = Math.max.apply(null, [150].concat(results.filter(function (x) { return x != null; })));
      s.nodes.forEach(function (n, i) { if (n.pingUrl) show(els[i], results[i], max * 1.15); });
      if (me) {
        var mine = results[s.nodes.indexOf(me)];
        el('myPing').textContent = mine != null ? fmt(mine) : '–';
        el('myQuality').textContent = mine != null ? quality(mine) : '';
        el('myQuality').style.color = mine != null ? color(mine) : '';
      }
      window.dispatchEvent(new CustomEvent('hg-status', { detail: { s: s, results: results } }));
      el('updated').textContent = 'Updated ' + new Date().toLocaleTimeString();
    } catch (e) {
      el('rows').innerHTML = '<div class="error"></div>';
      el('rows').firstChild.textContent = 'Could not load the status: ' + (e.message || e);
    } finally {
      running = false;
      el('refresh').disabled = false;
    }
  }

  el('refresh').onclick = load;
  load();
  setInterval(function () { if (!document.hidden) load(); }, 60000);
})();
</script>
<script type="module">
// The map: the nodes in their status colors (yours highlighted, with your ping to each), and the
// home region as a soft area. If the map can't load, its card is hidden.
const box = document.getElementById('map');
let map = null, last = null, fitted = false;
const HEALTH = { online: 'OK', degraded: 'DEGRADED', offline: 'DOWN' };
function draw(d) {
  last = d;
  if (!map) return;
  const s = d.s, placed = s.nodes.filter((n) => n.lat != null && n.lon != null);
  map.setMarkers(placed.map((n) => {
    const i = s.nodes.indexOf(n), ms = d.results ? d.results[i] : null;
    const ping = ms != null ? (ms < 10 ? ms.toFixed(1) : Math.round(ms)) + ' ms' : n.pingUrl ? (d.results ? 'no reply' : 'measuring…') : '–';
    return {
      id: n.id, lat: n.lat, lng: n.lon, health: HEALTH[n.state] || 'UNKNOWN', kind: 'node', label: n.id, highlight: n.current,
      info: { title: n.id, subtitle: n.current ? 'You’re connected through this node' : (n.place || null),
              rows: [['Status', n.state, HEALTH[n.state]], ['Your round trip', ping]].concat(n.current && n.place ? [['Place', n.place]] : []) },
    };
  }));
  map.setAreas(s.home ? [{ id: 'home', lat: s.home.lat, lng: s.home.lon, radiusKm: 1300, label: s.home.label }] : []);
  const me = placed.find((n) => n.current);
  map.setLines(me && s.home ? [{ id: 'path', from: [me.lon, me.lat], to: [s.home.lon, s.home.lat], health: HEALTH[me.state] || 'UNKNOWN' }] : []);
  if (!fitted) {
    const pts = placed.map((n) => [n.lon, n.lat]).concat(s.home ? [[s.home.lon, s.home.lat]] : []);
    if (pts.length) { map.fit(pts, 3); fitted = true; }
  }
}
window.addEventListener('hg-status', (e) => draw(e.detail));
try {
  const m = await import('/map/homegap-map.js');
  map = await m.createMap(box, { maxZoom: 4, minZoom: 0.4 });
  if (last) draw(last);
} catch (e) {
  document.getElementById('mapcard').hidden = true;
}
</script>
</body>
</html>
""".trimIndent()
}
