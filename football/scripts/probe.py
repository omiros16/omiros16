"""Temporary: check which data sources answer from a GitHub runner and print samples."""
import json, re, sys, urllib.request, gzip

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

def get(url, headers=None):
    h = {"User-Agent": UA, "Accept-Encoding": "gzip", "Accept-Language": "en-US,en;q=0.9", "Accept": "*/*"}
    h.update(headers or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=30) as r:
            b = r.read()
            if r.headers.get("Content-Encoding") == "gzip":
                b = gzip.decompress(b)
            return r.status, b.decode("utf-8", "replace"), dict(r.headers)
    except Exception as e:
        return getattr(e, "code", 0), str(e), {}

def show(name, url, n=1500, headers=None):
    st, body, hd = get(url, headers)
    print(f"\n===== {name} [{st}] len={len(body)} {url}")
    if n: print(body[:n])
    return body

def walk(o, path="", depth=0, out=None):
    """Print key paths that mention injur/unavail/suspend."""
    if out is None: out = []
    if depth > 9: return out
    if isinstance(o, dict):
        for k, v in o.items():
            p = path + "." + k
            if re.search(r"injur|unavail|suspen|absent|missing", k, re.I): out.append((p, json.dumps(v)[:600]))
            walk(v, p, depth + 1, out)
    elif isinstance(o, list):
        for i, v in enumerate(o[:3]): walk(v, path + f"[{i}]", depth + 1, out)
    return out

L = json.loads(show("fotmob gre league", "https://www.fotmob.com/api/data/leagues?id=135", 0))
print("league keys", list(L.keys()))
try:
    tbl = L["table"][0]["data"]["table"]["all"]
    print("table", [(t["id"], t["name"]) for t in tbl])
    tid = tbl[0]["id"]
except Exception as e:
    print("table err", e); tid = 8638
fx = L.get("fixtures", {}).get("allMatches", [])
print("n fixtures", len(fx), json.dumps(fx[:1])[:600])
upcoming = [m for m in fx if not m.get("status", {}).get("finished")]
T = json.loads(show("fotmob team", f"https://www.fotmob.com/api/data/teams?id={tid}", 0))
print("team keys", list(T.keys()))
for p, v in walk(T)[:12]: print("  ", p, v)
sq = T.get("squad", {})
print("squad", json.dumps(sq)[:2500])
if upcoming:
    mid = upcoming[0]["id"]
    D = json.loads(show("fotmob match", f"https://www.fotmob.com/api/data/matchDetails?matchId={mid}", 300) or "{}")
    for p, v in walk(D)[:12]: print("  ", p, v)
show("fotmob playerstats", "https://www.fotmob.com/api/data/leagueseasondeepstats?id=135&season=2026/2027&type=players&stat=goals", 400)
for path in ["/competition/GR1/clubs", "/competition/GR1/injuries", "/club/683/injuries", "/club/683/squad"]:
    show("tmapi " + path, "https://tmapi-alpha.transfermarkt.technology" + path, 500)
