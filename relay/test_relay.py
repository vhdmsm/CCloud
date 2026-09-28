"""Runs the relay against a fake OMDb server: python3 -m unittest test_relay.py"""

import importlib
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

upstream_calls = []


class FakeUpstream(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        query = dict(urllib.parse.parse_qsl(url.query))
        upstream_calls.append((url.path, query))
        # OMDb: the first key is over its daily limit, the second works
        if query.get("apikey") == "omdb-1":
            return self.reply(401, {"Response": "False", "Error": "Request limit reached!"})
        return self.reply(200, {"Response": "True", "Awards": "Won 3 Oscars.", "imdbID": query.get("i")})

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def serve(handler):
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


class RelayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        upstream = serve(FakeUpstream)
        base = f"http://127.0.0.1:{upstream.server_port}"
        os.environ.update({
            "OMDB_BASE": base + "/omdb/",
            "OMDB_API_KEY": "omdb-1",
            "OMDB_API_KEY2": "omdb-2",
            "RELAY_TOKEN": "app-token",
            "CACHE_PATH": os.path.join(tempfile.mkdtemp(), "cache.db"),
            "RATE_LIMIT_PER_MINUTE": "1000",
        })
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        cls.relay = importlib.import_module("relay")
        cls.relay.cache = cls.relay.Cache(os.environ["CACHE_PATH"])
        cls.server = serve(cls.relay.Handler)
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    def setUp(self):
        upstream_calls.clear()

    def get(self, path, token="app-token", ip=None):
        headers = {"X-App-Token": token} if token else {}
        if ip:
            headers["CF-Connecting-IP"] = ip
        try:
            with urllib.request.urlopen(urllib.request.Request(self.base + path, headers=headers)) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_omdb_moves_to_the_next_key_at_the_limit(self):
        status, body = self.get("/omdb/?i=tt0133093")
        self.assertEqual(200, status)
        self.assertEqual("Won 3 Oscars.", body["Awards"])
        self.assertEqual(["omdb-1", "omdb-2"], [q["apikey"] for _, q in upstream_calls])
        # The limited key is skipped afterwards, and cached answers need no request
        self.get("/omdb/?i=tt0133094")
        self.get("/omdb/?i=tt0133093")
        self.assertEqual(["omdb-1", "omdb-2", "omdb-2"], [q["apikey"] for _, q in upstream_calls])

    def test_omdb_only_by_imdb_id(self):
        self.assertEqual(400, self.get("/omdb/?t=Matrix")[0])
        self.assertEqual(400, self.get("/omdb/?i=tt1&apikey=x")[0])
        self.assertEqual([], upstream_calls)

    def test_needs_the_app_token(self):
        self.assertEqual(403, self.get("/omdb/?i=tt0133093", token=None)[0])
        self.assertEqual(403, self.get("/omdb/?i=tt0133093", token="wrong")[0])
        self.assertEqual(200, self.get("/health", token=None)[0])

    def test_rate_limits_each_ip(self):
        limiter = self.relay.limiter
        old = limiter.per_minute
        limiter.per_minute = 2
        try:
            self.assertEqual(200, self.get("/omdb/?i=tt0133093", ip="10.0.0.9")[0])
            self.assertEqual(200, self.get("/omdb/?i=tt0133093", ip="10.0.0.9")[0])
            self.assertEqual(429, self.get("/omdb/?i=tt0133093", ip="10.0.0.9")[0])
            self.assertEqual(200, self.get("/omdb/?i=tt0133093", ip="10.0.0.8")[0])
        finally:
            limiter.per_minute = old


if __name__ == "__main__":
    unittest.main()
