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

# ESPN roster stats for a Greek team
t = json.loads(show("espn gre teams", "https://site.api.espn.com/apis/site/v2/sports/soccer/gre.1/teams", 0))
teams = t["sports"][0]["leagues"][0]["teams"]
print([(x["team"]["id"], x["team"]["displayName"]) for x in teams])
tid = teams[0]["team"]["id"]
r = json.loads(show("espn gre roster", f"https://site.api.espn.com/apis/site/v2/sports/soccer/gre.1/teams/{tid}/roster", 0))
a = r["athletes"]
print("athlete keys", list(a[0].keys()))
for x in a[:40]:
    st = x.get("statistics")
    if st:
        print(x["displayName"], x.get("position", {}).get("abbreviation"), json.dumps(st)[:1500])
        break
print("injuries any", [(x["displayName"], x.get("injuries"), x.get("status")) for x in a if x.get("injuries")][:5])
print("status sample", [x.get("status") for x in a[:3]])

# Sportsgambler structure and coverage
idx = show("sg index", "https://www.sportsgambler.com/injuries/football/", 0)
print(sorted(set(re.findall(r'/injuries/football/([a-z0-9\-]+)/', idx))))
b = show("sg epl", "https://www.sportsgambler.com/injuries/football/england-premier-league/", 0)
i = b.find("inj-titles")
print(re.sub(r"\s+", " ", b[i-2500:i+200]))
j = b.find("inj-row", i + 3000)
print("---- later chunk", re.sub(r"\s+", " ", b[j-1500:j+2500]))
print("team headers?", re.findall(r'<h3[^>]*>(.*?)</h3>', b)[:30])
print("injury types", sorted(set(re.findall(r'inj-type ([a-z\-]+)', b))))
