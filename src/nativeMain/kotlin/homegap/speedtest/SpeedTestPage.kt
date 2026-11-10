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
<title>HomeGAP speed test</title>
${Brand.FAVICON_LINK}
<style>
:root { --bg:#f6f7f9; --fg:#1b1f24; --muted:#5b6470; --card:#fff; --line:#dde1e6; --accent:#2563eb; --bad:#b42318; }
@media (prefers-color-scheme: dark) { :root { --bg:#0f1216; --fg:#e6e9ee; --muted:#9aa4b1; --card:#171b21; --line:#2a313a; --accent:#60a5fa; --bad:#f97066; } }
* { box-sizing: border-box; }
body { margin:0; font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif; background:var(--bg); color:var(--fg); }
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
</style>
</head>
<body>
<main>
  <h1>${Brand.MARK_SVG}HomeGAP speed test</h1>
  <p class="sub" id="via">Checking which node you are connected through&hellip;</p>
  <div class="grid">
    <div class="card"><div class="label">Ping</div><div class="value"><span id="ping">&ndash;</span><span class="unit">ms</span></div></div>
    <div class="card"><div class="label">Jitter</div><div class="value"><span id="jitter">&ndash;</span><span class="unit">ms</span></div></div>
    <div class="card"><div class="label">Download</div><div class="value"><span id="down">&ndash;</span><span class="unit">Mbps</span></div></div>
    <div class="card"><div class="label">Upload</div><div class="value"><span id="up">&ndash;</span><span class="unit">Mbps</span></div></div>
  </div>
  <div class="row"><button id="go" disabled>Start test</button><span id="status"></span></div>
  <div class="card"><div class="label">Test through a specific node</div><ul id="others"></ul></div>
</main>
<script>
(function () {
  var params = new URLSearchParams(location.hash.slice(1));
  var token = params.get('t') || sessionStorage.getItem('homegap.st') || '';
  if (params.get('t')) { sessionStorage.setItem('homegap.st', token); history.replaceState(null, '', location.pathname + location.search); }
  var H = { 'X-HomeGAP-Token': token };
  // Opened at the shared name (the node DNS picked for this device), or at a node's own name.
  var auto = new URLSearchParams(location.search).get('via') === 'auto';
  var phase = 8;
  function el(id) { return document.getElementById(id); }
  function status(t, bad) { var s = el('status'); s.textContent = t; s.className = bad ? 'err' : ''; }
  function fmt(v) { return v == null ? '–' : (v >= 100 ? v.toFixed(0) : v.toFixed(1)); }
  function now() { return performance.now(); }

  async function info() {
    var r = await fetch('/speedtest/info', { headers: H, cache: 'no-store' });
    if (r.status === 401) throw new Error('This test link has expired. Create a new one from the HomeGAP dashboard.');
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
  }

  async function ping(n) {
    var samples = [];
    for (var i = 0; i <= n; i++) {
      var t0 = now();
      var r = await fetch('/speedtest/ping?i=' + i + '&r=' + Math.random(), { headers: H, cache: 'no-store' });
      if (!r.ok) throw new Error('ping HTTP ' + r.status);
      if (i > 0) samples.push(now() - t0);
    }
    var avg = samples.reduce(function (a, b) { return a + b; }, 0) / samples.length;
    var j = 0;
    for (var k = 1; k < samples.length; k++) j += Math.abs(samples[k] - samples[k - 1]);
    return { avg: avg, jitter: samples.length > 1 ? j / (samples.length - 1) : 0, n: samples.length };
  }

  // Bytes counted after a one-second warm-up so TCP slow start does not drag the figure down.
  async function download(streams) {
    var start = now(), warm = start + 1000, bytes = 0, counted = 0, tWarm = null;
    async function one() {
      var r = await fetch('/speedtest/down?seconds=' + (phase + 1) + '&r=' + Math.random(), { headers: H, cache: 'no-store' });
      if (!r.ok) throw new Error('download HTTP ' + r.status);
      var rd = r.body.getReader();
      for (;;) {
        var x = await rd.read();
        if (x.done) break;
        var t = now();
        bytes += x.value.length;
        if (t >= warm) { if (tWarm === null) tWarm = t; counted += x.value.length; el('down').textContent = fmt(counted * 8 / ((t - tWarm) / 1000) / 1e6); }
      }
    }
    var list = []; for (var i = 0; i < streams; i++) list.push(one());
    await Promise.all(list);
    var secs = (now() - (tWarm || start)) / 1000;
    return (tWarm ? counted : bytes) * 8 / secs / 1e6;
  }

  // Large streamed XHR bodies with progress events: per-request overhead would otherwise cap
  // the figure well below the link rate. Counted after a one-second warm-up, like download.
  function upload(streams) {
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
      if (warmBytes !== null && t > warm) el('up').textContent = fmt((total() - warmBytes) * 8 / ((t - warm) / 1000) / 1e6);
    }
    function send(i) {
      if (now() >= end || failed) return;
      var x = new XMLHttpRequest();
      xhrs[i] = x;
      x.open('POST', '/speedtest/up');
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

  async function run() {
    el('go').disabled = true;
    var res = { auto: auto, pingSamples: 0 };
    try {
      status('Measuring ping…');
      var p = await ping(20);
      res.pingMs = p.avg; res.jitterMs = p.jitter; res.pingSamples = p.n;
      el('ping').textContent = fmt(p.avg); el('jitter').textContent = fmt(p.jitter);
      status('Measuring download…');
      res.downMbps = await download(4); el('down').textContent = fmt(res.downMbps);
      status('Measuring upload…');
      res.upMbps = await upload(4); el('up').textContent = fmt(res.upMbps);
      status('Done. The result was saved to the HomeGAP dashboard.');
    } catch (e) {
      res.error = String(e.message || e);
      status(res.error, true);
    }
    try { await fetch('/speedtest/result', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, H), body: JSON.stringify(res) }); } catch (e) {}
    el('go').disabled = false;
  }

  el('go').onclick = run;
  // A new link opened in the same tab only changes the fragment: reload to pick up its token.
  window.addEventListener('hashchange', function () { if (location.hash.indexOf('t=') >= 0) location.reload(); });
  if (!token) { status('Missing test token. Open this page from a link created in the HomeGAP dashboard.', true); el('via').textContent = ''; return; }
  info().then(function (i) {
    phase = i.phaseSeconds || 8;
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
    el('go').disabled = false;
  }).catch(function (e) { status(String(e.message || e), true); });
})();
</script>
</body>
</html>
""".trimIndent()
}
