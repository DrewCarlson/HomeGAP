"""Minimal in-memory stand-in for the Bunny DNS API endpoints HomeGAP uses (e2e only)."""
import json, itertools
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# The zone of the platform domain, and the zone of the extra domain of the harness.
ZONES = {
    7: {"Id": 7, "Domain": "example.net", "Records": []},
    8: {"Id": 8, "Domain": "other.example.org", "Records": []},
}
ids = itertools.count(100)

class H(BaseHTTPRequestHandler):
    def _send(self, code, body=None):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def _auth(self):
        if self.headers.get("AccessKey") != "e2e-bunny-key":
            self._send(401, {"Message": "unauthorized"}); return False
        return True

    def do_GET(self):
        if not self._auth(): return
        p = self.path.split("?")[0]
        if p == "/dnszone":
            return self._send(200, {"Items": [{"Id": z["Id"], "Domain": z["Domain"]} for z in ZONES.values()]})
        zone = self._zone(p)
        if zone is not None and p == "/dnszone/%d" % zone["Id"]:
            for r in zone["Records"]:
                r["MonitorStatus"] = 1 if r.get("MonitorType") else 0  # pretend every monitored IP is online
            return self._send(200, zone)
        self._send(404, {})

    def _zone(self, path):
        """The zone that a /dnszone/<id>/... path names, or None."""
        parts = path.split("/")
        if len(parts) < 3 or parts[1] != "dnszone" or not parts[2].isdigit(): return None
        return ZONES.get(int(parts[2]))

    def do_PUT(self):
        if not self._auth(): return
        zone = self._zone(self.path)
        if zone is not None and self.path == "/dnszone/%d/records" % zone["Id"]:
            r = self._body(); r["Id"] = next(ids); zone["Records"].append(r)
            return self._send(201, r)
        self._send(404, {})

    def do_POST(self):
        if not self._auth(): return
        zone = self._zone(self.path)
        if zone is not None and self.path.startswith("/dnszone/%d/records/" % zone["Id"]):
            rid = int(self.path.rsplit("/", 1)[1]); r = self._body(); r["Id"] = rid
            zone["Records"] = [r if x["Id"] == rid else x for x in zone["Records"]]
            return self._send(204)
        self._send(404, {})

    def do_DELETE(self):
        if not self._auth(): return
        zone = self._zone(self.path)
        if zone is None: return self._send(404, {})
        rid = int(self.path.rsplit("/", 1)[1])
        zone["Records"] = [x for x in zone["Records"] if x["Id"] != rid]
        self._send(204)

ThreadingHTTPServer(("0.0.0.0", 8080), H).serve_forever()
