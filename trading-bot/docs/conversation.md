# Trading bot με Claude Code: η συζήτηση

9–11 Οκτωβρίου 2026 · Για χρήση ως context στο Claude Code

## Σύνοψη για το Claude Code

Το αρχείο περιέχει τη συζήτηση με το Claude για ένα αυτόματο trading bot. Πρώτα είναι αυτή η σύνοψη, μετά όλη η συζήτηση αυτούσια, και στο τέλος η λίστα μελετών από το `trading-research-reading-list.xlsx`.

**Στόχος**

- Bot που ανοίγει και κλείνει θέσεις μόνο του, 24ωρο, γραμμένο με Claude Code.
- Budget: περίπου €70 σε tokens για το Claude Code.

**Πλαίσιο trader**

- Swing trading σε US100 / US500.
- Μεθοδολογία: SMC/ICT και Quarters Theory.
- Prop firms: FTMO και E8 Markets.

**Βασικά συμπεράσματα**

- Το bot ως κώδικας είναι εφικτό με €70. Η κερδοφορία δεν εξαρτάται από τα tokens και δεν είναι εγγυημένη.
- Edge βγαίνει μόνο από testing σε δεδομένα. Οι ακαδημαϊκές μελέτες είναι η πιο χρήσιμη πηγή υποθέσεων, τα βίντεο SMC/ICT όχι.
- Οι έννοιες SMC/ICT χρειάζονται αυστηρούς, ελέγξιμους ορισμούς πριν γραφτεί κώδικας.
- Σειρά: κανόνες σε ψευδοκώδικα → backtest με fees και slippage → out-of-sample και walk-forward → paper trading 2–4 εβδομάδες → live με ελάχιστο size.

**Τεχνική πρόταση του Claude (δεν έχει οριστικοποιηθεί)**

- **Πλατφόρμα:** MT5 + Python, με το επίσημο πακέτο `MetaTrader5` (trades με `order_send`), σε Windows VPS με ανοιχτό MT5 terminal. Υπάρχει και στις δύο firms.
- **Εναλλακτικές:** cTrader (cBots σε Python ή C#, σε FTMO και E8) ή TradeLocker (μόνο E8, REST API, `pip install tradelocker`).
- **Αρχιτεκτονική:** modular, με τη σειρά data feed → signal logic → execution → risk (hard daily loss limit, kill switch), μαζί με logging και alerts (Telegram).
- **Account για swing:** FTMO Swing ή E8 One, γιατί επιτρέπουν κράτημα θέσεων το Σαββατοκύριακο.

**Κανόνες που πρέπει να σέβεται το bot** (όπως ίσχυαν στις 11/10/2026, επιβεβαίωσε πριν το live)

- **FTMO:** μέχρι 200 orders ταυτόχρονα και 2.000 positions/ημέρα, χωρίς hyperactivity. Δικό σου bot, όχι third-party EA.
- **E8:** 2.000 server requests/ημέρα (μετράνε και οι αλλαγές SL/TP), 100 ανοιχτά orders+positions, 50 lots/ticket, έως 50% των trades κάτω από 1 λεπτό, όχι όλο το daily drawdown σε ένα trade. Δικό σου EA.

**Οικονομία tokens στο Claude Code**

- Πρώτα spec, μετά κώδικας. Sonnet για το μεγαλύτερο μέρος, Opus μόνο για αρχιτεκτονική ή δύσκολα bugs.
- Modular χτίσιμο με commit σε κάθε βήμα. `/compact` ή νέο session όταν μεγαλώνει το context.

**Ανοιχτά βήματα**

- Συλλογή των PDF μελετών, ξεκινώντας από τις ομάδες 1–4 (tracker: `trading-research-reading-list.xlsx`).
- Ακριβείς κανόνες στρατηγικής: entry/exit, SL/TP, risk ανά trade, max drawdown.
- Επιλογή firm και account type.

## Η συζήτηση

### Ερώτηση 1 · Παρ 9 Οκτ 2026, 10:13

> εχω 70€ σε tokens για το claude code, βασει αυτου, ποσο πιθανο ειναι να μου δημιουργησεις ενα bot το οποιο θα εχει την ικανοτητα να κανει 24ωρο ή trading

### Απάντηση Claude

**Σύντομη απάντηση:** Το να γράψεις ένα bot που τρέχει 24/7 και ανοίγει θέσεις μόνο του είναι πολύ πιθανό με €70. Το να βγάζει λεφτά είναι εντελώς άλλο θέμα και δεν εξαρτάται από τα tokens.

#### Τι είναι εφικτό με €70

| Στόχος | Πιθανότητα | Σχόλιο |
|---|---|---|
| Bot που εκτελεί κανόνες (entry/exit, SL/TP, sizing) μέσω API | Πολύ υψηλή | Python + ccxt (crypto) ή MT5/broker API (forex). Ένα MVP κοστίζει περίπου €10–30 σε tokens αν το spec είναι καθαρό. |
| Backtesting module + paper trading | Υψηλή | Θέλει άλλα περίπου €10–20 σε iterations. |
| Logging, alerts (Telegram), kill switch, risk limits | Υψηλή | Είναι φθηνά να προστεθούν. |
| Κωδικοποίηση SMC/ICT (OB, FVG, liquidity sweeps, BOS/CHoCH) | Μέτρια | Ο κώδικας είναι εύκολος. Το δύσκολο είναι οι **αυστηροί ορισμοί**, γιατί η discretionary κρίση δεν μεταφράζεται 1:1 σε κανόνες. |
| Σταθερά κερδοφόρο bot | Χαμηλή | Το edge είναι θέμα στρατηγικής και validation, όχι κώδικα. Τα περισσότερα retail bots αποτυγχάνουν μετά το backtest λόγω overfitting, lookahead bias, fees και slippage. |

#### Τι πρέπει να ξέρεις για το «24ωρο»

- **Το Claude Code γράφει το bot, δεν το τρέχει.** Για 24/7 λειτουργία χρειάζεσαι VPS (περίπου €5–10/μήνα), όχι το laptop σου.
- **Αγορά:** το crypto ανοίγει 24/7, το forex 24/5 (κλείνει το Σαββατοκύριακο).
- **Prop firms:** πολλές απαγορεύουν ή περιορίζουν EAs/bots, HFT και copy trading. Έλεγξε τους κανόνες πριν το βάλεις σε funded account.
- **Ασφάλεια:** δώσε στα API keys μόνο δικαίωμα trade, χωρίς withdrawal. Βάλε hard daily loss limit και kill switch.

#### Πώς να «τεντώσεις» τα €70

- Γράψε **πρώτα το spec**: αγορά, timeframe, ακριβείς κανόνες εισόδου/εξόδου, risk ανά trade, max drawdown. Το ασαφές spec καίει tokens σε άσκοπα iterations.
- Χρησιμοποίησε **Sonnet** για το μεγαλύτερο μέρος της δουλειάς. Το Opus κράτα το μόνο για αρχιτεκτονική ή δύσκολα bugs.
- Χτίσε **modular**: πρώτα data feed, μετά signal logic, μετά execution, μετά risk. Κάνε commit σε κάθε βήμα.
- Τρέξε `/compact` ή άνοιξε νέο session όταν μεγαλώνει το context.
- Αν σκοπεύεις να το χρησιμοποιείς συχνά, σύγκρινε το κόστος με συνδρομή που περιλαμβάνει Claude Code αντί για pay-per-token.

#### Προτεινόμενη σειρά

1. Ορίζεις τη στρατηγική σε μορφή κανόνων (ψευδοκώδικας).
2. Φτιάχνεις το backtest σε ιστορικά δεδομένα, με fees και slippage.
3. Κάνεις out-of-sample και walk-forward έλεγχο.
4. Τρέχεις paper trading 2–4 εβδομάδες.
5. Περνάς σε live με ελάχιστο size.

Αν μου δώσεις τους κανόνες της στρατηγικής σου και την αγορά/broker, μπορώ να σου γράψω το spec έτσι ώστε να το δώσεις έτοιμο στο Claude Code.

### Ερώτηση 2 · Παρ 9 Οκτ 2026, 10:16

> θεωριτικα δεν μπορεις να βρεις εσυ μια κερδοφορα στρατηγικη ?

### Απάντηση Claude

**Όχι με τρόπο που να μπορώ να σου εγγυηθώ.** Μπορώ όμως να τρέξω τη διαδικασία αναζήτησης μαζί σου. Η διαφορά έχει σημασία.

#### Γιατί δεν μπορώ απλά να «βρω» μία

- **Δεν έχω μυστική γνώση.** Ό,τι ξέρω είναι δημόσια πληροφορία. Αν μια στρατηγική είναι γνωστή και κερδοφόρα, το edge της συνήθως έχει ήδη γίνει arbitraged.
- **Το data mining παράγει ψεύτικα edges.** Αν δοκιμάσω 500 συνδυασμούς παραμέτρων, κάποιοι θα δείχνουν κερδοφόροι καθαρά από τύχη. Αυτό είναι το multiple testing problem, και το ίδιο ισχύει για άνθρωπο ή AI.
- **Το backtest δεν είναι το live.** Fees, slippage, spreads, regime changes και latency τρώνε τα περισσότερα μικρά edges.
- **Οι αγορές αλλάζουν.** Κάτι που δούλεψε στο 2020–2024 μπορεί να μη δουλεύει στο 2026.

#### Τι μπορώ να κάνω ρεαλιστικά

| Ρόλος | Τι σημαίνει |
|---|---|
| Hypothesis generation | Προτείνω ιδέες με λογική βάση: momentum, mean reversion, session breakouts, funding rate arb, volatility regimes κ.λπ. |
| Κωδικοποίηση & backtest | Γράφω και τρέχω το backtest σε πραγματικά δεδομένα, με ρεαλιστικά κόστη. |
| Αυστηρό validation | Out-of-sample, walk-forward, Monte Carlo, έλεγχο robustness σε παραμέτρους και διαφορετικά assets. |
| Κόφτης | Σου λέω ξεκάθαρα πότε κάτι είναι overfit και πρέπει να πεταχτεί. Αυτό είναι ίσως το πιο χρήσιμο. |

#### Τι είναι ρεαλιστικό να περιμένεις

- Οι περισσότερες ιδέες θα **αποτύχουν** στο validation. Αυτό είναι φυσιολογικό, όχι αποτυχία της διαδικασίας.
- Αν κάτι επιβιώσει, θα είναι πιθανότατα **μικρό edge** (π.χ. Sharpe 0.5–1.2 μετά κόστη), όχι «διπλασιάζω τον λογαριασμό».
- Τα πιο ανθεκτικά edges για retail βρίσκονται συνήθως σε απλή λογική με οικονομική εξήγηση (γιατί υπάρχει αυτό το edge;), όχι σε πολύπλοκα indicators.

#### Τι χρειάζομαι για να ξεκινήσουμε

- **Αγορά:** crypto, forex ή indices.
- **Timeframe:** scalping, intraday ή swing.
- **Κεφάλαιο και πού θα τρέξει:** προσωπικός λογαριασμός ή prop firm, γιατί αλλάζουν οι περιορισμοί.
- **Πηγή δεδομένων:** π.χ. Binance API για crypto, Dukascopy για forex.

Με αυτά μπορούμε να στήσουμε έναν research pipeline: 3–5 υποθέσεις, backtest, validation και αυστηρή απόρριψη. Ό,τι επιβιώσει πηγαίνει σε paper trading.

### Ερώτηση 3 · Παρ 9 Οκτ 2026, 10:20

> αν το feedαρω με τα καταλληλα βιντεακια και τα καταλληλα αρθτα και τις καταλληλες μελετες η πιθανοτητα που αναφερεις ως χαμηλη θα αλλαξει ?

### Απάντηση Claude

**Λίγο, όχι σημαντικά.** Το bottleneck δεν είναι η γνώση. Είναι το αν υπάρχει πραγματικό edge και αν το επιβεβαιώνεις σωστά.

#### Τι αλλάζει ανάλογα με την πηγή

| Πηγή | Επίδραση | Γιατί |
|---|---|---|
| YouTube / trading influencers (π.χ. SMC/ICT) | Σχεδόν μηδενική, συχνά αρνητική | Survivorship bias, cherry-picked charts, ασαφείς ορισμοί που δεν γίνονται falsifiable κανόνες. Τα έσοδά τους συνήθως προέρχονται από courses και affiliates, όχι από trading. |
| Άρθρα / blogs | Μικρή | Καλές για ιδέες, σπάνια με αυστηρό testing. |
| Ακαδημαϊκές μελέτες | Μέτρια, η πιο χρήσιμη από τις τρεις | Δίνουν υποθέσεις με οικονομική λογική και στατιστική τεκμηρίωση. **Όμως** οι anomalies εξασθενούν σημαντικά μετά τη δημοσίευση (McLean & Pontiff, 2016), και πολλές δεν αναπαράγονται καθόλου (Hou, Xue & Zhang, 2020). |

#### Γιατί η γνώση δεν αρκεί

- Αν κάτι υπάρχει σε δημόσιο βίντεο ή paper, το ξέρουν και χιλιάδες άλλοι, μαζί με funds που έχουν καλύτερα δεδομένα και εκτέλεση.
- Ένα edge προκύπτει από **testing σε δεδομένα**, όχι από διάβασμα. Όσο υλικό κι αν μου δώσεις, η απάντηση στο αν κάτι δουλεύει έρχεται από το backtest και το out-of-sample, όχι από τη θεωρία.
- Πρακτικό: δεν βλέπω βίντεο απευθείας. Θα χρειαζόταν transcripts, και τα trading βίντεο είναι κατά κύριο λόγο οπτικά (charts), οπότε χάνεται το μεγαλύτερο μέρος του περιεχομένου.

#### Τι αυξάνει πραγματικά τις πιθανότητες

- **Ποιοτικά δεδομένα:** tick/1m data, σωστά fees και spreads, funding rates για perps.
- **Αυστηρό validation:** walk-forward, out-of-sample, robustness σε παραμέτρους και assets.
- **Niches με λιγότερο ανταγωνισμό:** μικρότερα crypto pairs, ειδικά structural effects (funding, session opens, rollover), όπου τα μεγάλα funds δεν χωράνε λόγω liquidity.
- **Χαμηλά κόστη εκτέλεσης:** συχνά καθορίζουν αν ένα μικρό edge επιβιώνει.
- **Οικονομική λογική:** να μπορείς να εξηγήσεις *ποιος χάνει* για να κερδίζεις εσύ.

#### Τι θα έκανα με το υλικό σου

Αν μου δώσεις 5–10 ακαδημαϊκές μελέτες για την αγορά που σε ενδιαφέρει, μπορώ να:

1. Εξάγω τις testable υποθέσεις.
2. Ελέγξω αν δείχνουν ακόμα σημάδια ζωής μετά τη δημοσίευση.
3. Τις μετατρέψω σε κανόνες για backtest.

Αυτή είναι η πιο αποδοτική χρήση του υλικού. Τα βιντεάκια θα τα άφηνα έξω.

### Ερώτηση 4 · Παρ 9 Οκτ 2026, 10:22

> ωραια πες μου τι ακριβως να ψαξω

### Απάντηση Claude

Εστιάζω σε **US100/US500 swing**, αφού εκεί κάνεις trading. Ψάξε στα αγγλικά, γιατί εκεί βρίσκεται όλη η βιβλιογραφία.

#### Πού

- **Google Scholar:** γενική αναζήτηση, και μετά το «Cited by» για να βρεις follow-ups.
- **SSRN:** δωρεάν working-paper εκδόσεις των περισσότερων papers.
- **NBER Working Papers** και **arXiv (q-fin):** το arXiv με προσοχή, γιατί πολλά papers εκεί δεν είναι peer-reviewed.

#### Τι να ψάξεις, ανά θέμα

| # | Θέμα | Search terms | Αφετηρία |
|---|---|---|---|
| 1 | Trend following σε indices | `time series momentum equity index futures`, `trend following` | Moskowitz, Ooi & Pedersen (2012), *JFE* · Hurst, Ooi & Pedersen (2017), *A Century of Evidence on Trend-Following Investing* |
| 2 | Overnight vs intraday returns | `overnight returns intraday returns S&P 500` | Lou, Polk & Skouras (2019), *JFE* |
| 3 | Event / calendar effects | `pre-FOMC announcement drift`, `FOMC cycle stock returns`, `turn of the month effect` | Lucca & Moench (2015), *JF* · Cieslak, Morse & Vissing-Jorgensen (2019), *JF*. Υπάρχουν ενδείξεις ότι το pre-FOMC drift έχει εξασθενήσει, οπότε έλεγξε και follow-ups. |
| 4 | Round numbers (≈ Quarters Theory) | `psychological barriers stock index`, `round number price clustering` | Donaldson & Kim (1993), *JFQA* |
| 5 | Stop clusters (≈ liquidity pools / DOL) | `stop-loss order clustering`, `support and resistance technical analysis` | Osler (2000), FRBNY *Economic Policy Review* · Osler (2003), *JF*. Είναι σε FX, αλλά η λογική μεταφέρεται. |
| 6 | Volatility-based sizing | `volatility managed portfolios`, `volatility targeting` | Moreira & Muir (2017), *JF*. Χρήσιμο για να μένεις μέσα στα daily/max loss limits του prop firm. |
| 7 | Technical rules + data snooping | `technical trading rules data snooping` | Brock, Lakonishok & LeBaron (1992), *JF* · Sullivan, Timmermann & White (1999), *JF* |

Για SMC/ICT ως τέτοια δεν υπάρχει ακαδημαϊκή βιβλιογραφία. Οι γραμμές 4–5 είναι τα πιο κοντινά ανάλογα που μπορούν να ελεγχθούν σε backtest.

#### Μεθοδολογία, για να μην ξεγελαστούμε

- **McLean & Pontiff (2016), *JF*:** πόσο εξασθενεί ένα anomaly μετά τη δημοσίευση.
- **Harvey, Liu & Zhu (2016), *RFS*:** multiple testing, και γιατί θέλεις t-stat > 3.
- **Bailey & López de Prado (2014), *The Deflated Sharpe Ratio*:** διορθώνει το Sharpe ανάλογα με το πόσες στρατηγικές δοκίμασες.

#### Φίλτρα επιλογής

**Κράτα** ένα paper αν:

- είναι σε top journal (JF, JFE, RFS, JFQA) ή έχει πολλά citations,
- τα δεδομένα του φτάνουν όσο πιο κοντά στο σήμερα,
- περιλαμβάνει transaction costs και out-of-sample,
- έχει follow-ups που το επιβεβαιώνουν. Ψάξε `"<τίτλος>" replication` ή δες το «Cited by».

**Πέτα** papers που:

- είναι μόνο in-sample,
- δεν περιλαμβάνουν κόστη,
- υπόσχονται του τύπου «ML predicts stock price with 9X% accuracy»,
- δημοσιεύονται σε άγνωστα journals.

#### Πώς να μου τα φέρεις

- Ανέβασε τα PDF εδώ, μέχρι 10.
- Ξεκίνα από τις γραμμές **1–4**, γιατί ταιριάζουν σε swing και σε US100/US500.

### Ερώτηση 5 · Κυρ 11 Οκτ 2026, 20:11

> Create a spreadsheet from your last answer.

*(Το αίτημα διακόπηκε από τον χρήστη πριν απαντήσει το Claude.)*

### Ερώτηση 6 · Κυρ 11 Οκτ 2026, 20:12

> θα μπορείς να το κάνεις και να βρεις την εφαρμογή στην οποία θα μπορεί να πει το bot και να κάνει το trade.

### Απάντηση Claude

Φτιάχνω το spreadsheet με τη λίστα μελετών. Παράλληλα ψάχνω σε ποιες πλατφόρμες των FTMO/E8 Markets μπορεί να συνδεθεί ένα bot και με ποιο API εκτελεί trades.

*[Αρχείο: `trading-research-reading-list.xlsx`. Reading list ως tracker: συμπληρώνεις τα κίτρινα κελιά και η απόφαση Κράτα/Πέτα βγαίνει αυτόματα.]*

Το `trading-research-reading-list.xlsx` έχει 3 φύλλα:

- **Μελέτες:** tracker με τις 14 μελέτες (status, 4 φίλτρα, αυτόματη απόφαση Κράτα/Πέτα).
- **Πηγές & Φίλτρα:** πού ψάχνεις και τι σημαίνει κάθε φίλτρο.
- **Πρόοδος:** μετρητές που ενημερώνονται μόνοι τους.

#### Πού μπορεί να εκτελεί trades το bot

Η FTMO δίνει σήμερα MT4, MT5, cTrader ή TradingView. Η E8 δίνει MT5, Match-Trader, TradeLocker και cTrader. Από αυτές, τρεις έχουν ξεκάθαρο τρόπο να τις χειριστεί bot:

| Πλατφόρμα | Firms | Πώς εκτελεί το bot | Πού τρέχει |
|---|---|---|---|
| **MT5** | FTMO, E8 | Python με το επίσημο πακέτο `MetaTrader5`, που επικοινωνεί απευθείας με το MT5 terminal. Τα trades στέλνονται με `order_send`. Εναλλακτικά, native EA σε MQL5. | Δίπλα σε ανοιχτό MT5 terminal, άρα Windows VPS |
| **cTrader** | FTMO, E8 | cBots σε Python, natively μέσα στο cTrader, ή σε C# | Μέσα στο cTrader |
| **TradeLocker** | E8 | REST API που ανοίγει, αλλάζει και κλείνει orders/positions και τραβάει ιστορικά δεδομένα. Υπάρχει επίσημο Python client (`pip install tradelocker`, Python 3.11+). | Οπουδήποτε, και σε φθηνό Linux VPS |

**Πρόταση: MT5 + Python.**

- Υπάρχει και στις δύο firms, οπότε το ίδιο bot δουλεύει σε FTMO και E8.
- Είναι η ίδια γλώσσα με το backtest, άρα δεν ξαναγράφεις τη λογική.
- Θέλει Windows VPS με το terminal ανοιχτό. Η FTMO επιτρέπει γενικά VPS, αρκεί να μη δηλώνει τοποθεσία στις ΗΠΑ.

#### Κανόνες που αφορούν το bot

| | FTMO | E8 |
|---|---|---|
| Bots / EAs | Επιτρέπονται, αν το trading είναι ρεαλιστικό και δεν εμπίπτει στις forbidden practices | Επιτρέπεται οποιοδήποτε EA |
| Ίδιο EA με άλλους | Ρίσκο να σου αρνηθούν το funded account λόγω maximum capital allocation | Μπορεί να κλείσει ο λογαριασμός. Προτείνουν δικό σου EA |
| Όρια server | 200 orders ταυτόχρονα, 2.000 positions/ημέρα. Σε hyperactivity μπορεί να σου ζητήσουν αλλαγή στο EA | 2.000 requests/ημέρα (και οι αλλαγές SL/TP μετράνε), 100 ανοιχτά orders+positions, 50 lots/ticket |
| Weekend | Standard: όταν γίνεις funded, κλείνεις πριν το Σαββατοκύριακο. Swing account: κανένας περιορισμός. Στο evaluation: κανένας περιορισμός | E8 One: επιτρέπεται. E8 Signature: οι θέσεις κλείνουν στις 23:00 |
| Άλλα | — | Μέχρι 50% των trades κάτω από 1 λεπτό. Απαγορεύεται να ρισκάρεις όλο το daily drawdown σε ένα trade |

Για swing bot τα όρια server δεν σε αφορούν, αφού κάνεις λίγα trades. Σε αφορά ο κανόνας για το weekend: στη FTMO διάλεξε **Swing** account, στην E8 το **E8 One**.

Αν θες, γράφω το spec για MT5 + Python (data feed, signals, execution, risk limits, kill switch), για να το δώσεις έτοιμο στο Claude Code.

**Πηγές**

- [FTMO – Which platforms can I use for trading?](https://ftmo.com/en/faq/which-platforms-can-i-use-for-trading/)
- [FTMO – Which instruments can I trade and what strategies am I allowed to use?](https://ftmo.com/en/faq/which-instruments-can-i-trade-and-what-strategies-am-i-allowed-to-use/)
- [FTMO – Can I hold positions overnight or over the weekend?](https://ftmo.com/en/faq/can-i-hold-positions-overnight-or-over-the-weekend/)
- [FTMO – Can I travel or use VPN/VPS?](https://ftmo.com/en/faq/can-i-travel-or-use-vpn-vps/)
- [E8 – Can I use indicators or expert advisors?](https://help.e8markets.com/en/articles/5515409-can-i-use-indicators-or-expert-advisors-when-trading-the-e8-account)
- [E8 – Trading Policies and Prohibited Trading Strategies](https://help.e8markets.com/en/articles/6929927-trading-policies-and-prohibited-trading-strategies)
- [FXEmpire – E8 Markets Review 2026](https://www.fxempire.com/prop-firms/e8markets)
- [MQL5 – Python Integration](https://www.mql5.com/en/docs/python_metatrader5)
- [TradeInformer – cTrader adds native Python support](https://tradeinformer.com/?p=9449)
- [TradeLocker Public API](https://public-api.tradelocker.com)
- [tradelocker (PyPI)](https://pypi.org/project/tradelocker/0.40.0/)

## Παράρτημα: λίστα μελετών (από το spreadsheet)

| # | Ομάδα | Μελέτη | Τίτλος | Journal / Πηγή |
|---|---|---|---|---|
| 1 | 1 · Trend following | Moskowitz, Ooi & Pedersen (2012) | Time Series Momentum | Journal of Financial Economics |
| 2 | 1 · Trend following | Hurst, Ooi & Pedersen (2017) | A Century of Evidence on Trend-Following Investing | Journal of Portfolio Management |
| 3 | 2 · Overnight vs intraday | Lou, Polk & Skouras (2019) | A Tug of War: Overnight Versus Intraday Expected Returns | Journal of Financial Economics |
| 4 | 3 · Event / calendar | Lucca & Moench (2015) | The Pre-FOMC Announcement Drift | Journal of Finance |
| 5 | 3 · Event / calendar | Cieslak, Morse & Vissing-Jorgensen (2019) | Stock Returns over the FOMC Cycle | Journal of Finance |
| 6 | 4 · Round numbers | Donaldson & Kim (1993) | Price Barriers in the Dow Jones Industrial Average | Journal of Financial and Quantitative Analysis |
| 7 | 5 · Stop clusters | Osler (2000) | Support for Resistance: Technical Analysis and Intraday Exchange Rates | FRBNY Economic Policy Review |
| 8 | 5 · Stop clusters | Osler (2003) | Currency Orders and Exchange Rate Dynamics: An Explanation for the Predictive Success of Technical Analysis | Journal of Finance |
| 9 | 6 · Volatility sizing | Moreira & Muir (2017) | Volatility-Managed Portfolios | Journal of Finance |
| 10 | 7 · Technical rules | Brock, Lakonishok & LeBaron (1992) | Simple Technical Trading Rules and the Stochastic Properties of Stock Returns | Journal of Finance |
| 11 | 7 · Technical rules | Sullivan, Timmermann & White (1999) | Data-Snooping, Technical Trading Rule Performance, and the Bootstrap | Journal of Finance |
| 12 | Μεθοδολογία | McLean & Pontiff (2016) | Does Academic Research Destroy Stock Return Predictability? | Journal of Finance |
| 13 | Μεθοδολογία | Harvey, Liu & Zhu (2016) | …and the Cross-Section of Expected Returns | Review of Financial Studies |
| 14 | Μεθοδολογία | Bailey & López de Prado (2014) | The Deflated Sharpe Ratio: Correcting for Selection Bias, Backtest Overfitting, and Non-Normality | Journal of Portfolio Management |
