"""Temporary: check which data sources answer from a GitHub runner and print samples."""
import json, re, sys, urllib.request, gzip

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

def get(url, headers=None):
    h = {"User-Agent": UA, "Accept-Encoding": "gzip"}
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
    print(f"\n===== {name} [{st}] len={len(body)} cors={hd.get('Access-Control-Allow-Origin')} {url}")
    print(body[:n])
    return body

for code in ["E0", "SC0", "D1", "I1", "SP1", "F1", "N1", "B1", "P1", "T1", "G1"]:
    b = show("fd " + code, f"https://www.football-data.co.uk/mmz4281/2627/{code}.csv", 600 if code == "E0" else 250)
b = show("fd E0 2526 tail", "https://www.football-data.co.uk/mmz4281/2526/E0.csv", 0)
print(b.splitlines()[0] if b else "")
print("rows", len(b.splitlines()))
for c in ["AUT", "SWZ", "DNK", "POL", "ROU", "NOR", "SWE", "FIN", "IRL", "RUS"]:
    show("fd new " + c, f"https://www.football-data.co.uk/new/{c}.csv", 300)
show("fd fixtures", "https://www.football-data.co.uk/fixtures.csv", 800)

show("clubelo", "http://api.clubelo.com/2026-10-20", 600)
show("clubelo fixtures", "http://api.clubelo.com/Fixtures", 600)

s = show("espn scoreboard", "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard?dates=20261017-20261020", 0)
try:
    j = json.loads(s)
    ev = j["events"][0]
    print("event keys", list(ev.keys()))
    comp = ev["competitions"][0]
    print("comp keys", list(comp.keys()))
    print("competitor0", json.dumps(comp["competitors"][0])[:2500])
    print("details", json.dumps(comp.get("details", []))[:2500])
    evid = ev["id"]
    teamid = comp["competitors"][0]["team"]["id"]
    sm = show("espn summary", f"https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/summary?event={evid}", 0)
    sj = json.loads(sm)
    print("summary keys", list(sj.keys()))
    for k in ["boxscore", "rosters", "keyEvents", "injuries", "headToHeadGames", "gameInfo", "odds"]:
        print("  ", k, json.dumps(sj.get(k))[:1500])
    r = show("espn roster", f"https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/teams/{teamid}/roster", 0)
    rj = json.loads(r)
    print("roster keys", list(rj.keys()))
    ath = rj.get("athletes", [])
    print("n athletes", len(ath))
    if ath:
        print(json.dumps(ath[0])[:3000])
        inj = [a for a in ath if a.get("injuries")]
        print("with injuries", len(inj), json.dumps([a.get("injuries") for a in inj[:3]])[:1500])
except Exception as e:
    print("espn parse error", e)
show("espn injuries core", "https://sports.core.api.espn.com/v2/sports/soccer/leagues/eng.1/injuries", 800)
show("espn teams", "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/teams", 400)
for lg in ["aut.1", "sui.1", "den.1", "pol.1", "cze.1", "gre.1", "tur.1", "sco.1", "rou.1", "nor.1", "swe.1"]:
    s = show("espn " + lg, f"https://site.api.espn.com/apis/site/v2/sports/soccer/{lg}/scoreboard?dates=20261001-20261020", 0)
    try:
        j = json.loads(s); e = j.get("events", [])
        st = e[0]["competitions"][0]["competitors"][0].get("statistics") if e else None
        print(lg, "events", len(e), "stats", [x.get("name") for x in (st or [])])
    except Exception as ex:
        print(lg, "err", ex)

u = show("understat league", "https://understat.com/league/EPL/2026", 300)
print("has playersData", "playersData" in u, "datesData" in u)
show("understat ajax", "https://understat.com/getLeagueData/EPL/2026", 600, {"X-Requested-With": "XMLHttpRequest", "Referer": "https://understat.com/league/EPL/2026"})
show("fpl", "https://fantasy.premierleague.com/api/bootstrap-static/", 300)
show("transfermarkt", "https://www.transfermarkt.com/premier-league/verletztespieler/wettbewerb/GB1", 300)
show("sofascore", "https://api.sofascore.com/api/v1/sport/football/scheduled-events/2026-10-24", 300)
show("fotmob", "https://www.fotmob.com/api/leagues?id=47", 300)
show("fbref", "https://fbref.com/en/comps/9/Premier-League-Stats", 300)
show("openfootball", "https://raw.githubusercontent.com/openfootball/football.json/master/2025-26/en.1.json", 300)
