"""Minimal in-memory stand-in for the Bunny DNS API endpoints HomeGAP uses (e2e only)."""
import json, itertools
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ZONE = {"Id": 7, "Domain": "example.net", "Records": []}
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
        if p == "/dnszone": return self._send(200, {"Items": [{"Id": 7, "Domain": "example.net"}]})
        if p == "/dnszone/7":
            for r in ZONE["Records"]:
                r["MonitorStatus"] = 1 if r.get("MonitorType") else 0  # pretend every monitored IP is online
            return self._send(200, ZONE)
        self._send(404, {})

    def do_PUT(self):
        if not self._auth(): return
        if self.path == "/dnszone/7/records":
            r = self._body(); r["Id"] = next(ids); ZONE["Records"].append(r)
            return self._send(201, r)
        self._send(404, {})

    def do_POST(self):
        if not self._auth(): return
        if self.path.startswith("/dnszone/7/records/"):
            rid = int(self.path.rsplit("/", 1)[1]); r = self._body(); r["Id"] = rid
            ZONE["Records"] = [r if x["Id"] == rid else x for x in ZONE["Records"]]
            return self._send(204)
        self._send(404, {})

    def do_DELETE(self):
        if not self._auth(): return
        rid = int(self.path.rsplit("/", 1)[1])
        ZONE["Records"] = [x for x in ZONE["Records"] if x["Id"] != rid]
        self._send(204)

ThreadingHTTPServer(("0.0.0.0", 8080), H).serve_forever()
