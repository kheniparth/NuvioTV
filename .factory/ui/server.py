#!/usr/bin/env python3
"""Minimal local factory UI: one file, Python standard library only, no Docker, no database.
Reads the router's existing state folder and history log; freezing a worker creates/deletes its FREEZE file.
Run from WSL:  python3 .factory/ui/server.py   then open http://127.0.0.1:8787
Env: FACTORY_STATE (state folder), FACTORY_HISTORY (history log), FACTORY_PORT (default 8787),
FACTORY_HOST (default 127.0.0.1; use 0.0.0.0 or a Tailscale IP to reach it from another machine) and
FACTORY_TOKEN (required whenever FACTORY_HOST is not 127.0.0.1: open /?token=<token> once, a cookie remembers it).
On Windows Python the defaults use %USERPROFILE%, so it can run on the machine that holds the state (no WSL needed).
"""
import datetime, glob, hmac, json, os, re, sys, time
from urllib.parse import parse_qs, urlparse
from http.server import BaseHTTPRequestHandler, HTTPServer

HOME = os.environ.get("FACTORY_WIN_HOME") or (os.environ["USERPROFILE"].replace("\\", "/") if os.name == "nt" else "/mnt/c/Users/kheni")
STATE = os.environ.get("FACTORY_STATE", f"{HOME}/.claude/scheduled-tasks/nuvio-tv/dispatch")
HISTORY = os.environ.get("FACTORY_HISTORY", f"{HOME}/.nuvio/nuvio-tv-history.log")
PORT = int(os.environ.get("FACTORY_PORT", "8787"))
HOST = os.environ.get("FACTORY_HOST", "127.0.0.1")
TOKEN = os.environ.get("FACTORY_TOKEN", "")
HERE = os.path.dirname(os.path.abspath(__file__))
WORKERS_JSON = os.path.join(HERE, "..", "workers.json")


def workers():
    try:
        with open(WORKERS_JSON, encoding="utf-8") as f:
            return json.load(f)["workers"]
    except Exception:
        return {"cursor": {"freezeFile": "FREEZE"}, "deepseek": {"freezeFile": "FREEZE-deepseek"}}


def workers_full():
    try:
        with open(WORKERS_JSON, encoding="utf-8") as f:
            return json.load(f)["workers"]
    except Exception:
        return {}


def schedule_freeze(w, now=None):
    """Reason text when the worker's schedule in workers.json freezes it right now (e.g. peak pricing), else None.
    Same rule as Get-ScheduleFreeze in workers.ps1; separate from the manual FREEZE files."""
    sch = (w or {}).get("schedule")
    if not sch:
        return None
    now = now or datetime.datetime.now(datetime.timezone.utc)
    if sch.get("timezone", "UTC").upper() != "UTC":
        try:
            from zoneinfo import ZoneInfo
            now = now.astimezone(ZoneInfo(sch["timezone"]))
        except Exception:
            pass
    if now.strftime("%Y-%m-%d") in (sch.get("offPeakDates") or []):
        return None
    t = now.hour * 60 + now.minute
    for rule in sch.get("freeze", []):
        if rule.get("days") and now.strftime("%a") not in rule["days"]:
            continue
        for span in rule.get("hours", []):
            a, b = (sum(int(x) * m for x, m in zip(p.split(":"), (60, 1))) for p in span.split("-"))
            if a <= t < b:
                reason = sch.get("reason", "scheduled")
                try:  # say when it ends in the owner's own timezone (schedule.displayTimezone), if known
                    from zoneinfo import ZoneInfo
                    end = now.replace(hour=b // 60, minute=b % 60, second=0, microsecond=0)
                    local = end.astimezone(ZoneInfo(sch["displayTimezone"]))
                    reason += f", until {local.strftime('%H:%M')} {local.tzname()}"
                except Exception:
                    pass
                return reason
    return None


def read_json(path):
    try:
        with open(path, encoding="utf-8-sig") as f:
            return json.load(f)
    except Exception:
        return None


def parse_history(limit=400):
    """Group history lines into router runs (a timestamp line starts a run) and pull out worker starts."""
    try:
        with open(HISTORY, encoding="utf-8-sig", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        return []
    runs, cur = [], None
    for ln in lines:
        if re.match(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d", ln.strip()):
            cur = {"time": ln.strip(), "dispatch": "", "review": "", "worker": None}
            runs.append(cur)
        elif cur is not None:
            m = re.search(r"dispatch: started: (.*?) \[(\w+), model ([^\]]+)\]", ln)
            if m:
                cur["worker"] = {"what": m.group(1), "worker": m.group(2), "model": m.group(3)}
            m = re.match(r"^(?:router|tick): dispatch=\[(.*?)\] review=\[(.*)\]$", ln)
            if m:
                cur["dispatch"], cur["review"] = m.group(1), m.group(2)
    return runs[-limit:]


def log_usage(path):
    """Token usage from a worker log written with --output-format json (DeepSeek runs); None for text logs (Cursor)."""
    try:
        with open(path, encoding="utf-8-sig", errors="replace") as f:
            head = f.read(2_000_000).strip()
        if not head.startswith(("{", "[")):
            return None
        data = json.loads(head)
        if isinstance(data, list):
            data = next((x for x in reversed(data) if isinstance(x, dict) and "usage" in x), {})
        u = data.get("usage") or {}
        return {"in": (u.get("input_tokens") or 0) + (u.get("cache_creation_input_tokens") or 0) + (u.get("cache_read_input_tokens") or 0),
                "out": u.get("output_tokens") or 0, "turns": data.get("num_turns")}
    except Exception:
        return None


def fmt_tokens(t):
    if not t:
        return None
    k = lambda n: f"{n/1000:.1f}k" if n >= 1000 else str(n)
    return f"{k(t['in'])} in / {k(t['out'])} out"


def worker_runs():
    """Past worker runs from the per-run log files: name, start, duration (log mtime - start), size."""
    out = []
    for p in glob.glob(os.path.join(STATE, "*-*-*-20*.log")):
        m = re.match(r"(.+?)-(issue|fix)-(\d+)-(\d{8})-(\d{6})\.log$", os.path.basename(p))
        if not m:
            continue
        start = time.mktime(time.strptime(m.group(4) + m.group(5), "%Y%m%d%H%M%S"))
        end = os.path.getmtime(p)
        out.append({"worker": m.group(1), "kind": m.group(2), "number": int(m.group(3)),
                    "started": time.strftime("%Y-%m-%d %H:%M", time.localtime(start)),
                    "minutes": max(0, round((end - start) / 60)), "log_kb": round(os.path.getsize(p) / 1024),
                    "tokens": fmt_tokens(log_usage(p))})
    out.sort(key=lambda r: r["started"], reverse=True)
    return out[:200]


def snapshot():
    freeze = {name: os.path.exists(os.path.join(STATE, w.get("freezeFile", "FREEZE-" + name)))
              for name, w in workers().items()}
    full = workers_full()
    sched = {name: schedule_freeze(full.get(name)) for name in freeze}
    running = read_json(os.path.join(STATE, "running.json"))
    review = read_json(os.path.join(STATE, "review-running.json"))
    runs = parse_history()
    return {"now": time.strftime("%Y-%m-%d %H:%M:%S"), "freeze": freeze, "schedule": sched, "running": running, "review": review,
            "lastRouter": runs[-1] if runs else None, "routerRuns": runs[-30:][::-1], "workerRuns": worker_runs(),
            "stateFound": os.path.isdir(STATE)}


PAGE = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Factory</title>
<style>
body{font:14px system-ui;margin:0;padding:16px;background:#fafafa;color:#222;max-width:960px;margin:auto}
h1{font-size:20px}h2{font-size:15px;margin-top:24px}
.card{background:#fff;border:1px solid #ddd;border-radius:6px;padding:10px 14px;margin:8px 0}
table{border-collapse:collapse;width:100%;background:#fff}td,th{border:1px solid #ddd;padding:4px 8px;text-align:left;font-size:13px}
button{padding:4px 10px;margin-left:8px;cursor:pointer}.bad{color:#b00}.ok{color:#080}.mut{color:#777}
@media(prefers-color-scheme:dark){body{background:#1a1a1a;color:#ddd}.card,table{background:#242424;border-color:#444}td,th{border-color:#444}}
</style>
<h1>Factory</h1><div id=app>loading…</div>
<script>
const esc=s=>String(s??'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
async function freeze(w,on){await fetch('/api/freeze',{method:'POST',body:JSON.stringify({worker:w,frozen:on})});load()}
async function load(){
 const d=await (await fetch('/api/state')).json();
 let h=d.stateFound?'':'<div class="card bad">State folder not found. Set FACTORY_STATE.</div>';
 h+='<h2>Workers</h2>';
 for(const[w,f]of Object.entries(d.freeze)){
  const sc=d.schedule[w];
  const r=d.running&&d.running.worker===w?`working: ${esc(d.running.what)} since ${esc(d.running.started)} (${esc(d.running.model)})`:'idle';
  h+=`<div class=card><b>${esc(w)}</b> — <span class="${f||sc?'bad':'ok'}">${f?'FROZEN (manual)':sc?'frozen ('+esc(sc)+')':'active'}</span> — ${r}
   <button onclick="freeze('${esc(w)}',${!f})">${f?'Unfreeze':'Freeze'}</button></div>`}
 h+=`<div class=card>Claude review: ${d.review?'running — '+esc(d.review.what)+' since '+esc(d.review.started):'idle'}</div>`;
 const lr=d.lastRouter;
 h+=`<h2>Router</h2><div class=card>Last run ${esc(lr?lr.time:'never')}<br><span class=mut>dispatch: ${esc(lr&&lr.dispatch)}<br>review: ${esc(lr&&lr.review)}</span></div>`;
 h+='<h2>Worker history</h2><table><tr><th>Started<th>Worker<th>Work<th>Minutes<th>Tokens</tr>';
 for(const r of d.workerRuns)h+=`<tr><td>${esc(r.started)}<td>${esc(r.worker)}<td>${r.kind==='fix'?'fix PR':'issue'} #${r.number}<td>${r.minutes}<td class=mut>${r.tokens??'n/a'}</tr>`;
 h+='</table><h2>Recent router runs</h2><table><tr><th>Time<th>Dispatch<th>Review</tr>';
 for(const r of d.routerRuns)h+=`<tr><td>${esc(r.time)}<td>${esc(r.dispatch)}<td>${esc(r.review)}</tr>`;
 document.getElementById('app').innerHTML=h+'</table><p class=mut>Updated '+esc(d.now)+'. Refreshes every 15 s.</p>'}
load();setInterval(load,15000);
</script>"""


def authorized(handler):
    """Localhost needs no token; any other bind address requires FACTORY_TOKEN (cookie, header or ?token=)."""
    if not TOKEN:
        return True
    got = handler.headers.get("X-Factory-Token", "")
    for part in handler.headers.get("Cookie", "").split(";"):
        k, _, v = part.strip().partition("=")
        if k == "factory_token":
            got = v
    q = urlparse(handler.path).query
    got = parse_qs(q).get("token", [got])[0]
    return hmac.compare_digest(got, TOKEN)


class H(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype):
        b = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if not authorized(self):
            return self._send(401, "token required: open /?token=<FACTORY_TOKEN>", "text/plain")
        path = urlparse(self.path).path
        if path == "/api/state":
            self._send(200, json.dumps(snapshot()), "application/json")
        elif path in ("/", "/index.html"):
            body = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            if TOKEN:
                self.send_header("Set-Cookie", f"factory_token={TOKEN}; HttpOnly; SameSite=Strict; Path=/")
            self.end_headers()
            self.wfile.write(body)
        else:
            self._send(404, "not found", "text/plain")

    def do_POST(self):
        if not authorized(self):
            return self._send(401, "token required", "text/plain")
        # Same-origin only: a web page on another origin cannot freeze workers.
        if self.path != "/api/freeze" or self.headers.get("Origin", f"http://{self.headers.get('Host')}") != f"http://{self.headers.get('Host')}":
            return self._send(403, "forbidden", "text/plain")
        try:
            req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            w = workers()[req["worker"]]
        except Exception:
            return self._send(400, "bad request", "text/plain")
        path = os.path.join(STATE, w.get("freezeFile", "FREEZE-" + req["worker"]))
        if req.get("frozen"):
            os.makedirs(STATE, exist_ok=True)
            open(path, "a").close()
        elif os.path.exists(path):
            os.remove(path)
        self._send(200, "{}", "application/json")

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        print(json.dumps(snapshot(), indent=1)[:800])
        sys.exit(0)
    if HOST not in ("127.0.0.1", "localhost") and not TOKEN:
        sys.exit("FACTORY_TOKEN must be set when FACTORY_HOST is not 127.0.0.1")
    print(f"Factory UI on http://{HOST}:{PORT} (state: {STATE})")
    HTTPServer((HOST, PORT), H).serve_forever()
