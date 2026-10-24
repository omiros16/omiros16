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

r = json.loads(show("espn gre roster", "https://site.api.espn.com/apis/site/v2/sports/soccer/gre.1/teams/435/roster", 0))
best = None
for x in r["athletes"]:
    cats = x.get("statistics", {}).get("splits", {}).get("categories", [])
    flat = {s["name"]: s.get("value") for c in cats for s in c.get("stats", [])}
    if flat.get("totalGoals", 0) and (best is None or flat["totalGoals"] > best[1].get("totalGoals", 0)):
        best = (x["displayName"], flat, [c["name"] for c in cats], x.get("position", {}).get("abbreviation"))
print("best scorer", best)
print("season", json.dumps(r.get("season"))[:300])

for lg in ["por.1", "bel.1", "aut.1", "sui.1", "den.1", "pol.1", "rou.1", "nor.1", "swe.1", "fin.1", "irl.1", "rus.1", "esp.1", "ger.1", "ita.1", "fra.1"]:
    s = show("espn " + lg, f"https://site.api.espn.com/apis/site/v2/sports/soccer/{lg}/scoreboard?dates=20261004", 0)
    try:
        j = json.loads(s); e = j.get("events", [])
        print(lg, j.get("leagues", [{}])[0].get("name"), "events", len(e))
    except Exception as ex:
        print(lg, "err", ex)
for q in ["dates=202610", "dates=20261001-20261007", "dates=20261001-20261007&limit=200", "dates=2026"]:
    s = show("espn range " + q, f"https://site.api.espn.com/apis/site/v2/sports/soccer/gre.1/scoreboard?{q}", 0)
    try:
        j = json.loads(s); e = j.get("events", []); print(q, "events", len(e), [x.get("date") for x in e[:3]], [x.get("date") for x in e[-2:]])
    except Exception as ex:
        print(q, "err", ex)
show("fd new fixtures", "https://www.football-data.co.uk/new_league_fixtures.csv", 600)
b = show("sg greece", "https://www.sportsgambler.com/injuries/football/greece-super-league/", 0)
print("greek teams", re.findall(r'<h3 class="injuries-title"[^>]*>(?:<a[^>]*>)?(.*?)(?:</a>)?</h3>', b))
for slug in ["poland-ekstraklasa", "romania-liga-1", "finland-veikkausliiga"]:
    b = show("sg " + slug, f"https://www.sportsgambler.com/injuries/football/{slug}/", 0)
    print(slug, b.count("inj-row"))
show("clubelo", "http://api.clubelo.com/2026-10-20", 400)
