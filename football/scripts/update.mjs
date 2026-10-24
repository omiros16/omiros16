// Weekly data build: downloads results, xG, players, absences and fixtures for every league,
// tunes the model with a walk-forward backtest and writes one small JSON file per league.
// Each run replaces the previous files and keeps a fixed window of seasons, so the data
// never grows over time.   Usage: node football/scripts/update.mjs [outDir]
import { mkdir, writeFile } from 'node:fs/promises';
import { LEAGUES } from './leagues.mjs';
import { getText, getJson, pool } from './lib/net.mjs';
import { parseCsv, dayOf, isoDay, today } from './lib/csv.mjs';
import { matchNames, playerSimilarity } from './lib/names.mjs';
import { F, fitLeague } from '../js/model.js';
import { prepare, runLeague, score, statsCheck, promotedAt } from './backtest.mjs';
import { analyze } from '../js/analyze.js';

const OUT = process.argv[2] || 'football/data';
const REF = today();
const SEASONS_BACK = 6;          // seasons downloaded (H2H history + backtest)
const MODEL_DAYS = 500;          // full match details shipped for this many days
const H2H_DAYS = 6 * 365;        // older results kept only for head-to-head
const FD = 'https://www.football-data.co.uk';
const XHR_US = { 'X-Requested-With': 'XMLHttpRequest', Referer: 'https://understat.com/' };
const log = (...a) => console.log(...a);
const r3 = (x) => (x === null || x === undefined || !Number.isFinite(x) ? x : Math.round(x * 1000) / 1000);
const n = (v) => (v === '' || v === undefined || v === null || Number.isNaN(+v) ? null : +v);

function seasonCodes() {
  const d = new Date(REF * 86400000);
  const y = d.getUTCMonth() >= 6 ? d.getUTCFullYear() : d.getUTCFullYear() - 1;
  const codes = [];
  for (let i = 0; i < SEASONS_BACK; i++) { const a = (y - i) % 100; codes.push(String(a).padStart(2, '0') + String((a + 1) % 100).padStart(2, '0')); }
  return { year: y, codes };
}

function oddsOf(r, keys) {
  for (const [h, d, a] of keys) {
    const o = [n(r[h]), n(r[d]), n(r[a])];
    if (o.every((x) => x > 1)) return o;
  }
  return null;
}

// ---------------------------------------------------------------- results

async function loadResults(lg) {
  const rows = [];
  if (lg.fd) {
    const { codes } = seasonCodes();
    for (const code of codes) {
      const t = await getText(`${FD}/mmz4281/${code}/${lg.fd}.csv`);
      if (!t) continue;
      for (const r of parseCsv(t)) {
        const d = dayOf(r.Date);
        if (d === null || !r.HomeTeam || !r.AwayTeam || n(r.FTHG) === null) continue;
        rows.push({
          d, season: code, home: r.HomeTeam, away: r.AwayTeam, ref: r.Referee || null,
          v: [n(r.FTHG), n(r.FTAG), n(r.HTHG), n(r.HTAG), n(r.HS), n(r.AS), n(r.HST), n(r.AST), n(r.HF), n(r.AF), n(r.HC), n(r.AC), n(r.HY), n(r.AY), n(r.HR), n(r.AR), n(r.HxG), n(r.AxG)],
          odds: {
            x12: oddsOf(r, [['AvgCH', 'AvgCD', 'AvgCA'], ['B365CH', 'B365CD', 'B365CA'], ['AvgH', 'AvgD', 'AvgA']]),
            ou: [n(r['AvgC>2.5']) ?? n(r['Avg>2.5']), n(r['AvgC<2.5']) ?? n(r['Avg<2.5'])].every((x) => x > 1) ? [n(r['AvgC>2.5']) ?? n(r['Avg>2.5']), n(r['AvgC<2.5']) ?? n(r['Avg<2.5'])] : null,
          },
        });
      }
    }
  } else if (lg.fdNew) {
    const t = await getText(`${FD}/new/${lg.fdNew}.csv`);
    if (t) {
      for (const r of parseCsv(t)) {
        const d = dayOf(r.Date);
        if (d === null || d < REF - H2H_DAYS || n(r.HG) === null) continue;
        rows.push({
          d, season: r.Season, home: r.Home, away: r.Away, ref: null,
          v: [n(r.HG), n(r.AG), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null],
          odds: { x12: oddsOf(r, [['AvgCH', 'AvgCD', 'AvgCA'], ['PSCH', 'PSCD', 'PSCA'], ['B365CH', 'B365CD', 'B365CA']]), ou: null },
        });
      }
    }
  }
  rows.sort((a, b) => a.d - b.d);
  return rows;
}

// ---------------------------------------------------------------- Understat (xG + players)

async function understat(lg, fdTeams, rows, curTeams, rosterOf) {
  if (!lg.us) return null;
  const { year } = seasonCodes();
  const cur = await getJson(`https://understat.com/getLeagueData/${lg.us}/${year}`, XHR_US);
  const prev = await getJson(`https://understat.com/getLeagueData/${lg.us}/${year - 1}`, XHR_US);
  if (!cur) return null;
  const titles = new Set();
  for (const j of [cur, prev]) for (const m of j?.dates || []) { titles.add(m.h.title); titles.add(m.a.title); }
  const map = matchNames([...titles], fdTeams);
  const unmatched = [...titles].filter((t) => !map.has(t));
  if (unmatched.length) log(`  understat unmatched teams: ${unmatched.join(', ')}`);
  // fill xG where football-data has none
  const byKey = new Map();
  for (const j of [cur, prev]) for (const m of j?.dates || []) {
    if (!m.isResult) continue;
    const h = map.get(m.h.title), a = map.get(m.a.title);
    if (h === undefined || a === undefined) continue;
    const d = Math.floor(Date.parse(m.datetime.replace(' ', 'T') + 'Z') / 86400000);
    byKey.set(`${fdTeams[h]}|${fdTeams[a]}`, [...(byKey.get(`${fdTeams[h]}|${fdTeams[a]}`) || []), [d, +m.xG.h, +m.xG.a]]);
  }
  let filled = 0;
  for (const r of rows) {
    if (r.v[16] !== null) continue;
    const c = (byKey.get(`${r.home}|${r.away}`) || []).find(([d]) => Math.abs(d - r.d) <= 1);
    if (c) { r.v[16] = c[1]; r.v[17] = c[2]; filled++; }
  }
  // A player who appeared for more than one club gets a comma-separated, alphabetical
  // team_title, so it does not say which club he is at now. Understat also has matches whose
  // rosters were credited to both sides (dozens of players listed under the same two clubs).
  // Settle those against the current ESPN rosters and leave out what cannot be settled.
  const titleCount = new Map();
  for (const p of cur.players || []) titleCount.set(p.team_title, (titleCount.get(p.team_title) || 0) + 1);
  const players = [];
  let settled = 0, dropped = 0;
  for (const p of cur.players || []) {
    const cands = [...new Set(String(p.team_title).split(',').map((t) => map.get(t.trim())).filter((t) => t !== undefined).map((t) => fdTeams[t]))]
      .filter((t) => curTeams.includes(t));
    let team = cands.length === 1 ? cands[0] : null;
    if (cands.length > 1) {
      let best = null, bs = 0;
      for (const r of (await rosterOf()) || []) {
        if (!cands.includes(r.team)) continue;
        const sc = playerSimilarity(p.player_name, r.name);
        if (sc > bs) { bs = sc; best = r.team; }
      }
      if (bs >= 0.85) { team = best; settled++; } else dropped++;
    }
    if (!team) continue;
    const pos = String(p.position || 'M').split(' ')[0];
    players.push({ name: p.player_name, team, pos: pos === 'GK' ? 'G' : pos[0], gp: +p.games, min: +p.time, g: +p.goals, xg: +p.xG, a: +p.assists, xa: +p.xA, sh: +p.shots, y: +p.yellow_cards, r: +p.red_cards });
  }
  const clusters = [...titleCount].filter(([t, c]) => t.includes(',') && c >= 4).map(([t, c]) => `${t} (${c})`);
  log(`  understat: ${cur.players?.length || 0} players, xG filled for ${filled} matches, multi-club players settled ${settled}, left out ${dropped}${clusters.length ? `, suspicious clusters: ${clusters.join('; ')}` : ''}`);
  return players;
}

// ---------------------------------------------------------------- ESPN rosters (players elsewhere)

// Every athlete's current club, plus season stats for those who have played.
async function espnRosters(lg, fdTeams) {
  if (!lg.espn) return null;
  const t = await getJson(`https://site.api.espn.com/apis/site/v2/sports/soccer/${lg.espn}/teams`);
  const teams = t?.sports?.[0]?.leagues?.[0]?.teams?.map((x) => x.team) || [];
  if (!teams.length) return null;
  const map = matchNames(teams.map((x) => x.displayName), fdTeams);
  const unmatched = teams.filter((x) => !map.has(x.displayName)).map((x) => x.displayName);
  if (unmatched.length) log(`  espn unmatched teams: ${unmatched.join(', ')}`);
  const players = [], roster = [];
  await pool(teams, 4, async (tm) => {
    const ti = map.get(tm.displayName);
    if (ti === undefined) return;
    const r = await getJson(`https://site.api.espn.com/apis/site/v2/sports/soccer/${lg.espn}/teams/${tm.id}/roster`);
    for (const a of r?.athletes || []) {
      roster.push({ name: a.displayName, team: fdTeams[ti] });
      const st = {};
      for (const c of a.statistics?.splits?.categories || []) for (const s of c.stats || []) st[s.name] = s.value;
      const apps = st.appearances || 0;
      if (!apps) continue;
      const subs = Math.min(apps, st.subIns || 0);
      const pos = a.position?.abbreviation || 'M';
      players.push({ name: a.displayName, team: fdTeams[ti], pos: pos[0] === 'G' ? 'G' : pos[0], gp: apps, min: Math.round((apps - subs) * 82 + subs * 22), g: st.totalGoals || 0, xg: null, a: st.goalAssists || 0, xa: null, sh: st.totalShots ?? null, y: st.yellowCards || 0, r: st.redCards || 0 });
    }
  });
  log(`  espn: ${roster.length} squad players, ${players.length} with appearances`);
  return { players, roster };
}

// ---------------------------------------------------------------- FotMob (fixtures + absences)

const FOTMOB_IDS = { E0: 47, SP1: 87, I1: 55, D1: 54, F1: 53, G1: 135, N1: 57, P1: 61, B1: 40, T1: 71, SC0: 64, AUT: 38, SWZ: 69, DNK: 46, POL: 196, ROU: 189, NOR: 59, SWE: 67, FIN: 51, IRL: 126, RUS: 63 };

async function fotmob(lg, fdTeams) {
  const id = FOTMOB_IDS[lg.id];
  if (!id) return null;
  const L = await getJson(`https://www.fotmob.com/api/data/leagues?id=${id}`);
  if (!L) return null;
  const all = L.fixtures?.allMatches || [];
  const names = new Set();
  for (const m of all) { names.add(m.home?.name); names.add(m.away?.name); }
  names.delete(undefined);
  const map = matchNames([...names], fdTeams);
  const unmatched = [...names].filter((x) => !map.has(x));
  log(`  fotmob ${L.details?.name || '?'} (${L.details?.country || '?'}): ${all.length} matches${unmatched.length ? `, unmatched: ${unmatched.join(', ')}` : ''}`);
  if (map.size < Math.min(8, names.size * 0.6)) { log('  fotmob league looks wrong, skipped'); return null; }
  const now = Date.now();
  const when = (m) => Date.parse(m.status?.utcTime);
  const teamsOf = (m) => [map.get(m.home?.name), map.get(m.away?.name)];

  // finished games, to fill in what football-data has not published yet
  const results = [];
  for (const m of all) {
    const st = m.status || {};
    const sc = /^(\d+)\s*-\s*(\d+)$/.exec(st.scoreStr || '');
    const [h, a] = teamsOf(m);
    if (!st.finished || st.cancelled || st.awarded || !sc || h === undefined || a === undefined) continue;
    results.push({ d: Math.floor(when(m) / 86400000), home: fdTeams[h], away: fdTeams[a], hg: +sc[1], ag: +sc[2] });
  }
  const starts = all.map(when).filter(Number.isFinite);
  const seasonStart = starts.length ? Math.floor(Math.min(...starts) / 86400000) : null;

  const upcoming = all.filter((m) => !m.status?.finished && !m.status?.cancelled && when(m) > now - 3 * 3600e3 && when(m) < now + 10 * 86400e3);
  const fixtures = [], absences = new Map(), absAt = new Map(), types = new Set();
  await pool(upcoming, 4, async (m) => {
    const [h, a] = teamsOf(m);
    if (h === undefined || a === undefined) return;
    fixtures.push({ t: m.status.utcTime, home: fdTeams[h], away: fdTeams[a] });
    const D = await getJson(`https://www.fotmob.com/api/data/matchDetails?matchId=${m.id}`);
    const lu = D?.content?.lineup;
    for (const [side, team] of [['homeTeam', fdTeams[h]], ['awayTeam', fdTeams[a]]]) {
      const list = lu?.[side]?.unavailable;
      // a team with two games in the window keeps the list for the nearer one
      if (!Array.isArray(list) || (absAt.has(team) && absAt.get(team) <= when(m))) continue;
      absAt.set(team, when(m));
      absences.set(team, list.map((u) => {
        const ty = u.unavailability?.type || 'injury';
        types.add(ty);
        return { name: u.name, type: ty === 'suspension' ? 's' : /doubt/i.test(ty) ? 'd' : 'i', info: ty, ret: u.unavailability?.expectedReturn || '' };
      }));
    }
  });
  log(`  fotmob: ${results.length} results, ${fixtures.length} upcoming, absences for ${absences.size} teams, types: ${[...types].join(',')}`);
  return { fixtures, absences, results, seasonStart };
}

// Adds FotMob results that football-data does not have yet (it can lag days, or stop
// updating a league), as long as both describe the same season.
function mergeResults(rows, fm, curSeason) {
  if (!fm?.results?.length || fm.seasonStart === null) return 0;
  const cur = rows.filter((r) => r.season === curSeason);
  if (!cur.length || Math.abs(fm.seasonStart - Math.min(...cur.map((r) => r.d))) > 25) return 0;
  const have = new Set(rows.map((r) => `${r.home}|${r.away}|${r.d}`));
  let added = 0;
  for (const m of fm.results) {
    if ([-1, 0, 1].some((k) => have.has(`${m.home}|${m.away}|${m.d + k}`))) continue;
    rows.push({ d: m.d, season: curSeason, home: m.home, away: m.away, ref: null, v: [m.hg, m.ag, ...new Array(16).fill(null)], odds: { x12: null, ou: null }, fotmob: true });
    added++;
  }
  rows.sort((a, b) => a.d - b.d);
  return added;
}

// ---------------------------------------------------------------- Fantasy Premier League

async function fpl(fdTeams) {
  const j = await getJson('https://fantasy.premierleague.com/api/bootstrap-static/');
  if (!j) return null;
  const map = matchNames(j.teams.map((t) => t.name), fdTeams);
  const teamOf = new Map(j.teams.map((t) => [t.id, map.has(t.name) ? fdTeams[map.get(t.name)] : null]));
  const out = new Map(), left = new Map();
  for (const e of j.elements) {
    const team = teamOf.get(e.team);
    if (!team) continue;
    const name = `${e.first_name} ${e.second_name}`;
    if (e.status === 'u' || e.status === 'n') { left.set(team, [...(left.get(team) || []), name]); continue; }
    const ch = e.chance_of_playing_next_round;
    if (e.status === 'a' && (ch === null || ch === 100)) continue;
    const type = e.status === 's' ? 's' : e.status === 'd' ? 'd' : 'i';
    out.set(team, [...(out.get(team) || []), { name, type, info: e.news || '', ret: '', chance: ch ?? (type === 'd' ? 50 : 0) }]);
  }
  log(`  fpl: absences for ${out.size} teams`);
  return { absences: out, left };
}

// ---------------------------------------------------------------- sportsgambler (fallback)

async function sportsgambler(lg, fdTeams) {
  if (!lg.sg) return null;
  const html = await getText(`https://www.sportsgambler.com/injuries/football/${lg.sg}/`);
  if (!html) return null;
  const blocks = html.split('<h3 class="injuries-title"').slice(1);
  const raw = [];
  for (const b of blocks) {
    const tn = /^[^>]*>(?:<a[^>]*>)?([^<]+)/.exec(b)?.[1]?.trim();
    if (!tn) continue;
    const list = [];
    for (const row of b.split('<div class="inj-row">').slice(1)) {
      const ty = /inj-type ([a-z-]+)/.exec(row)?.[1] || '';
      const name = /class="inj-player">([^<]+)/.exec(row)?.[1]?.trim();
      if (!name) continue;
      list.push({ name, type: ty === 'redcard' ? 's' : ty === 'injury-questionmark' ? 'd' : 'i', info: /class="inj-info">([^<]*)/.exec(row)?.[1]?.trim() || '', ret: /class="inj-return h-sm">([^<]*)/.exec(row)?.[1]?.trim() || '' });
    }
    raw.push([tn.replace(/&amp;/g, '&'), list]);
  }
  const map = matchNames(raw.map((x) => x[0]), fdTeams);
  if (map.size < raw.length * 0.7) { log(`  sportsgambler page does not match league (${map.size}/${raw.length})`); return null; }
  const out = new Map();
  for (const [tn, list] of raw) if (map.has(tn)) out.set(fdTeams[map.get(tn)], list);
  log(`  sportsgambler: absences for ${out.size} teams`);
  return { absences: out };
}

// ---------------------------------------------------------------- football-data fixtures (fallback)

let fdFixtures = null;
async function fdFixtureRows() {
  if (fdFixtures) return fdFixtures;
  const a = parseCsv((await getText(`${FD}/fixtures.csv`)) || '');
  const b = parseCsv((await getText(`${FD}/new_league_fixtures.csv`)) || '');
  fdFixtures = [
    ...a.map((r) => ({ div: r.Div, d: dayOf(r.Date), time: r.Time, home: r.HomeTeam, away: r.AwayTeam, ref: r.Referee || null })),
    ...b.map((r) => ({ country: r.Country, d: dayOf(r.Date), time: r.Time, home: r.Home, away: r.Away, ref: null })),
  ];
  return fdFixtures;
}

// football-data lists kick-offs in UK time.
function ukToIso(day, time) {
  const [hh, mm] = time.split(':').map(Number);
  const guess = Date.UTC(1970, 0, 1 + day, hh, mm);
  const london = new Date(guess).toLocaleString('en-GB', { timeZone: 'Europe/London', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  const [lh, lm] = london.split(':').map(Number);
  const offset = ((lh * 60 + lm) - (hh * 60 + mm) + 1440) % 1440; // 0 or 60 minutes
  return new Date(guess - offset * 60000).toISOString();
}

// ---------------------------------------------------------------- build one league

async function buildLeague(lg) {
  log(`\n== ${lg.id} ${lg.country} ${lg.name}`);
  const rows = await loadResults(lg);
  if (rows.length < 100) { log('  not enough results, skipped'); return null; }
  const curSeason = rows[rows.length - 1].season;
  const curTeams = [...new Set(rows.filter((r) => r.season === curSeason).flatMap((r) => [r.home, r.away]))].sort();
  const teams = [...new Set([...curTeams, ...rows.flatMap((r) => [r.home, r.away])])];
  const ti = new Map(teams.map((t, i) => [t, i]));
  const refsList = [...new Set(rows.map((r) => r.ref).filter(Boolean))];
  const ri = new Map(refsList.map((r, i) => [r, i]));

  const fm = await fotmob(lg, curTeams).catch((e) => (log('  fotmob failed', e.message), null));
  const added = mergeResults(rows, fm, curSeason);
  if (added) log(`  added ${added} results from FotMob (latest football-data game: ${isoDay(Math.max(...rows.filter((r) => !r.fotmob).map((r) => r.d)))})`);

  // ESPN rosters are fetched once, when needed (players outside Understat, or to settle
  // Understat players listed under two clubs).
  let espnP = null;
  const espn = () => (espnP ??= espnRosters(lg, curTeams).catch((e) => (log('  espn failed', e.message), null)));
  // Understat also covers last season, so relegated sides must be matchable too.
  const recentTeams = [...new Set(rows.filter((r) => r.d >= REF - MODEL_DAYS).flatMap((r) => [r.home, r.away]))];
  const usPlayers = await understat(lg, recentTeams, rows, curTeams, async () => (await espn())?.roster).catch((e) => (log('  understat failed', e.message), null));
  const tuple = (r) => [r.d, ti.get(r.home), ti.get(r.away), ...r.v.slice(0, 16), r.v[16], r.v[17], r.ref ? ri.get(r.ref) : null];
  const all = rows.map(tuple);

  // backtest data (all seasons downloaded)
  const bt = prepare({ id: lg.id, matches: all, nTeams: teams.length, season: rows.map((r) => r.season), odds: rows.map((r) => r.odds) });

  const players = usPlayers || (await espn())?.players || [];
  const fp = lg.fpl ? await fpl(curTeams).catch((e) => (log('  fpl failed', e.message), null)) : null;
  let absSrc = fp ? 'fpl' : fm && fm.absences.size ? 'fotmob' : null;
  let absences = fp?.absences || (fm && fm.absences.size ? fm.absences : null);
  if (!absences) {
    const sg = await sportsgambler(lg, curTeams).catch((e) => (log('  sportsgambler failed', e.message), null));
    if (sg) { absences = sg.absences; absSrc = 'sportsgambler'; }
  }

  // fixtures
  let fixtures = [];
  if (fm && fm.fixtures.length) {
    fixtures = fm.fixtures.map((f) => ({ d: Math.floor(Date.parse(f.t) / 86400000), t: f.t, home: f.home, away: f.away, ref: null }));
  } else {
    const fx = await fdFixtureRows();
    fixtures = fx.filter((f) => (lg.fd ? f.div === lg.fd : f.country === lg.fdCountry) && f.d >= REF - 1 && f.d <= REF + 10)
      .filter((f) => ti.has(f.home) && ti.has(f.away))
      .map((f) => ({ d: f.d, t: f.time ? ukToIso(f.d, f.time) : null, home: f.home, away: f.away, ref: f.ref }));
  }
  if (lg.fd) {
    // referees appointed for upcoming games (published by football-data for some leagues)
    const fx = await fdFixtureRows();
    for (const f of fixtures) {
      const m = fx.find((x) => x.div === lg.fd && x.home === f.home && x.away === f.away && Math.abs(x.d - f.d) <= 1);
      if (m?.ref) f.ref = m.ref;
    }
  }

  // players: drop those who left (FPL) and attach absences
  const left = fp?.left || new Map();
  const plist = players.filter((p) => !(left.get(p.team) || []).some((nm) => playerSimilarity(nm, p.name) >= 0.9));
  const inj = [];
  let matched = 0, total = 0;
  for (const [team, list] of absences || []) {
    if (!ti.has(team)) continue;
    for (const a of list) {
      total++;
      let best = -1, bs = 0;
      plist.forEach((p, i) => { if (p.team === team) { const s = playerSimilarity(a.name, p.name); if (s > bs) { bs = s; best = i; } } });
      if (bs < 0.75) best = -1; else matched++;
      inj.push([ti.get(team), best >= 0 ? plist[best].name : a.name, a.type, a.info, a.ret, a.chance ?? (a.type === 'd' ? 50 : 0), best]);
    }
  }
  if (total) log(`  absences: ${total} (${matched} linked to player stats) via ${absSrc}`);
  if (plist.length) {
    const thin = curTeams.filter((t) => plist.filter((p) => p.team === t).length < 12);
    if (thin.length) log(`  WARNING few players for: ${thin.map((t) => `${t} (${plist.filter((p) => p.team === t).length})`).join(', ')}`);
  }

  return {
    lg, rows, teams, curTeams, ti, refsList, all, bt, curSeason,
    players: plist.map((p) => [p.name, ti.get(p.team), p.pos, p.gp, p.min, p.g, r3(p.xg), p.a, r3(p.xa), p.sh, p.y, p.r]),
    playersSrc: usPlayers ? 'understat' : players.length ? 'espn' : null,
    inj, absSrc,
    fixtures: fixtures.filter((f) => ti.has(f.home) && ti.has(f.away)),
  };
}

// ---------------------------------------------------------------- main

async function main() {
  const built = [];
  for (const lg of LEAGUES) {
    try { const b = await buildLeague(lg); if (b) built.push(b); } catch (e) { log(`  ${lg.id} failed: ${e.stack}`); }
  }
  if (built.length < LEAGUES.length * 0.6) throw new Error(`only ${built.length} leagues built`);
  const missing = LEAGUES.filter((lg) => !built.some((b) => b.lg.id === lg.id));

  // Choose model settings by pooled walk-forward log-loss.
  const grid = [];
  for (const halfLife of [150, 270, 400, 600]) for (const wxg of [0.2, 0.35, 0.55]) grid.push({ halfLife, wxg, k: 4 });
  const h2hW = [0, 1, 2];
  const t0 = Date.now();
  const perLeague = built.map((b) => runLeague(b.bt, grid, REF, h2hW));
  const pooled = grid.map((g, gi) => h2hW.map((hw, hi) => {
    let s = 0, c = 0;
    perLeague.forEach((rows, li) => {
      if (!built[li].lg.fd && g.wxg !== grid[0].wxg) return; // xG settings only matter where shots exist
      for (const x of rows[gi][hi]) { s -= Math.log(Math.max(1e-9, x.p[x.r])); c++; }
    });
    return c ? s / c : Infinity;
  }));
  let best = [0, 0];
  pooled.forEach((row, gi) => row.forEach((v, hi) => { if (v < pooled[best[0]][best[1]]) best = [gi, hi]; }));
  const params = { ...grid[best[0]], h2h: h2hW[best[1]] };
  log(`\nbacktest ${((Date.now() - t0) / 1000).toFixed(1)}s, chosen ${JSON.stringify(params)}`);
  grid.forEach((g, gi) => log(`  hl=${g.halfLife} wxg=${g.wxg}: ${pooled[gi].map((v) => v.toFixed(4)).join(' / ')}`));

  // Shrinkage for corners/cards/shots/fouls: pick the strength that predicted best overall.
  const KS = [6, 15, 40, 100];
  const statRes = built.map((b) => (b.lg.fd ? statsCheck(b.bt, params.halfLife, REF, KS) : {}));
  params.statK = {};
  for (const name of ['corners', 'yellows', 'sot', 'fouls']) {
    const tot = Object.fromEntries(KS.map((k) => [k, 0]));
    let cnt = 0;
    for (const r of statRes) if (r[name]) { for (const k of KS) tot[k] += r[name].byK[k] * r[name].n; cnt += r[name].n; }
    if (!cnt) continue;
    params.statK[name] = +KS.reduce((a, k) => (tot[k] < tot[a] ? k : a), KS[0]);
    log(`  ${name}: ${KS.map((k) => `k=${k} ${(tot[k] / cnt).toFixed(4)}`).join(', ')} -> ${params.statK[name]}`);
  }
  params.statK.shots = params.statK.sot;

  await mkdir(`${OUT}/leagues`, { recursive: true });
  const index = { updated: new Date().toISOString(), ref: REF, params, leagues: [], fixtures: [] };
  const fmt = (x) => (x === null ? null : +x.toFixed(4));
  for (const [li, b] of built.entries()) {
    const { lg } = b;
    const sc = score(b.bt, perLeague[li][best[0]][best[1]]);
    const base = score(b.bt, perLeague[li][best[0]][0]);
    const stats = Object.fromEntries(Object.entries(statRes[li]).map(([k, v]) => [k, { line: v.line, n: v.n, ll: fmt(v.byK[params.statK[k]]), llBase: fmt(v.base) }]));
    const promoted = promotedAt(b.bt, REF).map((t) => b.teams[t]);
    const window = b.all.filter((m) => m[F.D] >= REF - MODEL_DAYS);
    const fit = fitLeague(window, b.teams.length, REF + 1, params, promotedAt(b.bt, REF));
    const old = b.all.filter((m) => m[F.D] < REF - MODEL_DAYS).map((m) => m.slice(0, 7));
    const btOut = {
      n: sc.n, ll: fmt(sc.ll), llMkt: fmt(sc.llMkt), llSame: fmt(sc.llSame), nMkt: sc.nMkt, llNoH2H: fmt(base.ll), brier: fmt(sc.brier), acc: fmt(sc.acc), llOU: fmt(sc.llOU), llOUMkt: fmt(sc.llOUMkt),
      bets: sc.bets, roi: fmt(sc.roi), betsOU: sc.betsOU, roiOU: fmt(sc.roiOU), cal: sc.cal, stats,
    };
    log(`${lg.id}: n=${sc.n} ll=${sc.ll.toFixed(4)} mkt=${sc.llMkt?.toFixed(4)} acc=${(sc.acc * 100).toFixed(1)}% roi=${sc.roi?.toFixed(3)} (${sc.bets} bets) ${JSON.stringify(stats)}`);
    const roundArr = (a) => a.map((x) => +x.toFixed(4));
    const fitOut = {
      ...fit, proxy: { a: r3(fit.proxy.a), b: r3(fit.proxy.b) }, rho: r3(fit.rho), ht: r3(fit.ht),
      goals: { bh: r3(fit.goals.bh), ba: r3(fit.goals.ba), att: roundArr(fit.goals.att), def: roundArr(fit.goals.def), n: fit.goals.n.map((x) => +x.toFixed(1)) },
      stats: Object.fromEntries(Object.entries(fit.stats).map(([k, s]) => [k, { bh: r3(s.bh), ba: r3(s.ba), att: roundArr(s.att), def: roundArr(s.def), r: Number.isFinite(s.r) ? r3(s.r) : null }])),
      refs: fit.refs ? Object.fromEntries(Object.entries(fit.refs).map(([k, v]) => [k, { f: r3(v.f), n: v.n }])) : null,
    };
    const fixtures = b.fixtures.map((f) => [f.d, f.t, b.ti.get(f.home), b.ti.get(f.away), f.ref]);
    const file = {
      id: lg.id, name: lg.name, country: lg.country, flag: lg.flag, updated: index.updated, ref: REF,
      teams: b.teams, cur: b.curTeams.map((t) => b.ti.get(t)), promoted, refs: b.refsList,
      seasonStart: Math.min(...b.rows.filter((r) => r.season === b.curSeason).map((r) => r.d)),
      hasStats: !!lg.fd, m: window.map((m) => m.map((x) => (typeof x === 'number' ? r3(x) : x))), old,
      fit: fitOut, players: b.players, playersSrc: b.playersSrc, inj: b.inj, injSrc: b.absSrc, fixtures, bt: btOut,
    };
    await writeFile(`${OUT}/leagues/${lg.id}.json`, JSON.stringify(file));
    const quick = new Map();
    for (const f of b.fixtures) {
      try {
        const r = analyze(file, b.ti.get(f.home), b.ti.get(f.away), { params });
        const ou = r.markets.find((g) => g.id === 'ou').items.find((it) => it.l === 'Over 2.5').pw;
        const gg = r.markets.find((g) => g.id === 'gg').items[0].pw;
        quick.set(f, [...r.p, ou, gg].map((x) => +x.toFixed(3)));
      } catch (e) { log(`  quick ${f.home}-${f.away}: ${e.message}`); }
    }
    index.leagues.push({ id: lg.id, name: lg.name, country: lg.country, flag: lg.flag, teams: b.curTeams, hasStats: !!lg.fd, players: b.playersSrc, inj: b.absSrc, n: window.length, bt: { n: sc.n, ll: btOut.ll, llMkt: btOut.llMkt, llSame: btOut.llSame, nMkt: sc.nMkt, acc: btOut.acc, roi: btOut.roi, bets: btOut.bets } });
    for (const f of b.fixtures) index.fixtures.push([lg.id, f.t || isoDay(f.d), f.home, f.away, quick.get(f) || null]);
  }
  // A league whose sources failed this week keeps last week's published file.
  const prevUrl = process.env.PREV_URL;
  if (prevUrl && missing.length) {
    const prev = await getJson(`${prevUrl}/data/index.json`);
    for (const lg of missing) {
      const old = await getText(`${prevUrl}/data/leagues/${lg.id}.json`);
      const entry = prev?.leagues?.find((x) => x.id === lg.id);
      if (!old || !entry) continue;
      await writeFile(`${OUT}/leagues/${lg.id}.json`, old);
      index.leagues.push({ ...entry, stale: true });
      log(`${lg.id}: kept previous data`);
    }
  }
  index.fixtures.sort((a, b) => String(a[1]).localeCompare(String(b[1])));
  await writeFile(`${OUT}/index.json`, JSON.stringify(index));
  log(`\nwrote ${built.length} leagues to ${OUT}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
