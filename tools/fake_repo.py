#!/usr/bin/env python3
"""A repository shaped like Artifactory/Nexus: GET, PUT, DELETE under /<repo>/<path>.

    fake_repo.py <port> <log> [--deny-put] [--corrupt-target] [--slow]

--corrupt-target makes reads of the release repository return different bytes, which is how a
repository that accepts a write but stores something else behaves.
"""
import base64
import http.server
import json
import sys
import urllib.parse

port, log_path = int(sys.argv[1]), sys.argv[2]
flags = sys.argv[3:]
deny_put = "--deny-put" in flags
corrupt_target = "--corrupt-target" in flags

STORE = {
    "staging/com/acme/app/1.2.3/app-1.2.3.jar": b"jar-bytes\n",
    "staging/com/acme/app/1.2.3/app-1.2.3.pom": b"<project/>\n",
    "staging/com/acme/app/1.2.3/notes with spaces.txt": b"spaced\n",
}


class Handler(http.server.BaseHTTPRequestHandler):
    def _path(self):
        return urllib.parse.unquote(self.path.lstrip("/"))

    def _log(self, method, extra=""):
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({"method": method, "path": self._path(),
                                  "auth": self.headers.get("Authorization"), "note": extra}) + "\n")

    def do_GET(self):  # noqa: N802
        key = self._path()
        self._log("GET")
        if key not in STORE:
            self.send_response(404)
            self.end_headers()
            return
        body = STORE[key]
        if corrupt_target and key.startswith("release/"):
            body = body + b"tampered"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_PUT(self):  # noqa: N802
        key = self._path()
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        self._log("PUT", str(len(body)))
        if deny_put:
            self.send_response(403)
            self.end_headers()
            self.wfile.write(b"no deploy permission")
            return
        if not key.startswith("release/"):
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"unknown repository")
            return
        STORE[key] = body
        self.send_response(201)
        self.end_headers()

    def do_DELETE(self):  # noqa: N802
        key = self._path()
        self._log("DELETE")
        if key in STORE:
            del STORE[key]
            self.send_response(204)
        else:
            self.send_response(404)
        self.end_headers()

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
