// Turns a match prediction into the betting markets shown in the app.
// Every item carries pw (chance the bet wins) and pl (chance it loses); the rest is a refund.
import { MAXG, outcome1x2, htft, goalTiming, nbPmf, convolve } from './model.js';

const item = (l, pw, pl = 1 - pw) => ({ l, pw, pl });
const fmt = (x) => (Number.isInteger(x) ? String(x) : x.toFixed(1));

function goalDists(M) {
  const tot = new Float64Array(2 * MAXG + 1), h = new Float64Array(MAXG + 1), a = new Float64Array(MAXG + 1);
  const margin = new Map();
  for (let i = 0; i <= MAXG; i++) for (let j = 0; j <= MAXG; j++) {
    const p = M[i][j];
    tot[i + j] += p; h[i] += p; a[j] += p;
    margin.set(i - j, (margin.get(i - j) || 0) + p);
  }
  return { tot, h, a, margin };
}
const over = (dist, line) => { let s = 0; for (let k = Math.ceil(line); k < dist.length; k++) if (k > line) s += dist[k]; return s; };
const sumM = (M, f) => { let s = 0; for (let i = 0; i <= MAXG; i++) for (let j = 0; j <= MAXG; j++) if (f(i, j)) s += M[i][j]; return s; };

// Asian handicap on the home side; quarter lines are split into two half stakes.
function asian(margin, line) {
  const parts = Number.isInteger(line * 2) ? [line] : [line - 0.25, line + 0.25];
  let pw = 0, pl = 0;
  for (const l of parts) {
    for (const [m, p] of margin) {
      const r = m + l;
      if (r > 1e-9) pw += p / parts.length; else if (r < -1e-9) pl += p / parts.length;
    }
  }
  return { pw, pl };
}

function countMarket(id, title, more, cat, mh, ma, rh, ra, lines, teamLines, names, max, extra = []) {
  const ph = nbPmf(mh, rh, max), pa = nbPmf(ma, ra, max), pt = convolve(ph, pa);
  const items = [];
  for (const L of lines) items.push(item(`Over ${fmt(L)}`, over(pt, L)), item(`Under ${fmt(L)}`, 1 - over(pt, L)));
  const groups = [{ id, t: title, cat, items, mean: mh + ma }];
  const teamItems = [];
  for (const L of teamLines) {
    teamItems.push(item(`${names[0]} Over ${fmt(L)}`, over(ph, L)), item(`${names[0]} Under ${fmt(L)}`, 1 - over(ph, L)));
  }
  for (const L of teamLines) {
    teamItems.push(item(`${names[1]} Over ${fmt(L)}`, over(pa, L)), item(`${names[1]} Under ${fmt(L)}`, 1 - over(pa, L)));
  }
  groups.push({ id: id + 'T', t: `${title} ανά ομάδα`, cat, items: teamItems, mean: [mh, ma] });
  let p1 = 0, px = 0;
  for (let i = 0; i < ph.length; i++) for (let j = 0; j < pa.length; j++) {
    if (i > j) p1 += ph[i] * pa[j]; else if (i === j) px += ph[i] * pa[j];
  }
  groups.push({ id: id + '1X2', t: more, cat, items: [item(`1 (${names[0]})`, p1), item('Ίσα', px), item(`2 (${names[1]})`, 1 - p1 - px)] });
  for (const e of extra) groups.push(e(ph, pa, pt));
  return groups;
}

export function buildMarkets(pr) {
  const { M, lh, la, ht, names } = pr;
  const [H, A] = names;
  const G = [];
  const [p1, px, p2] = outcome1x2(M);
  const d = goalDists(M);

  G.push({ id: '1x2', t: 'Τελικό αποτέλεσμα', cat: 'result', items: [item(`1 · ${H}`, p1), item('X · Ισοπαλία', px), item(`2 · ${A}`, p2)] });
  G.push({ id: 'dc', t: 'Διπλή ευκαιρία', cat: 'result', items: [item('1X', p1 + px), item('X2', px + p2), item('12', p1 + p2)] });
  G.push({ id: 'dnb', t: 'Ισοπαλία όχι στοίχημα (DNB)', cat: 'result', items: [item(`${H}`, p1, p2), item(`${A}`, p2, p1)] });

  const ou = [];
  for (const L of [0.5, 1.5, 2.5, 3.5, 4.5, 5.5]) ou.push(item(`Over ${L}`, over(d.tot, L)), item(`Under ${L}`, 1 - over(d.tot, L)));
  G.push({ id: 'ou', t: 'Over / Under γκολ', cat: 'goals', items: ou, mean: lh + la });
  const gg = sumM(M, (i, j) => i > 0 && j > 0);
  G.push({ id: 'gg', t: 'Goal / No Goal', cat: 'goals', items: [item('Goal (G/G)', gg), item('No Goal (N/G)', 1 - gg)] });
  const tt = [];
  for (const L of [0.5, 1.5, 2.5, 3.5]) tt.push(item(`${H} Over ${L}`, over(d.h, L)), item(`${H} Under ${L}`, 1 - over(d.h, L)));
  for (const L of [0.5, 1.5, 2.5, 3.5]) tt.push(item(`${A} Over ${L}`, over(d.a, L)), item(`${A} Under ${L}`, 1 - over(d.a, L)));
  G.push({ id: 'team', t: 'Γκολ ανά ομάδα', cat: 'goals', items: tt, mean: [lh, la] });
  const ex = [];
  for (let k = 0; k <= 5; k++) ex.push(item(`${k} γκολ`, d.tot[k]));
  ex.push(item('6+ γκολ', over(d.tot, 5.5)));
  G.push({ id: 'exact', t: 'Ακριβής αριθμός γκολ', cat: 'goals', items: ex });
  const range = (a, b) => { let s = 0; for (let k = a; k <= Math.min(b, d.tot.length - 1); k++) s += d.tot[k]; return s; };
  G.push({ id: 'range', t: 'Εύρος γκολ (πολλαπλά)', cat: 'goals', items: [[0, 1], [1, 2], [1, 3], [2, 3], [2, 4], [3, 4], [3, 5], [4, 6]].map(([a, b]) => item(`${a}-${b} γκολ`, range(a, b))) });
  G.push({ id: 'oe', t: 'Μονά / Ζυγά', cat: 'goals', items: [item('Μονά', sumM(M, (i, j) => (i + j) % 2 === 1)), item('Ζυγά', sumM(M, (i, j) => (i + j) % 2 === 0))] });

  const scores = [];
  for (let i = 0; i <= 6; i++) for (let j = 0; j <= 6; j++) scores.push([i, j, M[i][j]]);
  scores.sort((x, y) => y[2] - x[2]);
  G.push({ id: 'cs', t: 'Ακριβές σκορ', cat: 'result', items: scores.slice(0, 16).map(([i, j, p]) => item(`${i}-${j}`, p)), matrix: true });

  const res = (f) => [sumM(M, (i, j) => i > j && f(i, j)), sumM(M, (i, j) => i === j && f(i, j)), sumM(M, (i, j) => i < j && f(i, j))];
  for (const L of [1.5, 2.5, 3.5]) {
    const o = res((i, j) => i + j > L), u = res((i, j) => i + j < L);
    G.push({ id: 'rou' + L, t: `Τελικό & Over/Under ${L}`, cat: 'combo', items: [item(`1 & Over ${L}`, o[0]), item(`1 & Under ${L}`, u[0]), item(`X & Over ${L}`, o[1]), item(`X & Under ${L}`, u[1]), item(`2 & Over ${L}`, o[2]), item(`2 & Under ${L}`, u[2])] });
  }
  const rg = res((i, j) => i > 0 && j > 0), rn = res((i, j) => !(i > 0 && j > 0));
  G.push({ id: 'rgg', t: 'Τελικό & Goal/No Goal', cat: 'combo', items: [item('1 & G/G', rg[0]), item('1 & N/G', rn[0]), item('X & G/G', rg[1]), item('X & N/G', rn[1]), item('2 & G/G', rg[2]), item('2 & N/G', rn[2])] });
  G.push({ id: 'ggou', t: 'Goal/No Goal & Over/Under 2.5', cat: 'combo', items: [
    item('G/G & Over 2.5', sumM(M, (i, j) => i > 0 && j > 0 && i + j > 2.5)), item('G/G & Under 2.5', sumM(M, (i, j) => i > 0 && j > 0 && i + j < 2.5)),
    item('N/G & Over 2.5', sumM(M, (i, j) => !(i > 0 && j > 0) && i + j > 2.5)), item('N/G & Under 2.5', sumM(M, (i, j) => !(i > 0 && j > 0) && i + j < 2.5))] });
  const dcou = [];
  for (const L of [1.5, 2.5]) {
    dcou.push(item(`1X & Over ${L}`, sumM(M, (i, j) => i >= j && i + j > L)), item(`1X & Under ${L}`, sumM(M, (i, j) => i >= j && i + j < L)));
    dcou.push(item(`X2 & Over ${L}`, sumM(M, (i, j) => i <= j && i + j > L)), item(`X2 & Under ${L}`, sumM(M, (i, j) => i <= j && i + j < L)));
    dcou.push(item(`12 & Over ${L}`, sumM(M, (i, j) => i !== j && i + j > L)));
  }
  G.push({ id: 'dcou', t: 'Διπλή ευκαιρία & Over/Under', cat: 'combo', items: dcou });

  // Handicaps
  const ah = [];
  const lines = [];
  for (let l = -3; l <= 3; l += 0.25) lines.push(Math.round(l * 4) / 4);
  // keep the lines around the fair one (where the home side is close to 50/50)
  const scored = lines.map((l) => ({ l, ...asian(d.margin, l) })).map((x) => ({ ...x, eff: x.pw / (x.pw + x.pl) }));
  const near = scored.filter((x) => x.eff > 0.22 && x.eff < 0.8);
  for (const x of near) {
    const awayL = -x.l;
    const sgn = (v) => (v > 0 ? `+${v}` : v === 0 ? '0' : `${v}`);
    ah.push({ l: `${H} ${sgn(x.l)}`, pw: x.pw, pl: x.pl }, { l: `${A} ${sgn(awayL)}`, pw: x.pl, pl: x.pw });
  }
  G.push({ id: 'ah', t: 'Ασιατικό χάντικαπ', cat: 'result', items: ah, note: 'Στα .25 και .75 το ποντάρισμα μοιράζεται σε δύο γραμμές· στις ακέραιες γραμμές η ισοβαθμία επιστρέφει τα χρήματα.' });
  const eh = [];
  for (const k of [1, 2]) {
    const m = (f) => sumM(M, f);
    eh.push(item(`${H} -${k}: 1`, m((i, j) => i - k > j)), item(`${H} -${k}: X`, m((i, j) => i - k === j)), item(`${H} -${k}: 2`, m((i, j) => i - k < j)));
    eh.push(item(`${A} -${k}: 1`, m((i, j) => i > j - k)), item(`${A} -${k}: X`, m((i, j) => i === j - k)), item(`${A} -${k}: 2`, m((i, j) => i < j - k)));
  }
  G.push({ id: 'eh', t: 'Ευρωπαϊκό χάντικαπ (3 εκβάσεις)', cat: 'result', items: eh });
  const mg = (k) => d.margin.get(k) || 0;
  let h3 = 0, a3 = 0;
  for (const [m, p] of d.margin) { if (m >= 3) h3 += p; if (m <= -3) a3 += p; }
  G.push({ id: 'margin', t: 'Διαφορά νίκης', cat: 'result', items: [
    item(`${H} με 1`, mg(1)), item(`${H} με 2`, mg(2)), item(`${H} με 3+`, h3),
    item('Ισοπαλία 0-0', M[0][0]), item('Ισοπαλία με γκολ', px - M[0][0]),
    item(`${A} με 1`, mg(-1)), item(`${A} με 2`, mg(-2)), item(`${A} με 3+`, a3)] });
  G.push({ id: 'cleansheet', t: 'Καθαρή εστία / Νίκη χωρίς παθητικό', cat: 'result', items: [
    item(`${H} καθαρή εστία`, d.a[0]), item(`${A} καθαρή εστία`, d.h[0]),
    item(`${H} νίκη χωρίς να δεχτεί`, sumM(M, (i, j) => i > 0 && j === 0)), item(`${A} νίκη χωρίς να δεχτεί`, sumM(M, (i, j) => j > 0 && i === 0))] });

  // Goal order: with constant scoring rates every ordering of the final goals is equally likely,
  // so the chance the home side scored first given (i, j) is i / (i + j).
  let hf = 0, af = 0, hfw = 0, afw = 0, hc = 0, ac = 0, hl = 0, al = 0;
  for (let i = 0; i <= MAXG; i++) for (let j = 0; j <= MAXG; j++) {
    if (i + j === 0) continue;
    const p = M[i][j], sh = i / (i + j), sa = j / (i + j);
    hf += p * sh; af += p * sa; hl += p * sh; al += p * sa;
    if (i > j) { hfw += p * sh; hc += p * sa; } // home wins: scored first / came from behind
    if (j > i) { afw += p * sa; ac += p * sh; }
  }
  G.push({ id: 'first', t: 'Πρώτη ομάδα που σκοράρει', cat: 'goals', items: [item(H, hf), item('Κανένα γκολ', M[0][0]), item(A, af)] });
  G.push({ id: 'last', t: 'Τελευταία ομάδα που σκοράρει', cat: 'goals', items: [item(H, hl), item('Κανένα γκολ', M[0][0]), item(A, al)] });
  G.push({ id: 'firstwin', t: 'Σκοράρει πρώτη & κερδίζει / ανατροπή', cat: 'combo', items: [
    item(`${H} σκοράρει πρώτη & κερδίζει`, hfw), item(`${A} σκοράρει πρώτη & κερδίζει`, afw),
    item(`${H} κερδίζει από μειονεκτική θέση`, hc), item(`${A} κερδίζει από μειονεκτική θέση`, ac)] });

  // Halves
  const hf2 = htft(lh, la, ht, M);
  const lab = ['1', 'X', '2'];
  G.push({ id: 'ht', t: 'Ημίχρονο – αποτέλεσμα', cat: 'half', items: lab.map((x, i) => item(`Ημίχρονο ${x}`, hf2.ht1[i])) });
  const htftItems = [];
  for (let r = 0; r < 3; r++) for (let c = 0; c < 3; c++) htftItems.push(item(`${lab[r]}/${lab[c]}`, hf2.T[r][c]));
  G.push({ id: 'htft', t: 'Ημίχρονο / Τελικό', cat: 'half', items: htftItems });
  const htou = [];
  for (const L of [0.5, 1.5, 2.5]) htou.push(item(`1ο ημίχρ. Over ${L}`, over(hf2.htTot, L)), item(`1ο ημίχρ. Under ${L}`, 1 - over(hf2.htTot, L)));
  for (const L of [0.5, 1.5, 2.5]) htou.push(item(`2ο ημίχρ. Over ${L}`, over(hf2.h2Tot, L)), item(`2ο ημίχρ. Under ${L}`, 1 - over(hf2.h2Tot, L)));
  G.push({ id: 'htou', t: 'Over / Under ανά ημίχρονο', cat: 'half', items: htou });
  G.push({ id: 'h2', t: '2ο ημίχρονο – αποτέλεσμα', cat: 'half', items: lab.map((x, i) => item(`2ο ημίχρ. ${x}`, hf2.H2[i])) });
  const hv = hf2.halves;
  G.push({ id: 'hsh', t: 'Ημίχρονο με τα περισσότερα γκολ', cat: 'half', items: [item('1ο ημίχρονο', hv.more1), item('Ίδια', hv.equal), item('2ο ημίχρονο', hv.more2)] });
  const e = hf2.extra;
  G.push({ id: 'halfx', t: 'Ειδικά ημιχρόνων', cat: 'half', items: [
    item('Γκολ και στα δύο ημίχρονα', e.bothHalvesGoal), item('G/G στο 1ο ημίχρονο', e.bttsHT), item('G/G στο 2ο ημίχρονο', e.btts2H),
    item(`${H} σκοράρει και στα δύο`, e.hBoth), item(`${A} σκοράρει και στα δύο`, e.aBoth),
    item(`${H} κερδίζει τουλάχιστον ένα ημίχρονο`, e.hWinEither), item(`${A} κερδίζει τουλάχιστον ένα ημίχρονο`, e.aWinEither),
    item(`${H} κερδίζει και τα δύο ημίχρονα`, e.hWinBoth), item(`${A} κερδίζει και τα δύο ημίχρονα`, e.aWinBoth)] });
  const gt = goalTiming(lh + la, ht);
  G.push({ id: 'timing', t: 'Ώρα πρώτου γκολ', cat: 'half', items: [
    item("1ο γκολ 1'-15'", gt.between(0, 15)), item("1ο γκολ 16'-30'", gt.between(15, 30)), item("1ο γκολ 31'-45'", gt.between(30, 45)),
    item("1ο γκολ 46'-60'", gt.between(45, 60)), item("1ο γκολ 61'-75'", gt.between(60, 75)), item("1ο γκολ 76'-90'", gt.between(75, 90)),
    item('Χωρίς γκολ', gt.none), item("Γκολ πριν το 27'", gt.firstBefore(27)), item("Γκολ πριν το 10'", gt.firstBefore(10))] });

  // Stats markets
  const S = pr.stats || {};
  if (S.corners) {
    const c = S.corners;
    G.push(...countMarket('corners', 'Κόρνερ', 'Περισσότερα κόρνερ', 'corners', c.mh, c.ma, c.r, c.r, [7.5, 8.5, 9.5, 10.5, 11.5, 12.5], [3.5, 4.5, 5.5, 6.5], names, 40, [
      (ph, pa) => {
        const items = [];
        const sg = (v) => (v > 0 ? `+${v}` : `${v}`);
        for (const L of [-2.5, -1.5, 1.5, 2.5]) {
          let pw = 0;
          for (let i = 0; i < ph.length; i++) for (let j = 0; j < pa.length; j++) if (i - j + L > 0) pw += ph[i] * pa[j];
          items.push(item(`${H} ${sg(L)} κόρνερ`, pw), item(`${A} ${sg(-L)} κόρνερ`, 1 - pw));
        }
        return { id: 'cornersAH', t: 'Χάντικαπ κόρνερ', cat: 'corners', items };
      },
      (ph, pa, pt) => ({ id: 'cornersOE', t: 'Μονά / Ζυγά κόρνερ', cat: 'corners', items: [item('Μονά', pt.reduce((s, p, k) => s + (k % 2 ? p : 0), 0)), item('Ζυγά', pt.reduce((s, p, k) => s + (k % 2 ? 0 : p), 0))] }),
    ]));
  }
  if (S.yellows) {
    const y = S.yellows, rd = S.reds;
    const rh = rd ? rd.mh : 0.07, ra = rd ? rd.ma : 0.08;
    // A card = yellow or red (as most bookmakers count them); reds are rare enough to treat as Poisson.
    const cardsGroup = countMarket('cards', 'Κάρτες', 'Περισσότερες κάρτες', 'cards', y.mh + rh, y.ma + ra, y.r, y.r, [2.5, 3.5, 4.5, 5.5, 6.5], [0.5, 1.5, 2.5, 3.5], names, 25);
    G.push(...cardsGroup);
    const pRed = 1 - Math.exp(-(rh + ra));
    G.push({ id: 'red', t: 'Κόκκινη κάρτα', cat: 'cards', items: [item('Ναι, κόκκινη στον αγώνα', pRed), item('Όχι', 1 - pRed), item(`${H} παίρνει κόκκινη`, 1 - Math.exp(-rh)), item(`${A} παίρνει κόκκινη`, 1 - Math.exp(-ra))] });
    // Booking points: yellow 10, red 25.
    const pyH = nbPmf(y.mh, y.r, 20), pyA = nbPmf(y.ma, y.r, 20), pr0 = [Math.exp(-(rh + ra)), 1 - Math.exp(-(rh + ra))];
    const yt = convolve(pyH, pyA);
    const bp = [];
    for (const L of [20.5, 30.5, 40.5, 50.5, 60.5]) {
      let o = 0;
      for (let k = 0; k < yt.length; k++) for (let r = 0; r < 2; r++) if (k * 10 + r * 25 > L) o += yt[k] * pr0[r];
      bp.push(item(`Over ${L}`, o), item(`Under ${L}`, 1 - o));
    }
    G.push({ id: 'bp', t: 'Πόντοι καρτών (κίτρινη 10, κόκκινη 25)', cat: 'cards', items: bp });
  }
  if (S.shots) {
    const s = S.shots;
    G.push(...countMarket('shots', 'Σουτ', 'Περισσότερα σουτ', 'shots', s.mh, s.ma, s.r, s.r, [20.5, 22.5, 24.5, 26.5, 28.5], [9.5, 11.5, 13.5, 15.5], names, 80).slice(0, 2));
  }
  if (S.sot) {
    const s = S.sot;
    G.push(...countMarket('sot', 'Σουτ στην εστία', 'Περισσότερα σουτ στην εστία', 'shots', s.mh, s.ma, s.r, s.r, [6.5, 7.5, 8.5, 9.5, 10.5], [2.5, 3.5, 4.5, 5.5], names, 40).slice(0, 3));
  }
  if (S.fouls) {
    const s = S.fouls;
    G.push(...countMarket('fouls', 'Φάουλ', 'Περισσότερα φάουλ', 'shots', s.mh, s.ma, s.r, s.r, [19.5, 21.5, 23.5, 25.5, 27.5], [9.5, 10.5, 11.5, 12.5, 13.5], names, 80).slice(0, 2));
  }

  // Players
  for (const [side, list] of [[0, pr.playersH], [1, pr.playersA]]) {
    if (!list || !list.length) continue;
    const team = names[side];
    const top = list.filter((p) => p.perGame >= 20).slice(0, 14);
    G.push({ id: 'scorer' + side, t: `Σκόρερ – ${team}`, cat: 'players', items: top.map((p) => ({ ...item(`${p.name}`, p.anytime), sub: `Πρώτος: ${(p.first * 100).toFixed(1)}% · 2+: ${(p.brace * 100).toFixed(1)}% · ~${Math.round(p.perGame)}'` })) });
    G.push({ id: 'assist' + side, t: `Ασίστ / Γκολ ή ασίστ – ${team}`, cat: 'players', items: top.slice(0, 10).flatMap((p) => [item(`${p.name} ασίστ`, p.assist), item(`${p.name} γκολ ή ασίστ`, p.gOrA)]) });
    const carded = [...list].filter((p) => p.perGame >= 30).sort((x, y) => y.card - x.card).slice(0, 10);
    if (carded.length && carded[0].card > 0) G.push({ id: 'pcard' + side, t: `Κάρτα παίκτη – ${team}`, cat: 'players', items: carded.map((p) => item(p.name, p.card)) });
  }
  return G;
}

// Fair odds, the bookmaker price needed for value, and the expected return at given odds.
export const fairOdds = (it) => (it.pw > 0 ? 1 + it.pl / it.pw : Infinity);
export const evAt = (it, odds) => it.pw * (odds - 1) - it.pl;
export function kelly(it, odds) {
  const b = odds - 1, p = it.pw / (it.pw + it.pl);
  const f = (b * p - (1 - p)) / b;
  return Math.max(0, f) * (it.pw + it.pl);
}
