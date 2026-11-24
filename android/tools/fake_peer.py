#!/usr/bin/env python3
"""
fake_peer.py — a tiny Pasala peer for testing (Python 3 stdlib only).

Implements docs/PROTOCOL.md v1 from the command line so you can exercise the
Android app without a second device.

State (identity + peers) lives in a JSON file (default: ./.fake_peer.json next to
this script). Use --state to keep several fake peers apart.

Commands
  pair [URI]            Scan-side pairing. URI is the pasala://pair?d=... text.
                        Without URI, the last "QR pasala://..." line is read from
                        `adb logcat` (debug builds log it when the code is shown).
  show-code             Show-side pairing: prints a pasala:// link (open it on the
                        phone with `adb shell am start -a android.intent.action.VIEW -d '<link>'`)
                        and serves until someone pairs with it (or forever with --keep).
  hello [--peer ID]     POST /v1/hello to a peer and print its answer.
  send FILE... [--peer ID] [--collection ID]
                        offer + upload each file to a peer (optionally into one of
                        its collections; see `hello` for the ids it advertises).
  serve [--collections "Fotos,Facturas"]
                        Run the receiving side (hello/offer/files/unpair/pair).
                        Files go to --out (default ./received); a file offered into a
                        collection goes to --out/<collection name>/. Collections are
                        advertised in the hello reply (ids are c-<slug>).
  unpair [--peer ID]    POST /v1/unpair and forget the peer.
  peers                 List known peers.

Common options: --port (default 47821; the real Mac app uses 47820), --name,
--adb-serial (for logcat), --addr (override advertised address list).
"""
import argparse
import base64
import hmac
import http.server
import json
import mimetypes
import os
import re
import secrets
import socket
import socketserver
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_STATE = os.path.join(HERE, ".fake_peer.json")


# ---------- helpers ----------

def b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def b64url_decode(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def log(*a):
    print("[fake_peer]", *a, flush=True)


def local_addrs():
    """LAN IPv4s first, then Tailscale (100.64.0.0/10). Never loopback."""
    lan, ts = [], []
    try:
        out = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5).stdout
        for ip in re.findall(r"inet (\d+\.\d+\.\d+\.\d+)", out):
            p = [int(x) for x in ip.split(".")]
            if p[0] == 127 or (p[0] == 169 and p[1] == 254):
                continue
            if p[0] == 100 and 64 <= p[1] <= 127:
                ts.append(ip)
            elif p[0] == 10 or (p[0] == 172 and 16 <= p[1] <= 31) or (p[0] == 192 and p[1] == 168):
                lan.append(ip)
    except Exception:
        pass
    if not lan:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("192.168.0.1", 9))
            lan.append(s.getsockname()[0])
            s.close()
        except Exception:
            pass
    seen, res = set(), []
    for ip in lan + ts:
        if ip not in seen:
            seen.add(ip)
            res.append(ip)
    return res


class State:
    def __init__(self, path, name=None, port=None, addrs=None):
        self.path = path
        self.lock = threading.Lock()
        data = {}
        if os.path.exists(path):
            with open(path) as f:
                data = json.load(f)
        self.id = data.get("id") or str(uuid.uuid4())
        self.name = name or data.get("name") or "Fake Peer (Mac)"
        self.port = port or data.get("port") or 47821
        self.peers = data.get("peers", {})
        self._addrs = addrs
        self.save()

    @property
    def addrs(self):
        return self._addrs or local_addrs()

    def save(self):
        with open(self.path, "w") as f:
            json.dump({"id": self.id, "name": self.name, "port": self.port, "peers": self.peers}, f, indent=2)

    def me(self):
        return {"id": self.id, "name": self.name, "platform": "mac", "port": self.port, "addrs": self.addrs}

    def pick_peer(self, peer_id=None):
        if peer_id:
            for pid, p in self.peers.items():
                if pid.startswith(peer_id) or p["name"] == peer_id:
                    return pid, p
            sys.exit(f"unknown peer {peer_id}")
        if not self.peers:
            sys.exit("no peers yet; pair first")
        # most recently paired
        pid = max(self.peers, key=lambda k: self.peers[k].get("pairedAt", 0))
        return pid, self.peers[pid]


def http_post(url, body=None, headers=None, timeout=10, data_iter=None, length=None):
    h = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode()
        h.setdefault("Content-Type", "application/json")
    else:
        data = data_iter if data_iter is not None else b""
    if length is not None:
        h["Content-Length"] = str(length)
    req = urllib.request.Request(url, data=data, headers=h, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except Exception:
            return e.code, {"raw": raw.decode(errors="replace")}


def auth(st, peer):
    return {"X-Pasala-From": st.id, "Authorization": "Bearer " + peer["secret"]}


def reach(st, pid, peer):
    """Protocol § Elegir dirección: last good, then stored addrs; hello with 2 s connect timeout."""
    cands = []
    if peer.get("lastGood"):
        cands.append(peer["lastGood"])
    cands += [a for a in peer.get("addrs", []) if a not in cands]
    for host in cands:
        try:
            code, resp = http_post(f"http://{host}:{peer['port']}/v1/hello",
                                   {"name": st.name, "port": st.port, "addrs": st.addrs},
                                   auth(st, peer), timeout=2)
        except Exception as e:
            log(f"hello {host} failed: {e}")
            continue
        if code == 200:
            with st.lock:
                peer.update(name=resp.get("name", peer["name"]), port=resp.get("port", peer["port"]),
                            addrs=resp.get("addrs") or peer["addrs"], lastGood=host, lastSeen=int(time.time() * 1000))
                st.peers[pid] = peer
                st.save()
            return host, resp
        log(f"hello {host} -> {code} {resp}")
    return None, None


# ---------- commands ----------

def cmd_pair(st, args):
    uri = args.uri
    if not uri:
        cmd = ["adb"] + (["-s", args.adb_serial] if args.adb_serial else []) + ["logcat", "-d", "-s", "Pasala"]
        out = subprocess.run(cmd, capture_output=True, text=True).stdout
        found = re.findall(r"QR (pasala://pair\?d=[A-Za-z0-9_\-]+)", out)
        if not found:
            sys.exit("no QR line in logcat; open 'Mostrar mi código' on the phone first")
        uri = found[-1]
        log("using QR from logcat")
    q = urllib.parse.urlparse(uri)
    d = urllib.parse.parse_qs(q.query)["d"][0]
    qr = json.loads(b64url_decode(d))
    log(f"pairing with {qr['name']} ({qr['platform']}) at {qr['addrs']}:{qr['port']}")
    body = {"code": qr["code"], **{k: v for k, v in st.me().items() if k != "platform"}, "platform": "mac"}
    for host in qr["addrs"]:
        try:
            code, resp = http_post(f"http://{host}:{qr['port']}/v1/pair", body, timeout=4)
        except Exception as e:
            log(f"{host}: {e}")
            continue
        if code == 200:
            st.peers[resp["id"]] = {"name": resp["name"], "platform": resp["platform"], "port": resp["port"],
                                   "addrs": resp["addrs"], "secret": resp["secret"],
                                   "pairedAt": int(time.time() * 1000), "lastGood": host}
            st.save()
            log(f"paired OK with {resp['name']} id={resp['id']}")
            return
        log(f"{host}: {code} {resp}")
        if code == 403:
            sys.exit(1)
    sys.exit("pairing failed")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    st: State = None
    out_dir = None
    accepted = {}
    pair_code = None  # (code, expires)
    paired_event = threading.Event()
    ask = False
    collections = []  # [{"id","name"}] advertised in hello

    def log_message(self, fmt, *a):
        pass

    def reply(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def body_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def authed(self):
        pid = self.headers.get("X-Pasala-From", "")
        tok = (self.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
        peer = self.st.peers.get(pid)
        expected = peer["secret"] if peer else "x" * 43
        if not peer or not hmac.compare_digest(expected.encode(), tok.encode()):
            return None, None
        return pid, peer

    def do_POST(self):
        st = self.st
        path = self.path.split("?")[0]
        if path == "/v1/pair":
            b = self.body_json()
            pc = Handler.pair_code
            if not pc or time.time() > pc[1] or not hmac.compare_digest(pc[0].encode(), b.get("code", "").encode()):
                return self.reply(403, {"error": "bad_code"})
            Handler.pair_code = None
            secret = b64url(secrets.token_bytes(32))
            with st.lock:
                st.peers[b["id"]] = {"name": b["name"], "platform": b["platform"], "port": b["port"],
                                     "addrs": b["addrs"], "secret": secret, "pairedAt": int(time.time() * 1000)}
                st.save()
            log(f"paired with {b['name']} ({b['platform']}) id={b['id']} addrs={b['addrs']}")
            self.reply(200, {**{k: v for k, v in st.me().items()}, "secret": secret})
            Handler.paired_event.set()
            return
        pid, peer = self.authed()
        if not peer:
            # drain small bodies
            n = int(self.headers.get("Content-Length") or 0)
            if n < 1 << 20:
                self.rfile.read(n)
            return self.reply(401, {"error": "unauthorized"})
        if path == "/v1/hello":
            b = self.body_json()
            with st.lock:
                peer.update(name=b.get("name", peer["name"]), port=b.get("port", peer["port"]),
                            addrs=b.get("addrs") or peer["addrs"], lastSeen=int(time.time() * 1000))
                st.save()
            log(f"hello from {peer['name']} {b.get('addrs')}")
            return self.reply(200, {**st.me(), "collections": Handler.collections})
        if path == "/v1/offer":
            b = self.body_json()
            ok = True
            if Handler.ask:
                ok = input(f"accept {len(b['files'])} files from {peer['name']}? [Y/n] ").strip().lower() != "n"
            col_id = b.get("collection")
            col = next((c for c in Handler.collections if c["id"] == col_id), None)
            if ok:
                Handler.accepted[b["batch"]] = (pid, col["name"] if col else None)
            where = "none" if col_id is None else (f"{col_id!r} ({col['name']})" if col else f"{col_id!r} (unknown -> default)")
            log(f"offer from {peer['name']}: {[f['name'] for f in b['files']]} collection={where} "
                f"-> {'accepted' if ok else 'declined'}")
            return self.reply(200, {"accepted": ok})
        if path == "/v1/files":
            batch = self.headers.get("X-Pasala-Batch")
            n = int(self.headers.get("Content-Length") or -1)
            acc = Handler.accepted.get(batch)
            if not acc or acc[0] != pid:
                if 0 <= n < 1 << 20:
                    self.rfile.read(n)
                return self.reply(409, {"error": "not_accepted"})
            if n < 0:
                return self.reply(411, {"error": "length_required"})
            name = urllib.parse.unquote(self.headers.get("X-Pasala-Name", "archivo"))
            name = os.path.basename(name.replace("\\", "/")) or "archivo"
            dest = os.path.join(self.out_dir, acc[1]) if acc[1] else self.out_dir
            os.makedirs(dest, exist_ok=True)
            tmp = os.path.join(dest, f".{uuid.uuid4().hex}.part")
            got = 0
            try:
                with open(tmp, "wb") as f:
                    while got < n:
                        chunk = self.rfile.read(min(1 << 20, n - got))
                        if not chunk:
                            break
                        f.write(chunk)
                        got += len(chunk)
            except Exception:
                pass
            if got != n:
                os.unlink(tmp)
                log(f"incomplete {name}: {got}/{n}")
                return self.reply(400, {"error": "incomplete"})
            final = unique(dest, name)
            os.rename(tmp, os.path.join(dest, final))
            mod = self.headers.get("X-Pasala-Modified")
            if mod:
                t = int(mod) / 1000
                os.utime(os.path.join(dest, final), (t, t))
            log(f"received {final} ({n} bytes, {self.headers.get('X-Pasala-Mime')}) "
                f"[{int(self.headers.get('X-Pasala-Index', 0)) + 1}/{self.headers.get('X-Pasala-Count')}] from {peer['name']}")
            return self.reply(200, {"ok": True, "name": final})
        if path == "/v1/unpair":
            with st.lock:
                st.peers.pop(pid, None)
                st.save()
            log(f"{peer['name']} unpaired us")
            return self.reply(200, {"ok": True})
        self.reply(404, {"error": "not_found"})


def unique(d, name):
    if not os.path.exists(os.path.join(d, name)):
        return name
    stem, ext = os.path.splitext(name)
    i = 1
    while os.path.exists(os.path.join(d, f"{stem} ({i}){ext}")):
        i += 1
    return f"{stem} ({i}){ext}"


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def start_server(st, args):
    Handler.st = st
    Handler.out_dir = os.path.abspath(args.out)
    Handler.ask = getattr(args, "ask", False)
    names = [n.strip() for n in (getattr(args, "collections", None) or "").split(",") if n.strip()]
    Handler.collections = [{"id": "c-" + re.sub(r"[^a-z0-9]+", "-", n.lower()).strip("-"), "name": n} for n in names]
    if Handler.collections:
        log(f"advertising collections {Handler.collections}")
    srv = Server(("0.0.0.0", st.port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    log(f"listening on {st.addrs} port {st.port}, saving to {Handler.out_dir}")
    return srv


def cmd_serve(st, args):
    start_server(st, args)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass


def cmd_show_code(st, args):
    start_server(st, args)
    code = b64url(secrets.token_bytes(16))
    Handler.pair_code = (code, time.time() + 300)
    payload = {"v": 1, **st.me(), "code": code}
    uri = "pasala://pair?d=" + b64url(json.dumps(payload, separators=(",", ":")).encode())
    print(uri, flush=True)
    if args.open_on:
        subprocess.run(["adb", "-s", args.open_on, "shell", "am", "start", "-a", "android.intent.action.VIEW",
                        "-d", f"'{uri}'"], check=False)
    ok = Handler.paired_event.wait(300)
    log("paired" if ok else "code expired")
    if args.keep:
        cmd_serve_loop()


def cmd_serve_loop():
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass


def cmd_hello(st, args):
    pid, peer = st.pick_peer(args.peer)
    host, resp = reach(st, pid, peer)
    print(json.dumps({"host": host, "response": resp}, indent=2))
    sys.exit(0 if host else 1)


def cmd_send(st, args):
    pid, peer = st.pick_peer(args.peer)
    host, _ = reach(st, pid, peer)
    if not host:
        sys.exit(f"{peer['name']} is not reachable")
    files = []
    for p in args.files:
        mime = mimetypes.guess_type(p)[0] or "application/octet-stream"
        files.append({"path": p, "name": os.path.basename(p), "size": os.path.getsize(p), "mime": mime})
    batch = str(uuid.uuid4())
    base = f"http://{host}:{peer['port']}"
    offer = {"batch": batch, "files": [{k: f[k] for k in ("name", "size", "mime")} for f in files]}
    if args.collection:
        offer["collection"] = args.collection
    code, resp = http_post(base + "/v1/offer", offer, auth(st, peer), timeout=75)
    log(f"offer -> {code} {resp}")
    if code != 200 or not resp.get("accepted"):
        sys.exit(2)
    for i, f in enumerate(files):
        h = auth(st, peer)
        h.update({
            "Content-Type": "application/octet-stream",
            "X-Pasala-Batch": batch, "X-Pasala-Index": str(i), "X-Pasala-Count": str(len(files)),
            "X-Pasala-Name": urllib.parse.quote(f["name"], safe=""), "X-Pasala-Mime": f["mime"],
            "X-Pasala-Modified": str(int(os.path.getmtime(f["path"]) * 1000)),
        })
        t0 = time.time()
        with open(f["path"], "rb") as fh:
            code, resp = http_post(base + "/v1/files", None, h, timeout=120, data_iter=fh, length=f["size"])
        dt = time.time() - t0
        log(f"{f['name']} -> {code} {resp} ({f['size'] / max(dt, 1e-3) / 1e6:.1f} MB/s)")
        if code != 200:
            sys.exit(3)


def cmd_unpair(st, args):
    pid, peer = st.pick_peer(args.peer)
    host, _ = reach(st, pid, peer)
    if host:
        code, resp = http_post(f"http://{host}:{peer['port']}/v1/unpair", None, auth(st, peer), timeout=5, length=0)
        log(f"unpair -> {code} {resp}")
    st.peers.pop(pid, None)
    st.save()


def cmd_peers(st, args):
    for pid, p in st.peers.items():
        print(f"{pid}  {p['name']:<28} {p['platform']:<8} {p['addrs']}:{p['port']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--state", default=DEFAULT_STATE)
    ap.add_argument("--name")
    ap.add_argument("--port", type=int)
    ap.add_argument("--addr", action="append", help="advertise this address (repeatable)")
    ap.add_argument("--adb-serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--out", default=os.path.join(HERE, "received"))
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pair"); p.add_argument("uri", nargs="?")
    p = sub.add_parser("show-code"); p.add_argument("--keep", action="store_true"); p.add_argument("--open-on", metavar="SERIAL")
    p = sub.add_parser("hello"); p.add_argument("--peer")
    p = sub.add_parser("send"); p.add_argument("files", nargs="+"); p.add_argument("--peer"); p.add_argument("--collection")
    p = sub.add_parser("serve"); p.add_argument("--ask", action="store_true"); p.add_argument("--collections")
    p = sub.add_parser("unpair"); p.add_argument("--peer")
    sub.add_parser("peers")
    args = ap.parse_args()
    st = State(args.state, args.name, args.port, args.addr)
    {"pair": cmd_pair, "show-code": cmd_show_code, "hello": cmd_hello, "send": cmd_send,
     "serve": cmd_serve, "unpair": cmd_unpair, "peers": cmd_peers}[args.cmd](st, args)


if __name__ == "__main__":
    main()
