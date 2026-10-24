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
    print(f"\n===== {name} [{st}] len={len(body)} cors={hd.get('Access-Control-Allow-Origin')} {url}")
    if n: print(body[:n])
    return body

XHR = {"X-Requested-With": "XMLHttpRequest", "Referer": "https://understat.com/"}
u = json.loads(show("understat EPL", "https://understat.com/getLeagueData/EPL/2026", 0, XHR))
print("keys", list(u.keys()))
for k, v in u.items():
    print(k, type(v).__name__, len(v))
    if isinstance(v, list): print(json.dumps(v[:2])[:1500])
    elif isinstance(v, dict): print(json.dumps(list(v.items())[:1])[:1200])
for lg in ["La_liga", "Bundesliga", "Serie_A", "Ligue_1", "RFPL"]:
    b = show("understat " + lg, f"https://understat.com/getLeagueData/{lg}/2026", 0, XHR)
    try:
        j = json.loads(b); print(lg, {k: len(v) for k, v in j.items()})
    except Exception as e: print(lg, "err", e)
show("understat 2025 EPL", "https://understat.com/getLeagueData/EPL/2025", 200, XHR)
show("understat match", "https://understat.com/getMatchData/28000", 300, XHR)
show("understat team", "https://understat.com/getTeamData/Arsenal/2026", 600, XHR)
show("understat player", "https://understat.com/getPlayerData/1250", 600, XHR)

f = json.loads(show("fpl", "https://fantasy.premierleague.com/api/bootstrap-static/", 0))
print("fpl keys", list(f.keys()))
el = f["elements"]
print("n elements", len(el), "keys", list(el[0].keys()))
inj = [e for e in el if e.get("status") != "a"]
print("non-available", len(inj))
for e in inj[:8]:
    print({k: e.get(k) for k in ["web_name", "team", "status", "news", "chance_of_playing_next_round", "minutes", "goals_scored", "element_type"]})
print("teams", [(t["id"], t["name"]) for t in f["teams"]])
show("fpl fixtures", "https://fantasy.premierleague.com/api/fixtures/?future=1", 500)

show("espn scoreboard plain", "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard", 300)
s = show("espn scoreboard day", "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard?dates=20261018", 0)
try:
    j = json.loads(s)
    ev = j["events"][0]; comp = ev["competitions"][0]
    print("competitor0", json.dumps(comp["competitors"][0])[:2000])
    print("details", json.dumps(comp.get("details", []))[:1500])
    evid = ev["id"]; teamid = comp["competitors"][0]["team"]["id"]
    sj = json.loads(show("espn summary", f"https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/summary?event={evid}", 0))
    print("summary keys", list(sj.keys()))
    for k in ["boxscore", "rosters", "keyEvents", "injuries", "headToHeadGames", "gameInfo"]:
        print("  ", k, json.dumps(sj.get(k))[:1200])
    rj = json.loads(show("espn roster", f"https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/teams/{teamid}/roster", 0))
    ath = rj.get("athletes", [])
    print("roster keys", list(rj.keys()), "n", len(ath))
    if ath:
        print(json.dumps(ath[0])[:2500])
        print("with injuries", sum(1 for a in ath if a.get("injuries")), json.dumps([a.get("injuries") for a in ath if a.get("injuries")][:3])[:1200])
    show("espn core team injuries", f"https://sports.core.api.espn.com/v2/sports/soccer/leagues/eng.1/teams/{teamid}/injuries", 600)
except Exception as e:
    print("espn err", e)
for lg in ["gre.1", "ned.1", "por.1", "tur.1", "bel.1", "sco.1"]:
    s = show("espn " + lg, f"https://site.api.espn.com/apis/site/v2/sports/soccer/{lg}/scoreboard?dates=20261018", 0)
    try:
        j = json.loads(s); e = j.get("events", [])
        c = e[0]["competitions"][0]["competitors"][0] if e else {}
        print(lg, "events", len(e), "stats", [x.get("name") for x in c.get("statistics", [])], "details", len(e[0]["competitions"][0].get("details", [])) if e else 0)
    except Exception as ex:
        print(lg, "err", ex)

show("rotowire", "https://www.rotowire.com/soccer/tables/injury-report.php?league=EPL", 800)
show("rotowire LIGA", "https://www.rotowire.com/soccer/tables/injury-report.php?league=LIGA", 300)
show("sportsgambler", "https://www.sportsgambler.com/injuries/football/england-premier-league/", 300)
b = show("sportsgambler greece", "https://www.sportsgambler.com/injuries/football/greece-super-league/", 0)
print("sg greece has inj-row", b.count("inj-row"), b.count("injury"), b[b.find("inj-"):b.find("inj-")+1500] if "inj-" in b else b[:600])
show("transfermarkt2", "https://www.transfermarkt.com/super-league-1/verletztespieler/wettbewerb/GR1", 300, {"Accept": "text/html,application/xhtml+xml", "Accept-Language": "en-US,en;q=0.9"})
show("tm api", "https://tmapi-alpha.transfermarkt.technology/competition/GB1", 300)
show("fotmob data", "https://www.fotmob.com/api/data/leagues?id=47", 300)
show("clubelo", "http://api.clubelo.com/2026-10-20", 400)
show("clubelo2", "http://clubelo.com/API", 300)
show("physioroom", "https://www.physioroom.com/news/english_premier_league/epl_injury_table.php", 300)
