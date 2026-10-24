// Team search that understands Greek: common Greek club names map to the names used in the
// data, and anything else is transliterated to Latin letters and matched loosely.
const GREEK_ALIASES = {
  'ολυμπιακος': 'Olympiakos', 'θρυλος': 'Olympiakos', 'παναθηναικος': 'Panathinaikos', 'παο': 'Panathinaikos',
  'αεκ': 'AEK', 'παοκ': 'PAOK', 'αρης': 'Aris', 'οφη': 'OFI Crete', 'αστερας': 'Asteras Tripolis', 'ατρομητος': 'Atromitos',
  'παναιτωλικος': 'Panetolikos', 'βολος': 'Volos NFC', 'λεβαδειακος': 'Levadeiakos', 'κηφισια': 'Kifisia', 'καλαματα': 'Kalamata',
  'ηρακλης': 'Iraklis', 'λαμια': 'Lamia', 'πανσερραικος': 'Panserraikos', 'αθενς κλαμπ': 'Athens Kallithea',
  'αρσεναλ': 'Arsenal', 'αστον βιλα': 'Aston Villa', 'μπορνμουθ': 'Bournemouth', 'μπρεντφορντ': 'Brentford', 'μπραιτον': 'Brighton',
  'τσελσι': 'Chelsea', 'κρισταλ πάλας': 'Crystal Palace', 'κρισταλ παλας': 'Crystal Palace', 'εβερτον': 'Everton', 'φουλαμ': 'Fulham',
  'λιβερπουλ': 'Liverpool', 'μαντσεστερ σιτι': 'Man City', 'σιτι': 'Man City', 'μαντσεστερ γιουναιτεντ': 'Man United', 'γιουναιτεντ': 'Man United',
  'νιουκαστλ': 'Newcastle', 'νοτιγχαμ': "Nott'm Forest", 'φόρεστ': "Nott'm Forest", 'φορεστ': "Nott'm Forest", 'τοτεναμ': 'Tottenham',
  'γουεστ χαμ': 'West Ham', 'γουλβς': 'Wolves', 'λιντς': 'Leeds', 'μπαρνλι': 'Burnley', 'σαντερλαντ': 'Sunderland', 'κοβεντρι': 'Coventry',
  'χαλ': 'Hull', 'ιπσουιτς': 'Ipswich', 'λεστερ': 'Leicester', 'σαουθαμπτον': 'Southampton',
  'ρεαλ μαδριτης': 'Real Madrid', 'ρεαλ': 'Real Madrid', 'μπαρτσελονα': 'Barcelona', 'μπαρσα': 'Barcelona', 'ατλετικο μαδριτης': 'Ath Madrid',
  'ατλετικο': 'Ath Madrid', 'αθλετικ μπιλμπαο': 'Ath Bilbao', 'μπιλμπαο': 'Ath Bilbao', 'σεβιλλη': 'Sevilla', 'μπετις': 'Betis',
  'σοσιεδαδ': 'Sociedad', 'βιγιαρεαλ': 'Villarreal', 'βαλενθια': 'Valencia', 'βαλενσια': 'Valencia', 'θελτα': 'Celta', 'σελτα': 'Celta',
  'τζιρονα': 'Girona', 'οσασουνα': 'Osasuna', 'χεταφε': 'Getafe', 'μαγιορκα': 'Mallorca', 'ραγιο': 'Vallecano',
  'αλαβες': 'Alaves', 'εσπανιολ': 'Espanol', 'λεβαντε': 'Levante', 'ελτσε': 'Elche', 'οβιεδο': 'Oviedo',
  'ιντερ': 'Inter', 'μιλαν': 'Milan', 'γιουβεντους': 'Juventus', 'γιουβε': 'Juventus', 'ναπολι': 'Napoli', 'ρομα': 'Roma', 'λατσιο': 'Lazio',
  'αταλαντα': 'Atalanta', 'φιορεντινα': 'Fiorentina', 'μπολονια': 'Bologna', 'τορινο': 'Torino', 'τζενοα': 'Genoa', 'ουντινεζε': 'Udinese',
  'κομο': 'Como', 'κάλιαρι': 'Cagliari', 'καλιαρι': 'Cagliari', 'λετσε': 'Lecce', 'παρμα': 'Parma', 'σασουολο': 'Sassuolo', 'βερονα': 'Verona',
  'μπαγερν': 'Bayern Munich', 'μπάγερν': 'Bayern Munich', 'ντορτμουντ': 'Dortmund', 'λεβερκουζεν': 'Leverkusen', 'λειψια': 'RB Leipzig',
  'αιντραχτ': 'Ein Frankfurt', 'φρανκφουρτη': 'Ein Frankfurt', 'στουτγκαρδη': 'Stuttgart', 'βολφσμπουργκ': 'Wolfsburg',
  'γκλαντμπαχ': "M'gladbach", 'φραιμπουργκ': 'Freiburg', 'χοφενχαιμ': 'Hoffenheim', 'βερντερ': 'Werder Bremen', 'βρεμη': 'Werder Bremen',
  'ουνιον βερολινου': 'Union Berlin', 'μαιντς': 'Mainz', 'αουγκσμπουργκ': 'Augsburg', 'αμβουργο': 'Hamburg', 'κολωνια': 'FC Koln',
  'σαιντ παουλι': 'St Pauli', 'χαιντενχαιμ': 'Heidenheim',
  'παρι': 'Paris SG', 'παρι σεν ζερμεν': 'Paris SG', 'μαρσειγ': 'Marseille', 'μασσαλια': 'Marseille', 'λυων': 'Lyon', 'μονακο': 'Monaco',
  'λιλ': 'Lille', 'νις': 'Nice', 'λανς': 'Lens', 'ρεν': 'Rennes', 'στρασβουργο': 'Strasbourg', 'ναντ': 'Nantes', 'τουλουζ': 'Toulouse',
  'αγιαξ': 'Ajax', 'αιντχοφεν': 'PSV Eindhoven', 'φεγενορντ': 'Feyenoord', 'αλκμααρ': 'AZ Alkmaar', 'τβεντε': 'Twente', 'ουτρεχτη': 'Utrecht',
  'μπενφικα': 'Benfica', 'πορτο': 'Porto', 'σπορτινγκ': 'Sp Lisbon', 'σπορτινγκ λισαβονας': 'Sp Lisbon', 'μπραγκα': 'Sp Braga', 'γκιμαραες': 'Guimaraes',
  'γαλατασαραι': 'Galatasaray', 'φενερμπαχτσε': 'Fenerbahce', 'μπεσικτας': 'Besiktas', 'τραμπζονσπορ': 'Trabzonspor', 'μπασακσεχιρ': 'Buyuksehyr',
  'σελτικ': 'Celtic', 'ρειντζερς': 'Rangers', 'αμπερντιν': 'Aberdeen', 'χαρτς': 'Hearts', 'χιμπερνιαν': 'Hibernian',
  'μπριζ': 'Club Brugge', 'κλαμπ μπριζ': 'Club Brugge', 'αντερλεχτ': 'Anderlecht', 'γκενκ': 'Genk', 'γανδη': 'Gent', 'σταντάρ': 'Standard', 'σανταρ': 'Standard',
  'ουνιον': 'St. Gilloise', 'σαλτσμπουργκ': 'Salzburg', 'ραπιντ βιεννης': 'Rapid Vienna', 'βασιλεια': 'Basel', 'γιανγκ μποις': 'Young Boys',
  'κοπεγχαγη': 'FC Copenhagen', 'μιντιλαντ': 'Midtjylland', 'μπόντο': 'Bodo/Glimt', 'μποντο γκλιμτ': 'Bodo/Glimt', 'μαλμε': 'Malmo FF',
  'ζενιτ': 'Zenit', 'σπαρτακ μοσχας': 'Spartak Moscow', 'τσσκα': 'CSKA Moscow', 'λεγκια': 'Legia', 'στεαουα': 'FCSB',
};

const DIGRAPHS = [['ου', 'ou'], ['μπ', 'mp'], ['ντ', 'nt'], ['γκ', 'gk'], ['γγ', 'ng'], ['τσ', 'ts'], ['τζ', 'tz'], ['αι', 'ai'], ['ει', 'ei'], ['οι', 'oi'], ['αυ', 'av'], ['ευ', 'ev'], ['θ', 'th'], ['χ', 'ch'], ['ψ', 'ps'], ['ξ', 'x']];
const LETTERS = { α: 'a', β: 'v', γ: 'g', δ: 'd', ε: 'e', ζ: 'z', η: 'i', ι: 'i', κ: 'k', λ: 'l', μ: 'm', ν: 'n', ο: 'o', π: 'p', ρ: 'r', σ: 's', ς: 's', τ: 't', υ: 'y', φ: 'f', ω: 'o' };

export const plain = (s) => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/ς/g, 'σ').replace(/[’'`.]/g, '').replace(/\s+/g, ' ').trim();

export function latin(s) {
  let t = plain(s);
  t = t.replace(/(^|\s)μπ/g, '$1b').replace(/(^|\s)ντ/g, '$1d').replace(/(^|\s)γκ/g, '$1g');
  for (const [g, l] of DIGRAPHS) t = t.split(g).join(l);
  return t.replace(/[α-ω]/g, (c) => LETTERS[c] ?? c);
}

function grams(s) { const g = new Set(), t = ` ${s} `; for (let i = 0; i < t.length - 2; i++) g.add(t.slice(i, i + 3)); return g; }
function dice(a, b) { const A = grams(a), B = grams(b); let n = 0; for (const x of A) if (B.has(x)) n++; return (2 * n) / (A.size + B.size || 1); }

// entries: [{ name, league, ... }]; returns the best matches for the query.
export function searchTeams(entries, query, limit = 8) {
  const q = plain(query);
  if (!q) return [];
  const ql = latin(q);
  const aliasTargets = new Set();
  for (const [g, target] of Object.entries(GREEK_ALIASES)) if (plain(g).startsWith(q) || (q.length >= 3 && plain(g).includes(q))) aliasTargets.add(target);
  const scored = entries.map((e) => {
    const n = plain(e.name);
    let s = 0;
    if (aliasTargets.has(e.name)) s = 1.1;
    else if (n === ql) s = 1.05;
    else if (n.startsWith(ql)) s = 1;
    else if (n.split(' ').some((w) => w.startsWith(ql))) s = 0.9;
    else if (n.includes(ql)) s = 0.8;
    else if (ql.length >= 3) s = dice(n, ql) * 0.85;
    return [s, e];
  }).filter(([s]) => s >= 0.35);
  scored.sort((a, b) => b[0] - a[0] || a[1].name.localeCompare(b[1].name));
  return scored.slice(0, limit).map(([, e]) => e);
}
