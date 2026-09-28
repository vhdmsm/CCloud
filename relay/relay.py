"""
CCloud API relay: forwards the app's TMDB and OMDb requests from a server outside Iran.

The API keys live here (environment variables), not in the app. Only the few endpoints the app
uses are forwarded, answers are cached in SQLite so repeated lookups (from any user) cost no
upstream request, and each client IP is rate limited.
"""

import json
import os
import re
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TMDB_BASE = os.environ.get("TMDB_BASE", "https://api.themoviedb.org/3")
OMDB_BASE = os.environ.get("OMDB_BASE", "https://www.omdbapi.com/")
TMDB_API_KEY = os.environ.get("TMDB_API_KEY", "")
OMDB_API_KEYS = [k for k in (os.environ.get(n, "") for n in ("OMDB_API_KEY", "OMDB_API_KEY2", "OMDB_API_KEY3")) if k]
# Optional shared token the app sends in X-App-Token; keeps random scanners out
RELAY_TOKEN = os.environ.get("RELAY_TOKEN", "")
CACHE_PATH = os.environ.get("CACHE_PATH", "/data/cache.db")
PORT = int(os.environ.get("PORT", "8080"))
# Requests per client IP per minute
RATE_LIMIT = int(os.environ.get("RATE_LIMIT_PER_MINUTE", "120"))

DAY = 24 * 60 * 60
TMDB_TTL = DAY
OMDB_TTL = 30 * DAY
NOT_FOUND_TTL = DAY
# A key over its daily limit is tried again after this
OMDB_KEY_BACKOFF = 3 * 60 * 60
UPSTREAM_TIMEOUT = 15

# Only what the app asks for: movie search and movie details (with credits)
TMDB_PATHS = [re.compile(r"^search/movie$"), re.compile(r"^movie/\d+$")]
TMDB_PARAMS = {"query", "year", "include_adult", "append_to_response", "language", "page"}
IMDB_ID = re.compile(r"^tt\d{5,10}$")


class Cache:
    def __init__(self, path):
        directory = os.path.dirname(path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.lock = threading.Lock()
        with self.lock:
            self.db.execute(
                "CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY, status INTEGER, body BLOB, expires INTEGER)"
            )
            self.db.commit()

    def get(self, key):
        with self.lock:
            row = self.db.execute("SELECT status, body, expires FROM cache WHERE key = ?", (key,)).fetchone()
        if row is None or row[2] < time.time():
            return None
        return row[0], row[1]

    def put(self, key, status, body, ttl):
        with self.lock:
            self.db.execute(
                "INSERT OR REPLACE INTO cache (key, status, body, expires) VALUES (?, ?, ?, ?)",
                (key, status, body, int(time.time() + ttl)),
            )
            self.db.commit()


class RateLimiter:
    def __init__(self, per_minute):
        self.per_minute = per_minute
        self.lock = threading.Lock()
        self.windows = {}

    def allow(self, ip):
        minute = int(time.time() // 60)
        with self.lock:
            if len(self.windows) > 10000:
                self.windows = {k: v for k, v in self.windows.items() if v[0] == minute}
            window, count = self.windows.get(ip, (minute, 0))
            if window != minute:
                window, count = minute, 0
            if count >= self.per_minute:
                return False
            self.windows[ip] = (window, count + 1)
            return True


cache = None
limiter = RateLimiter(RATE_LIMIT)
omdb_blocked_until = {}
omdb_lock = threading.Lock()


def fetch(url, headers=None):
    request = urllib.request.Request(url, headers={"Accept": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=UPSTREAM_TIMEOUT) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def tmdb(path, query):
    if not TMDB_API_KEY:
        return 503, {"status_message": "TMDB is not configured on the relay"}
    if not any(p.match(path) for p in TMDB_PATHS):
        return 404, {"status_message": "Not available through the relay"}
    params = sorted((k, v) for k, v in query if k in TMDB_PARAMS)
    key = "tmdb:" + path + "?" + urllib.parse.urlencode(params)
    cached = cache.get(key)
    if cached:
        return cached

    headers = {}
    # A v4 read access token (a JWT) goes in the header, a v3 key in the URL
    if TMDB_API_KEY.startswith("eyJ"):
        headers["Authorization"] = "Bearer " + TMDB_API_KEY
        upstream_params = params
    else:
        upstream_params = params + [("api_key", TMDB_API_KEY)]
    status, body = fetch(f"{TMDB_BASE}/{path}?{urllib.parse.urlencode(upstream_params)}", headers)
    if status == 200:
        cache.put(key, status, body, TMDB_TTL)
    elif status == 404:
        cache.put(key, status, body, NOT_FOUND_TTL)
    elif status == 401:
        # Don't pass on the upstream message, it would describe the relay's key
        return 502, {"status_message": "The relay's TMDB key was rejected"}
    return status, body


def omdb(query):
    imdb_id = dict(query).get("i", "")
    if not IMDB_ID.match(imdb_id):
        return 400, {"Response": "False", "Error": "Only lookups by IMDb id are available"}
    key = "omdb:" + imdb_id
    cached = cache.get(key)
    if cached:
        return cached

    for api_key in OMDB_API_KEYS:
        with omdb_lock:
            if omdb_blocked_until.get(api_key, 0) > time.time():
                continue
        status, body = fetch(OMDB_BASE + "?" + urllib.parse.urlencode({"i": imdb_id, "apikey": api_key}))
        try:
            data = json.loads(body)
        except ValueError:
            return 502, {"Response": "False", "Error": f"OMDb error {status}"}
        error = data.get("Error", "")
        if data.get("Response") != "True" and ("limit" in error.lower() or "api key" in error.lower()):
            # Daily limit or a wrong key: move on to the next key
            with omdb_lock:
                omdb_blocked_until[api_key] = time.time() + OMDB_KEY_BACKOFF
            continue
        # Found, or not an OMDb movie ("Incorrect IMDb ID."): both are worth keeping
        cache.put(key, 200, body, OMDB_TTL)
        return 200, body
    # Every key is over its limit (or none is set); the app backs off on this message
    return 503, {"Response": "False", "Error": "Request limit reached!"}


class Handler(BaseHTTPRequestHandler):
    server_version = "CCloudRelay"

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        if url.path == "/health":
            return self.reply(200, {"status": "ok"})
        if RELAY_TOKEN and self.headers.get("X-App-Token") != RELAY_TOKEN:
            return self.reply(403, {"error": "Forbidden"})
        if not limiter.allow(self.client_ip()):
            return self.reply(429, {"error": "Too many requests"})

        query = urllib.parse.parse_qsl(url.query)
        try:
            if url.path.startswith("/tmdb/"):
                status, body = tmdb(url.path[len("/tmdb/"):], query)
            elif url.path in ("/omdb", "/omdb/"):
                status, body = omdb(query)
            else:
                status, body = 404, {"error": "Not found"}
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            status, body = 504, {"error": f"Upstream unreachable: {error}"}
        self.reply(status, body)

    def client_ip(self):
        # Behind Cloudflare the visitor's address is in CF-Connecting-IP; Caddy sets X-Forwarded-For
        return (
            self.headers.get("CF-Connecting-IP")
            or self.headers.get("X-Forwarded-For", "").split(",")[0].strip()
            or self.client_address[0]
        )

    def reply(self, status, body):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        # Log the path without query strings
        print(f"{self.command} {urllib.parse.urlsplit(self.path).path} -> {args[1] if len(args) > 1 else ''}", flush=True)


def main():
    global cache
    cache = Cache(CACHE_PATH)
    print(f"CCloud relay on :{PORT} (TMDB {'on' if TMDB_API_KEY else 'off'}, {len(OMDB_API_KEYS)} OMDb keys)", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
