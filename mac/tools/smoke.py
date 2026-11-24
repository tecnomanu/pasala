#!/usr/bin/env python3
"""Smoke test for the Mac app: acts as a fake phone.

Usage: smoke.py <pasala://pair?d=...> <file-to-send> [<file-to-send> ...]
Pairs with the Mac, sends the files to it, then waits for the Mac to send something back
(trigger it with pasala-mcp's pasala_send to device 'Smoke Phone').
"""
import base64, json, os, sys, threading, time, urllib.request, uuid, urllib.parse, mimetypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 47899
ME = {"id": str(uuid.uuid4()), "name": "Smoke Phone", "platform": "android", "port": PORT, "addrs": ["127.0.0.1"]}
received = []

def b64d(s): return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))

def post(url, body=None, headers=None, data=None, timeout=10):
    h = {"Content-Type": "application/json"}; h.update(headers or {})
    req = urllib.request.Request(url, data=data if data is not None else json.dumps(body or {}).encode(), headers=h, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, json.loads(r.read() or b"{}")

payload = json.loads(b64d(urllib.parse.parse_qs(urllib.parse.urlparse(sys.argv[1]).query)["d"][0]))
host = payload["addrs"][0]; base = f"http://{host}:{payload['port']}"
st, peer = post(base + "/v1/pair", {**ME, "code": payload["code"]})
print("paired:", st, peer["name"], peer["platform"])
auth = {"X-Pasala-From": ME["id"], "Authorization": "Bearer " + peer["secret"]}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, code, obj):
        b = json.dumps(obj).encode(); self.send_response(code); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        if self.headers.get("Authorization") != "Bearer " + peer["secret"] or self.headers.get("X-Pasala-From") != peer["id"]:
            return self.reply(401, {"error": "unauthorized"})
        n = int(self.headers.get("Content-Length") or 0); body = self.rfile.read(n)
        if self.path == "/v1/hello": return self.reply(200, ME)
        if self.path == "/v1/offer": return self.reply(200, {"accepted": True})
        if self.path == "/v1/files":
            name = urllib.parse.unquote(self.headers["X-Pasala-Name"]); received.append((name, len(body)))
            print(f"  ← received {name} ({len(body)} bytes)"); return self.reply(200, {"ok": True, "name": name})
        self.reply(404, {"error": "not_found"})

srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
threading.Thread(target=srv.serve_forever, daemon=True).start()

print("hello:", post(base + "/v1/hello", {"name": ME["name"], "port": PORT, "addrs": ["127.0.0.1"]}, auth)[1]["name"])
files = sys.argv[2:]
batch = str(uuid.uuid4())
offer = [{"name": os.path.basename(f), "size": os.path.getsize(f), "mime": mimetypes.guess_type(f)[0] or "application/octet-stream"} for f in files]
print("offer:", post(base + "/v1/offer", {"batch": batch, "files": offer}, auth, timeout=70)[1])
for i, f in enumerate(files):
    with open(f, "rb") as fh: data = fh.read()
    h = {**auth, "Content-Type": "application/octet-stream", "X-Pasala-Batch": batch, "X-Pasala-Index": str(i),
         "X-Pasala-Count": str(len(files)), "X-Pasala-Name": urllib.parse.quote(os.path.basename(f)), "X-Pasala-Mime": offer[i]["mime"]}
    # send in slices so the Mac shows progress
    print("  → sent", post(base + "/v1/files", headers=h, data=data, timeout=120)[1])
    time.sleep(0.6)

wait = int(os.environ.get("SMOKE_WAIT", "40"))
print(f"waiting {wait}s for the Mac to send something back…", flush=True)
t0 = time.time()
while time.time() - t0 < wait and not received: time.sleep(0.5)
print("received back:", received)
