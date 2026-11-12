package homegap.provision

import homegap.brand.Brand
import homegap.model.NodeDesired
import homegap.model.ResourceMode
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The page a node shows while the homelab is unreachable (its internet is down, or it is
 * restarting or updating), instead of an error.
 *
 * The page covers only names for which the node ends TLS: a service in `terminate` mode with the
 * page on, and the shared status and speed-test names. A passthrough name never gets the page,
 * because the node holds no certificate for it.
 *
 * A small `maintenance` service on each node checks every few seconds that the homelab answers
 * through the tunnel, at the addresses of the services that Pangolin last generated. When no
 * address answers a few times in a row, it drops [routes] into Traefik's dynamic directory: one
 * HTTP router for the covered names, above every other router, that sends each request to the
 * service. The service answers with the page and a 503. Once the homelab answers again, the
 * service removes the file. HomeGAP's own routing file is never touched, so the override can only
 * ever add the page.
 *
 * For a passthrough name, the override closes the connection at once and does not end TLS. The
 * browser then shows its error without a wait for a timeout.
 */
object MaintenancePage {
    const val SERVICE = "maintenance"
    const val PORT = 8090
    /** Where HomeGAP writes the service's files on the node (not watched by Traefik). */
    const val DIR = "config/maintenance"
    const val ROUTES = "$DIR/routes.yml"
    /** The file the service adds to (and removes from) Traefik's dynamic directory. */
    const val ACTIVE_NAME = "homegap-maintenance.yml"

    /** Above every other router of the node. */
    private const val PRIORITY = 1_000_000

    const val PROBES = "$DIR/probe.json"
    private const val CLOSED_PORT = 9

    /**
     * What the service checks: each resource that reaches the homelab (found in Pangolin's
     * generated routes as `<id>-<niceId>-service`), with a name to send as SNI. Empty when there is
     * nothing to cover.
     */
    fun probes(desired: NodeDesired, resourceIds: Map<String, Int>): String = buildJsonObject {
        putJsonArray("targets") {
            if (covers(desired, resourceIds)) for ((niceId, sni) in targets(desired, resourceIds)) {
                add(buildJsonObject { put("service_suffix", "-$niceId-service"); put("sni", sni) })
            }
        }
    }.toString() + "\n"

    /** The resources that reach the homelab and exist in Pangolin: niceId to a name for SNI. */
    private fun targets(desired: NodeDesired, resourceIds: Map<String, Int>): List<Pair<String, String>> {
        val passthrough = desired.sniRoutes.mapNotNull { r ->
            val sni = r.exactHosts.firstOrNull { it !in r.excludeHosts } ?: r.wildcardOf?.let { "homegap-probe.$it" }
            sni?.let { r.resourceNiceId to it }
        }
        val terminated = desired.publicResources.filter { it.mode == ResourceMode.HTTP && it.ssl && it.fullDomain != null }
            .map { it.niceId to (it.tlsServerName ?: it.fullDomain!!.replace("*.", "homegap-probe.")) }
        return (passthrough + terminated).filter { resourceIds[it.first] != null }
    }

    private fun covers(desired: NodeDesired, resourceIds: Map<String, Int>) =
        (desired.maintenanceHosts.isNotEmpty() || desired.sniRoutes.isNotEmpty()) && targets(desired, resourceIds).isNotEmpty()

    /** The Traefik override, or an empty file when there is nothing to cover. */
    fun routes(desired: NodeDesired, resourceIds: Map<String, Int>): String {
        val hosts = desired.maintenanceHosts
        if (!covers(desired, resourceIds)) return "# Managed by HomeGAP: nothing for the maintenance page to cover on this node.\n"
        val passthrough = desired.sniRoutes.filter { resourceIds[it.resourceNiceId] != null }
        val matches = hosts.map { if (it.startsWith("*.")) "HostRegexp(`${NodeTemplates.wildcardRegex(it.removePrefix("*."))}`)" else "Host(`$it`)" }
        // Pangolin's own pages on the node do not need the homelab.
        val own = listOfNotNull(desired.managementHost, desired.clientHost).filter { it !in hosts }
        val rule = "(${matches.joinToString(" || ")})" + own.joinToString("") { " && !Host(`$it`)" }
        val root = buildJsonObject {
            if (hosts.isNotEmpty()) putJsonObject("http") {
                putJsonObject("routers") {
                    putJsonObject("homegap-maintenance") {
                        putJsonArray("entryPoints") { add("websecure") }
                        put("rule", rule)
                        put("service", "homegap-maintenance")
                        put("priority", PRIORITY)
                        putJsonObject("tls") {}
                    }
                }
                putJsonObject("services") {
                    putJsonObject("homegap-maintenance") {
                        putJsonObject("loadBalancer") { putJsonArray("servers") { add(buildJsonObject { put("url", "http://127.0.0.1:$PORT") }) } }
                    }
                }
            }
            if (passthrough.isNotEmpty()) putJsonObject("tcp") {
                putJsonObject("routers") {
                    for (r in passthrough) putJsonObject("${r.name}-unreachable") {
                        putJsonArray("entryPoints") { add("websecure") }
                        put("rule", NodeTemplates.sniRule(r))
                        put("service", "homegap-unreachable")
                        put("priority", r.priority + PRIORITY)
                        // The node does not end TLS here: it holds no certificate for these names.
                        putJsonObject("tls") { put("passthrough", true) }
                    }
                }
                putJsonObject("services") {
                    // Nothing listens on this port, so Traefik closes the connection of the viewer.
                    putJsonObject("homegap-unreachable") {
                        putJsonObject("loadBalancer") { putJsonArray("servers") { add(buildJsonObject { put("address", "127.0.0.1:$CLOSED_PORT") }) } }
                    }
                }
            }
        }
        return YamlEmitter.emit(root, "Managed by HomeGAP: added to Traefik by the maintenance service only while the homelab is unreachable.")
    }

    /** The service: probe loop plus a tiny HTTP server, standard library only. */
    val SCRIPT = $$"""
        |# Managed by HomeGAP: shows the maintenance page while the homelab is unreachable.
        |import concurrent.futures, http.client, http.server, json, os, re, shutil, socket, socketserver, ssl, threading, time
        |
        |DIR = "/maintenance"
        |DYNAMIC = "/dynamic"
        |ACTIVE = os.path.join(DYNAMIC, "$$ACTIVE_NAME")
        |INTERVAL, DOWN_AFTER, UP_AFTER = 2.0, 3, 2
        |
        |def log(msg):
        |    print(time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), msg, flush=True)
        |
        |def load(name, default):
        |    try:
        |        with open(os.path.join(DIR, name)) as f:
        |            return json.load(f)
        |    except Exception:
        |        return default
        |
        |def pangolin_routes():
        |    # A cluster's Pangolin writes Traefik's config to a file; a standalone one serves it.
        |    try:
        |        with open(os.path.join(DYNAMIC, "router_config.yml")) as f:
        |            return f.read()
        |    except OSError:
        |        pass
        |    try:
        |        c = http.client.HTTPConnection("pangolin", 3001, timeout=4)
        |        c.request("GET", "/api/v1/traefik-config")
        |        body = c.getresponse().read().decode()
        |        c.close()
        |        return body
        |    except Exception:
        |        return ""
        |
        |def addresses(routes, suffix):
        |    # The servers of the first service named <id><suffix>, from YAML or JSON.
        |    m = re.search(r'"?\d+' + re.escape(suffix) + r'"?\s*:', routes)
        |    if not m:
        |        return []
        |    block = routes[m.end():]
        |    nxt = re.search(r'"?[\w-]+-service"?\s*:', block)
        |    block = block[: nxt.start()] if nxt else block
        |    return re.findall(r'"?(?:address|url)"?\s*:\s*"?([^"\s,}]+)', block)
        |
        |def handshake(address, sni):
        |    # A TCP service has host:port and carries TLS. An HTTP service has a URL.
        |    scheme, _, rest = address.rpartition("://")
        |    host, _, port = rest.rstrip("/").rpartition(":")
        |    try:
        |        with socket.create_connection((host.strip("[]"), int(port)), timeout=3) as raw:
        |            if scheme == "http":
        |                return True
        |            ctx = ssl.create_default_context()
        |            ctx.check_hostname = False
        |            ctx.verify_mode = ssl.CERT_NONE
        |            with ctx.wrap_socket(raw, server_hostname=sni):
        |                return True
        |    except Exception:
        |        return False
        |
        |def homelab_up(targets):
        |    # Up when any service reaches the homelab (a TLS handshake, or a connection for http). A service
        |    # Pangolin has dropped (its targets failed their health checks) counts as down.
        |    # The checks run side by side, so an unreachable homelab is noticed in one timeout.
        |    routes = pangolin_routes()
        |    checks = [(a, t["sni"]) for t in targets for a in addresses(routes, t["service_suffix"])]
        |    if not checks:
        |        return False
        |    with concurrent.futures.ThreadPoolExecutor(len(checks)) as pool:
        |        return any(pool.map(lambda c: handshake(*c), checks))
        |
        |def activate():
        |    tmp = ACTIVE + ".tmp"
        |    shutil.copyfile(os.path.join(DIR, "routes.yml"), tmp)
        |    os.replace(tmp, ACTIVE)
        |
        |def current():
        |    # True if Traefik has the routes that HomeGAP wrote last.
        |    try:
        |        with open(ACTIVE, "rb") as a, open(os.path.join(DIR, "routes.yml"), "rb") as b:
        |            return a.read() == b.read()
        |    except OSError:
        |        return False
        |
        |def deactivate():
        |    try:
        |        os.remove(ACTIVE)
        |    except FileNotFoundError:
        |        pass
        |
        |def watch():
        |    down = os.path.exists(ACTIVE)
        |    fails = oks = 0
        |    while True:
        |        targets = load("probe.json", {}).get("targets", [])
        |        up = homelab_up(targets) if targets else True
        |        fails, oks = (0, oks + 1) if up else (fails + 1, 0)
        |        try:
        |            if not down and fails >= DOWN_AFTER:
        |                activate(); down = True
        |                log("homelab unreachable: showing the maintenance page")
        |            elif down and oks >= UP_AFTER:
        |                deactivate(); down = False
        |                log("homelab reachable again: maintenance page removed")
        |            elif down and not current():
        |                activate()
        |        except OSError as e:
        |            log(f"could not update {ACTIVE}: {e}")
        |        time.sleep(INTERVAL)
        |
        |class Page(http.server.BaseHTTPRequestHandler):
        |    protocol_version = "HTTP/1.1"
        |    server_version = "homegap"
        |    sys_version = ""
        |
        |    def answer(self, body):
        |        self.send_response(503)
        |        self.send_header("Content-Type", "text/html; charset=utf-8")
        |        self.send_header("Content-Length", str(len(body)))
        |        self.send_header("Cache-Control", "no-store")
        |        self.send_header("Retry-After", "30")
        |        self.send_header("Connection", "close")
        |        self.end_headers()
        |        return body
        |
        |    def do_GET(self):
        |        with open(os.path.join(DIR, "index.html"), "rb") as f:
        |            self.wfile.write(self.answer(f.read()))
        |
        |    def do_HEAD(self):
        |        with open(os.path.join(DIR, "index.html"), "rb") as f:
        |            self.answer(f.read())
        |
        |    do_POST = do_PUT = do_PATCH = do_DELETE = do_OPTIONS = do_GET
        |
        |    def log_message(self, *args):
        |        pass
        |
        |class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
        |    daemon_threads = True
        |    allow_reuse_address = True
        |
        |threading.Thread(target=watch, daemon=True).start()
        |log("maintenance page ready on :$$PORT")
        |Server(("127.0.0.1", $$PORT), Page).serve_forever()
        |""".trimMargin()

    /**
     * The page itself: static HTML and CSS, no scripts. It reloads itself every 30 seconds. The
     * HomeGAP emblem stays a small mark in the footer and there is no favicon: visitors see this
     * page under the site's own name.
     */
    fun page(domain: String, node: String): String = $$"""
        |<!doctype html>
        |<html lang="en">
        |<head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<meta http-equiv="refresh" content="30">
        |<meta name="robots" content="noindex">
        |<meta name="color-scheme" content="dark">
        |<meta name="darkreader-lock">
        |<title>Back shortly</title>
        |<style>
        |:root {
        |  --bg: #070A1F; --surface: #0A0E2A; --text: #E6E8F5; --text-2: #C3C8E6; --muted: #A9AFD6;
        |  --border: #1E2452; --warn: #FFC46B; --warn-soft: rgba(255, 196, 107, .12);
        |  --shadow: 0 24px 64px rgba(0, 0, 0, .5);
        |  --sans: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
        |  color-scheme: dark;
        |}
        |* { box-sizing: border-box; }
        |body {
        |  margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 24px 16px;
        |  background-color: var(--bg); color: var(--text); font: 15px/1.6 var(--sans); -webkit-font-smoothing: antialiased;
        |  background-image: linear-gradient(#0C1131 1px, transparent 1px), linear-gradient(90deg, #0C1131 1px, transparent 1px); background-size: 32px 32px;
        |}
        |main {
        |  width: 100%; max-width: 440px; padding: 32px; border-radius: 14px;
        |  background: var(--surface); border: 1px solid var(--border); box-shadow: var(--shadow);
        |}
        |.status {
        |  display: inline-flex; align-items: center; gap: 8px; padding: 3px 10px; border-radius: 999px;
        |  background: var(--warn-soft); color: var(--warn); font-size: 12.5px; font-weight: 600;
        |}
        |.dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; animation: pulse 1.6s ease-in-out infinite; }
        |@keyframes pulse { 50% { opacity: .35; } }
        |@media (prefers-reduced-motion: reduce) { .dot { animation: none; } }
        |h1 { margin: 18px 0 8px; font-size: 24px; line-height: 1.25; letter-spacing: -.02em; }
        |p { margin: 0 0 12px; color: var(--text-2); }
        |footer {
        |  margin-top: 22px; padding-top: 14px; border-top: 1px solid var(--border);
        |  display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; color: var(--muted); font-size: 12.5px;
        |}
        |.by { display: inline-flex; align-items: center; gap: 6px; }
        |.hg-mark { width: 14px; height: 14px; flex: none; }
        |</style>
        |</head>
        |<body>
        |<main>
        |  <span class="status"><span class="dot"></span>Temporarily unavailable</span>
        |  <h1>Back shortly</h1>
        |  <p>$${escape(domain)} can't be reached right now. It is most likely restarting or being updated, and should be back in a few minutes.</p>
        |  <p>This page checks again every 30 seconds and shows the site as soon as it returns.</p>
        |  <footer><span>$${escape(domain)}</span><span class="by">$${Brand.MARK_SVG}Served by $${escape(node)}</span></footer>
        |</main>
        |</body>
        |</html>
        |""".trimMargin()

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
