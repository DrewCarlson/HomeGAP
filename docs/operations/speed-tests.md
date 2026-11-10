# Speed tests

The speed tests measure the latency and the throughput through the real traffic path. This page tells you how to enable them and what each test measures.

## Enable the speed tests

With `speedtest.enabled: true`, the orchestrator serves a speed test origin on `speedtest.bind`. The speed tests need a front door.

| Key | Default |
| --- | --- |
| `speedtest.enabled` | `false` |
| `speedtest.hostname` | `speedtest` |
| `speedtest.bind` | `0.0.0.0:8089` |
| `speedtest.link_ttl` | `2h` |
| `speedtest.phase_seconds` | `8` |
| `speedtest.streams` | `4` |

A change of `speedtest.enabled` or `speedtest.bind` needs a restart of the orchestrator.

The front door already forwards `speedtest.<domain>` and `speedtest-<node>.<domain>` from the nodes. Route these names to the orchestrator on port 8089 from the homelab Traefik. Put these labels on the `homegap-mesh` service, because it owns the networks of the orchestrator:

```yaml
labels:
  traefik.enable: "true"
  traefik.http.routers.homegap-speedtest.rule: "Host(`speedtest.example.net`) || HostRegexp(`^speedtest-[a-z0-9-]+\\.example\\.net$`)"
  traefik.http.routers.homegap-speedtest.entrypoints: websecure
  traefik.http.routers.homegap-speedtest.tls: "true"
  traefik.http.services.homegap-speedtest.loadbalancer.server.port: "8089"
```

If you also enable the status page, use one rule for both. See [Status page](status-page.md#routing).

## The two tests

The **Speed test** page has two tests. Each test measures ping, jitter, download and upload.

### Homelab ↔ node

The orchestrator runs `curl` on the node over SSH, against port 443 of the node, with the speed test hostname of the node. Thus the traffic takes the production path through the tunnel.

The test uses `speedtest.streams` parallel streams, and the rates are the sum. One stream shows what a single connection gets over the distance. Several streams show the capacity of the tunnel. You can select the number of streams for each run.

To run the test, click **Run test**, or click a node on the map.

### Your device → node → homelab

This test uses a short-lived link that you open on any device. The link is valid for `speedtest.link_ttl`.

1. Click **Create test link**.
2. Click **Copy**.
3. Open the link on the device.

`http://speedtest.<domain>/` goes to the node that DNS selects for the device. That node redirects to its own `speedtest-<node>` hostname. Thus each result belongs to a node.

The token is in the fragment of the URL. The browser never sends the fragment to a server, so no log contains the token.

<!-- SCREENSHOT: docs/img/dashboard/speedtest-results.png | the Speed test page with a homelab to node result and a device link -->

## Results

HomeGAP keeps the results in `<data_dir>/speedtests.json`. The **History** panel shows the recent results.

## API

| Route | What it does |
| --- | --- |
| `GET /api/speedtest` | Returns the settings and the recent results. |
| `POST /api/speedtest/nodes/{id}` | Runs a test between the homelab and a node. The call returns when the test ends. |
| `POST /api/speedtest/link` | Creates a test link for a device. |
