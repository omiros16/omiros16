// Leagues covered by the app and where each kind of data comes from.
//   fd:  football-data.co.uk division code (full match stats) or `new` file (results only)
//   us:  Understat league (player xG), espn: ESPN slug (player stats for other leagues)
//   sg:  sportsgambler.com injury page, fpl: Fantasy Premier League injuries
export const LEAGUES = [
  { id: 'E0', name: 'Premier League', country: 'Αγγλία', flag: '🏴󠁧󠁢󠁥󠁮󠁧󠁿', fd: 'E0', us: 'EPL', espn: 'eng.1', sg: 'england-premier-league', fpl: true },
  { id: 'SP1', name: 'La Liga', country: 'Ισπανία', flag: '🇪🇸', fd: 'SP1', us: 'La_liga', espn: 'esp.1', sg: 'spain-la-liga' },
  { id: 'I1', name: 'Serie A', country: 'Ιταλία', flag: '🇮🇹', fd: 'I1', us: 'Serie_A', espn: 'ita.1', sg: 'italy-serie-a' },
  { id: 'D1', name: 'Bundesliga', country: 'Γερμανία', flag: '🇩🇪', fd: 'D1', us: 'Bundesliga', espn: 'ger.1', sg: 'germany-bundesliga' },
  { id: 'F1', name: 'Ligue 1', country: 'Γαλλία', flag: '🇫🇷', fd: 'F1', us: 'Ligue_1', espn: 'fra.1', sg: 'france-ligue-1' },
  { id: 'G1', name: 'Super League', country: 'Ελλάδα', flag: '🇬🇷', fd: 'G1', espn: 'gre.1', fotmob: 135 },
  { id: 'N1', name: 'Eredivisie', country: 'Ολλανδία', flag: '🇳🇱', fd: 'N1', espn: 'ned.1', sg: 'netherlands-eredivisie' },
  { id: 'P1', name: 'Primeira Liga', country: 'Πορτογαλία', flag: '🇵🇹', fd: 'P1', espn: 'por.1', sg: 'portugal-primeira-liga' },
  { id: 'B1', name: 'Pro League', country: 'Βέλγιο', flag: '🇧🇪', fd: 'B1', espn: 'bel.1', sg: 'belgium-first-division-a' },
  { id: 'T1', name: 'Süper Lig', country: 'Τουρκία', flag: '🇹🇷', fd: 'T1', espn: 'tur.1', sg: 'turkey-super-lig' },
  { id: 'SC0', name: 'Premiership', country: 'Σκωτία', flag: '🏴󠁧󠁢󠁳󠁣󠁴󠁿', fd: 'SC0', espn: 'sco.1', sg: 'scottish-premiership' },
  { id: 'AUT', name: 'Bundesliga', country: 'Αυστρία', flag: '🇦🇹', fdNew: 'AUT', fdCountry: 'Austria', espn: 'aut.1', sg: 'austria-bundesliga' },
  { id: 'SWZ', name: 'Super League', country: 'Ελβετία', flag: '🇨🇭', fdNew: 'SWZ', fdCountry: 'Switzerland', espn: 'sui.1', sg: 'switzerland-super-league' },
  { id: 'DNK', name: 'Superliga', country: 'Δανία', flag: '🇩🇰', fdNew: 'DNK', fdCountry: 'Denmark', espn: 'den.1', sg: 'denmark-superliga' },
  { id: 'POL', name: 'Ekstraklasa', country: 'Πολωνία', flag: '🇵🇱', fdNew: 'POL', fdCountry: 'Poland' },
  { id: 'ROU', name: 'Liga I', country: 'Ρουμανία', flag: '🇷🇴', fdNew: 'ROU', fdCountry: 'Romania', espn: 'rou.1' },
  { id: 'NOR', name: 'Eliteserien', country: 'Νορβηγία', flag: '🇳🇴', fdNew: 'NOR', fdCountry: 'Norway', espn: 'nor.1', sg: 'norway-eliteserien' },
  { id: 'SWE', name: 'Allsvenskan', country: 'Σουηδία', flag: '🇸🇪', fdNew: 'SWE', fdCountry: 'Sweden', espn: 'swe.1', sg: 'sweden-allsvenskan' },
  { id: 'FIN', name: 'Veikkausliiga', country: 'Φινλανδία', flag: '🇫🇮', fdNew: 'FIN', fdCountry: 'Finland', espn: 'fin.1' },
  { id: 'IRL', name: 'Premier Division', country: 'Ιρλανδία', flag: '🇮🇪', fdNew: 'IRL', fdCountry: 'Ireland', espn: 'irl.1', sg: 'ireland-premier-division' },
  { id: 'RUS', name: 'Premier League', country: 'Ρωσία', flag: '🇷🇺', fdNew: 'RUS', fdCountry: 'Russia', us: 'RFPL', espn: 'rus.1', sg: 'russia-premier-league' },
];
