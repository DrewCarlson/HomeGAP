package homegap.speedtest

import homegap.brand.Brand

/**
 * Self-contained device test page (no external assets). The link token arrives in the URL
 * fragment, which browsers keep across the node's redirect and never send to any server.
 * NOTE: the markup deliberately contains no dollar signs (Kotlin raw string); the only
 * templates are the [Brand] emblem and favicon.
 */
object SpeedTestPage {
    val HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<meta name="color-scheme" content="dark">
<meta name="darkreader-lock">
<title>HomeGAP speed test</title>
${Brand.FAVICON_LINK}
<style>
@font-face { font-family: "Sora"; font-weight: 400; font-display: swap; src: url("/fonts/sora-latin-400-normal.woff2") format("woff2"); }
@font-face { font-family: "Sora"; font-weight: 600; font-display: swap; src: url("/fonts/sora-latin-600-normal.woff2") format("woff2"); }
@font-face { font-family: "JetBrains Mono"; font-weight: 400; font-display: swap; src: url("/fonts/jetbrains-mono-latin-400-normal.woff2") format("woff2"); }
:root { --bg:#070A1F; --fg:#E6E8F5; --muted:#A9AFD6; --card:#0A0E2A; --line:#1E2452; --accent:#8EF6FF; --bad:#FF8A98; --tone:#FFFFFF; }
* { box-sizing: border-box; }
body { margin:0; font:15px/1.5 "Sora",system-ui,-apple-system,Segoe UI,Roboto,sans-serif; background-color:var(--bg); color:var(--fg); background-image: linear-gradient(#0C1131 1px, transparent 1px), linear-gradient(90deg, #0C1131 1px, transparent 1px); background-size: 32px 32px; }
main { max-width:720px; margin:0 auto; padding:24px 16px 48px; }
h1 { font-size:22px; margin:0 0 4px; display:flex; align-items:center; gap:10px; }
.hg-mark { width:28px; height:28px; flex:none; }
.sub { color:var(--muted); margin:0 0 20px; }
.grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:12px; }
.card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:14px 16px; }
.label { color:var(--muted); font-size:13px; }
.value { font-size:28px; font-weight:600; font-variant-numeric:tabular-nums; }
.unit { font-size:14px; color:var(--muted); font-weight:400; margin-left:4px; }
button { font:inherit; padding:10px 18px; border-radius:8px; border:1px solid var(--accent); background:var(--accent); color:#fff; cursor:pointer; }
button:disabled { opacity:.5; cursor:default; }
.row { display:flex; gap:12px; align-items:center; flex-wrap:wrap; margin:20px 0; }
#status { color:var(--muted); }
.err { color:var(--bad); }
ul { padding-left:18px; } a { color:var(--accent); }
button.secondary { background:transparent; color:var(--accent); }
.live { display:flex; gap:20px; align-items:center; flex-wrap:wrap; margin-top:12px; }
.live-side { flex:1; min-width:200px; }
.phase { font-weight:600; }
/* A half-ring gauge: a conic arc filled to --p (0..1) of its scale. The phase picks the color:
   white for ping, cyan for download, violet for upload. Amber and rose are kept for problems. */
body[data-phase="download"] { --tone:#18FFFF; }
body[data-phase="upload"] { --tone:#B388FF; }
.gauge { position:relative; width:220px; height:122px; overflow:hidden; flex:none; }
.gauge-arc { position:absolute; inset:0 0 auto 0; width:220px; height:220px; border-radius:50%;
  background:conic-gradient(from -90deg, var(--tone) calc(var(--p, 0) * 180deg), #2E3570 0 180deg, transparent 0);
  -webkit-mask:radial-gradient(circle, transparent 62%, #000 63%); mask:radial-gradient(circle, transparent 62%, #000 63%); }
.gauge-text { position:absolute; left:0; right:0; bottom:0; text-align:center; }
.gauge-value { font-size:30px; font-weight:600; font-variant-numeric:tabular-nums; line-height:1.1; }
.gauge-unit { color:var(--muted); font-size:13px; }
.gauge-scale { display:flex; justify-content:space-between; width:220px; color:var(--muted); font-size:11px; }
.bars { display:flex; align-items:flex-end; gap:2px; height:48px; margin-top:8px; }
.bars span { flex:1; min-width:2px; background:var(--accent); opacity:.75; border-radius:2px 2px 0 0; }
table { width:100%; border-collapse:collapse; font-variant-numeric:tabular-nums; }
th, td { text-align:left; padding:6px 8px; border-bottom:1px solid var(--line); }
th { color:var(--muted); font-weight:500; font-size:13px; }
tr.now td { font-weight:600; }
td .reason { display:block; font-size:13px; font-weight:400; }
</style>
</head>
<body>
<main>
  <h1>${Brand.MARK_SVG}HomeGAP speed test</h1>
  <p class="sub" id="via">Checking which node you are connected through&hellip;</p>
  <div class="grid">
    <div class="card"><div class="label">Ping</div><div class="value"><span id="ping">&ndash;</span><span class="unit">ms</span></div></div>
    <div class="card"><div class="label">Jitter</div><div class="value"><span id="jitter">&ndash;</span><span class="unit">ms</span></div></div>
    <div class="card"><div class="label">Download</div><div class="value"><span id="down">&ndash;</span><span class="unit">Mb/s</span></div></div>
    <div class="card"><div class="label">Upload</div><div class="value"><span id="up">&ndash;</span><span class="unit">Mb/s</span></div></div>
  </div>
  <div class="card live">
    <div>
      <div class="gauge" id="gauge"><div class="gauge-arc"></div>
        <div class="gauge-text"><div class="gauge-value" id="g-value">&ndash;</div><div class="gauge-unit" id="g-unit">&nbsp;</div></div>
      </div>
      <div class="gauge-scale"><span>0</span><span id="g-max"></span></div>
    </div>
    <div class="live-side">
      <div class="phase" id="g-label">Ready</div>
      <div class="label" id="g-node">&nbsp;</div>
      <div class="bars" id="bars"></div>
    </div>
  </div>
  <div class="row"><button id="go" disabled>Start test</button><button id="all" class="secondary" disabled>Test all nodes</button><span id="status"></span></div>
  <div class="card" id="all-card" hidden><div class="label">All nodes</div>
    <table><thead><tr><th>Node</th><th>Ping</th><th>Jitter</th><th>Down</th><th>Up</th></tr></thead><tbody id="all-rows"></tbody></table>
  </div>
  <div class="card" style="margin-top:12px"><div class="label">Test through a specific node</div><ul id="others"></ul></div>
</main>
<script>
(function () {
  var params = new URLSearchParams(location.hash.slice(1));
  var token = params.get('t') || sessionStorage.getItem('homegap.st') || '';
  if (params.get('t')) { sessionStorage.setItem('homegap.st', token); history.replaceState(null, '', location.pathname + location.search); }
  var H = { 'X-HomeGAP-Token': token };
  // Opened at the shared name (the node DNS picked for this device), or at a node's own name.
  var auto = new URLSearchParams(location.search).get('via') === 'auto';
  var phase = 8, nodes = {}, here = null;
  function el(id) { return document.getElementById(id); }
  function status(t, bad) { var s = el('status'); s.textContent = t; s.className = bad ? 'err' : ''; }
  function fmt(v) { return v == null ? '–' : (v >= 100 ? v.toFixed(0) : v.toFixed(1)); }
  function now() { return performance.now(); }

  // The live view: a gauge on a scale that only grows during a phase, and one bar per sample.
  var viz = (function () {
    var STEPS = [10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000];
    var max = 0, bars = [], unit = '', lastBar = 0;
    function scale(v) { for (var i = 0; i < STEPS.length; i++) if (STEPS[i] >= v * 1.1) return STEPS[i]; return STEPS[STEPS.length - 1]; }
    function draw() {
      var top = Math.max.apply(null, [1].concat(bars)), b = el('bars');
      b.textContent = '';
      bars.forEach(function (v) { var x = document.createElement('span'); x.style.height = Math.max(2, v / top * 100) + '%'; b.appendChild(x); });
    }
    return {
      start: function (label, u, node) {
        max = 0; bars = []; unit = u; lastBar = 0;
        el('g-label').textContent = label; el('g-node').textContent = node ? 'Through node ' + node : ' ';
        document.body.dataset.phase = label.toLowerCase();
        el('g-unit').textContent = u; this.set(null); draw();
      },
      set: function (v) {
        if (v != null) max = Math.max(max, scale(v));
        el('gauge').style.setProperty('--p', v == null || !max ? 0 : Math.min(1, v / max));
        el('g-value').textContent = fmt(v);
        el('g-max').textContent = max ? max + ' ' + unit : '';
      },
      // A sample for the bars; rates arrive many times a second, so at most four bars a second.
      sample: function (v, throttle) {
        this.set(v);
        if (throttle && now() - lastBar < 250) return;
        lastBar = now(); bars.push(v); if (bars.length > 48) bars.shift(); draw();
      },
      idle: function (label) { el('g-label').textContent = label; el('g-node').textContent = ' '; this.set(null); el('g-unit').textContent = ' '; }
    };
  })();

  async function info() {
    var r = await fetch('/speedtest/info', { headers: H, cache: 'no-store' });
    if (r.status === 401) throw new Error('This test link has expired. Create a new one from the HomeGAP dashboard.');
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
  }

  // base: '' for the node that served this page, or another node's speed test origin.
  async function ping(base, n) {
    var samples = [];
    for (var i = 0; i <= n; i++) {
      var t0 = now();
      var r = await fetch(base + '/speedtest/ping?i=' + i + '&r=' + Math.random(), { headers: H, cache: 'no-store' });
      if (!r.ok) throw new Error('ping HTTP ' + r.status);
      if (i > 0) { samples.push(now() - t0); viz.sample(now() - t0); }
    }
    var avg = samples.reduce(function (a, b) { return a + b; }, 0) / samples.length;
    var j = 0;
    for (var k = 1; k < samples.length; k++) j += Math.abs(samples[k] - samples[k - 1]);
    return { avg: avg, jitter: samples.length > 1 ? j / (samples.length - 1) : 0, n: samples.length };
  }

  // Bytes counted after a one-second warm-up so TCP slow start does not drag the figure down.
  async function download(base, streams, show) {
    var start = now(), warm = start + 1000, bytes = 0, counted = 0, tWarm = null;
    async function one() {
      var r = await fetch(base + '/speedtest/down?seconds=' + (phase + 1) + '&r=' + Math.random(), { headers: H, cache: 'no-store' });
      if (!r.ok) throw new Error('download HTTP ' + r.status);
      var rd = r.body.getReader();
      for (;;) {
        var x = await rd.read();
        if (x.done) break;
        var t = now();
        bytes += x.value.length;
        if (t >= warm) {
          if (tWarm === null) tWarm = t;
          counted += x.value.length;
          // A rate over a few milliseconds is noise: the first reading comes after 200 ms.
          if (t - tWarm >= 200) { var mbps = counted * 8 / ((t - tWarm) / 1000) / 1e6; show(mbps); viz.sample(mbps, true); }
        }
      }
    }
    var list = []; for (var i = 0; i < streams; i++) list.push(one());
    await Promise.all(list);
    var secs = (now() - (tWarm || start)) / 1000;
    return (tWarm ? counted : bytes) * 8 / secs / 1e6;
  }

  // Large streamed XHR bodies with progress events: per-request overhead would otherwise cap
  // the figure well below the link rate. Counted after a one-second warm-up, like download.
  function upload(base, streams, show) {
    var chunk = new Uint8Array(1 << 20);
    for (var o = 0; o < chunk.length; o += 65536) crypto.getRandomValues(chunk.subarray(o, o + 65536));
    var parts = []; for (var c = 0; c < 32; c++) parts.push(chunk);
    var blob = new Blob(parts);
    var start = now(), warm = start + 1000, end = warm + phase * 1000;
    var done = [], live = [], xhrs = [], warmBytes = null, failed = null;
    function total() { var t = 0; for (var i = 0; i < streams; i++) t += done[i] + live[i]; return t; }
    function tick() {
      var t = now();
      if (t >= warm && warmBytes === null) warmBytes = total();
      if (warmBytes !== null && t - warm >= 200) { var mbps = (total() - warmBytes) * 8 / ((t - warm) / 1000) / 1e6; show(mbps); viz.sample(mbps, true); }
    }
    function send(i) {
      if (now() >= end || failed) return;
      var x = new XMLHttpRequest();
      xhrs[i] = x;
      x.open('POST', base + '/speedtest/up');
      x.setRequestHeader('X-HomeGAP-Token', token);
      x.upload.onprogress = function (e) { live[i] = e.loaded; tick(); };
      x.onload = function () {
        if (x.status !== 200) { failed = 'upload HTTP ' + x.status; return; }
        done[i] += blob.size; live[i] = 0; send(i);
      };
      x.onerror = function () { failed = 'upload failed'; };
      x.send(blob);
    }
    for (var i = 0; i < streams; i++) { done[i] = 0; live[i] = 0; send(i); }
    return new Promise(function (resolve, reject) {
      var timer = setInterval(function () {
        tick();
        if (failed) { clearInterval(timer); xhrs.forEach(function (x) { x.abort(); }); reject(new Error(failed)); return; }
        if (now() >= end) {
          clearInterval(timer);
          var mbps = (total() - (warmBytes || 0)) * 8 / ((now() - warm) / 1000) / 1e6;
          xhrs.forEach(function (x) { x.onload = null; x.onerror = null; x.abort(); });
          resolve(mbps);
        }
      }, 200);
    });
  }

  // One test against [base]; [out] shows each figure as it is measured. The result is saved
  // through the same node, which attributes it.
  async function test(base, node, isAuto, out) {
    var res = { auto: isAuto, pingSamples: 0 };
    try {
      viz.start('Ping', 'ms', node);
      status('Measuring ping' + (node ? ' through ' + node : '') + '…');
      var p = await ping(base, 20);
      res.pingMs = p.avg; res.jitterMs = p.jitter; res.pingSamples = p.n;
      out('ping', p.avg); out('jitter', p.jitter);
      viz.start('Download', 'Mb/s', node);
      status('Measuring download' + (node ? ' through ' + node : '') + '…');
      res.downMbps = await download(base, 4, function (v) { out('down', v); }); out('down', res.downMbps);
      viz.start('Upload', 'Mb/s', node);
      status('Measuring upload' + (node ? ' through ' + node : '') + '…');
      res.upMbps = await upload(base, 4, function (v) { out('up', v); }); out('up', res.upMbps);
    } catch (e) {
      // A network error (the browser's "Failed to fetch") means the node did not answer at all.
      res.error = e instanceof TypeError ? 'Could not reach this node (' + (e.message || e) + ')' : String(e.message || e);
    }
    try { await fetch(base + '/speedtest/result', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, H), body: JSON.stringify(res) }); } catch (e) {}
    return res;
  }

  function busy(on) { el('go').disabled = on; el('all').disabled = on; }
  function cards(name, v) { el(name).textContent = fmt(v); }
  function reset() { ['ping', 'jitter', 'down', 'up'].forEach(function (n) { el(n).textContent = '–'; }); }

  async function run() {
    busy(true); reset();
    var res = await test('', here, auto, cards);
    viz.idle(res.error ? 'Failed' : 'Done');
    if (res.error) status(res.error, true); else status('Done. The result was saved to the HomeGAP dashboard.');
    busy(false);
  }

  // Every node in turn, each through its own address; a row per node fills in as it runs.
  async function runAll() {
    busy(true);
    var ids = Object.keys(nodes), rows = el('all-rows'), failed = 0;
    el('all-card').hidden = false; rows.textContent = '';
    var cells = {};
    ids.forEach(function (id) {
      var tr = document.createElement('tr'); tr.id = 'row-' + id;
      cells[id] = {};
      ['node', 'ping', 'jitter', 'down', 'up'].forEach(function (k) {
        var td = document.createElement('td'); td.textContent = k === 'node' ? id : '–'; cells[id][k] = td; tr.appendChild(td);
      });
      rows.appendChild(tr);
    });
    for (var i = 0; i < ids.length; i++) {
      var id = ids[i], tr = el('row-' + id);
      tr.className = 'now'; reset();
      var base = nodes[id].charAt(nodes[id].length - 1) === '/' ? nodes[id].slice(0, -1) : nodes[id];
      var res = await test(base, id, false, function (k, v) { cells[id][k].textContent = fmt(v); cards(k, v); });
      tr.className = '';
      if (res.error) {
        // The reason under the name: a phone has no hover to show a tooltip.
        failed++;
        var why = document.createElement('span'); why.className = 'reason'; why.textContent = res.error;
        cells[id].node.textContent = id + ' (failed)'; cells[id].node.className = 'err'; cells[id].node.appendChild(why);
      }
    }
    viz.idle('Done');
    status(failed ? failed + ' of ' + ids.length + ' nodes failed; the reasons are in the table.'
                  : 'Done. All ' + ids.length + ' results were saved to the HomeGAP dashboard.', failed > 0);
    busy(false);
  }

  el('go').onclick = run;
  el('all').onclick = runAll;
  // A new link opened in the same tab only changes the fragment: reload to pick up its token.
  window.addEventListener('hashchange', function () { if (location.hash.indexOf('t=') >= 0) location.reload(); });
  if (!token) { status('Missing test token. Open this page from a link created in the HomeGAP dashboard.', true); el('via').textContent = ''; return; }
  info().then(function (i) {
    phase = i.phaseSeconds || 8;
    nodes = i.nodes; here = i.node;
    if (!Object.keys(i.nodes).some(function (id) { return i.nodes[id].indexOf('//' + location.host + '/') >= 0; })) auto = true;
    el('via').textContent = i.node
      ? 'Connected through node ' + i.node + (auto ? ' (chosen by DNS for your location)' : '') + '.'
      : 'Connected through the node DNS chose for you (this address does not name it; pick a node below).';
    var ul = el('others');
    Object.keys(i.nodes).forEach(function (id) {
      var li = document.createElement('li'), a = document.createElement('a');
      a.href = i.nodes[id] + '#t=' + encodeURIComponent(token);
      a.textContent = id + (id === i.node ? ' (current)' : '');
      li.appendChild(a); ul.appendChild(li);
    });
    busy(false);
  }).catch(function (e) { status(String(e.message || e), true); });
})();
</script>
</body>
</html>
""".trimIndent()
}
