# Trading bot: US100 / US500 swing σε MT5 (FTMO / E8)

Αυτόματο bot που ανοίγει και κλείνει θέσεις μόνο του, 24 ώρες το 24ωρο, σε MetaTrader 5 μέσω Python.
Είναι χτισμένο πάνω στη συζήτηση στο [`docs/conversation.md`](docs/conversation.md) (9–11/10/2026).

> **Η στρατηγική που τρέχει (`mr_ensemble`) βγήκε από έρευνα 89 δοκιμών σε 36 χρόνια
> δεδομένων: δες το [RESEARCH.md](RESEARCH.md).** Είναι ημερήσιο mean reversion σε US100/US500
> με volatility targeting. Out-of-sample (2019–2026, NASDAQ-100): Sharpe περίπου 1.0, +5.5–6.6%
> τον χρόνο χωρίς μόχλευση, max drawdown −6%. Είναι μικρό, σταθερό edge, όχι εγγύηση: δούλεψε
> μόνο σε αμερικανικούς δείκτες και δεν δούλευε πριν το 1990. Με τους κανόνες της FTMO το
> challenge θέλει υπομονή (χρόνια, όχι μήνες, με μόχλευση 1×). Πριν το live: 2–4 εβδομάδες paper.

## Τι κάνει

```
 data feed  ──►  signal logic  ──►  risk  ──►  execution
 (MT5 bars)      (strategies/)     (risk.py)   (MT5 order_send / paper)
                                       │
                         logging · Telegram alerts · kill switch
```

| Κομμάτι | Αρχείο | Τι κάνει |
|---|---|---|
| Data feed | `tradingbot/data/` | Κλεισμένα bars από MT5 (live), CSV (backtest), Yahoo (ημερήσια, για έρευνα) |
| Στρατηγικές | `tradingbot/strategies/` | **`mr_ensemble`** (η στρατηγική της έρευνας, ενεργή). Για σύγκριση, απενεργοποιημένες: `trend_following` και `quarters_sweep` (SMC + Quarters), που δεν έδειξαν edge |
| Έρευνα | `research/` | Δεδομένα, pre-registration, 85 δοκιμές, επικύρωση, holdout, προσομοίωση FTMO |
| Ορισμοί SMC/ICT | `tradingbot/smc.py` | Αυστηροί, ελέγξιμοι ορισμοί: swing points, BOS/CHoCH, FVG, liquidity sweep, quarter levels |
| Risk | `tradingbot/risk.py` | Sizing ανά % ρίσκου, ημερήσιο όριο ζημιάς, max drawdown, kill switch, όρια prop firm, Σαββατοκύριακο |
| Execution | `tradingbot/brokers/` | MT5 (`order_send`, magic number, retries σε requote) και paper broker |
| Backtest | `tradingbot/backtest/` | Spread, slippage, commission, swaps, walk-forward, Monte Carlo, Deflated Sharpe |
| Live loop | `tradingbot/runner.py` | Poll κάθε 30", αποθήκευση state, Telegram, ημερήσια αναφορά |

Ο ίδιος κώδικας στρατηγικής και ο ίδιος risk manager τρέχουν και στο backtest και στο live, οπότε
δεν ξαναγράφεις τη λογική και το backtest δείχνει τι θα έκαναν τα όρια.

## Εγκατάσταση

### Για backtest (οποιοσδήποτε υπολογιστής)

```bash
cd trading-bot
python -m venv .venv
# Windows: .venv\Scripts\activate    ·   Linux/Mac: source .venv/bin/activate
pip install -r requirements-dev.txt
python -m pytest            # όλα τα tests πρέπει να περνάνε
python -m tradingbot demo   # backtest σε συνθετικά δεδομένα: απλώς δείχνει ότι όλα τρέχουν
```

### Για live / paper (Windows VPS)

1. Windows VPS (περίπου €5–10/μήνα). Για FTMO, το VPS να **μη** δηλώνει τοποθεσία στις ΗΠΑ.
2. Εγκατέστησε το MT5 terminal της firm σου, κάνε login και ενεργοποίησε το **Algo Trading** (κουμπί στη γραμμή εργαλείων).
3. Python 3.11+ από το python.org, και μετά:
   ```bat
   cd trading-bot
   pip install -r requirements.txt
   copy config\config.example.yaml config\config.yaml
   copy .env.example .env
   ```
4. Συμπλήρωσε το `.env` (MT5 login/password/server, Telegram) και έλεγξε το `config/config.yaml`
   (κυρίως `account.initial_balance`: το μέγεθος του λογαριασμού, πάνω στο οποίο μετράνε τα % όρια).
5. Τρέξε `python -m tradingbot check`. Συνδέεται στο MT5 (και με `broker.type: paper`, γιατί και
   το paper παίρνει τιμές από το MT5) και δείχνει:
   - αν ο λογαριασμός είναι DEMO ή REAL και αν το balance ταιριάζει με το `initial_balance`,
   - αν είναι ανοιχτό το Algo Trading,
   - το πραγματικό value/point/lot, τα lots και το spread για κάθε σύμβολο,
   - **αν ο broker δίνει αρκετό ιστορικό**: το `mr_ensemble` θέλει 514 κλεισμένα D1 bars. Αν δεις
     `NOT ENOUGH`, δες παρακάτω «Ιστορικό».

   Διόρθωσε το `value_per_point` στο config αν διαφέρει: το backtest το χρειάζεται σωστό.

### Ιστορικό (όταν ο broker δίνει λίγα bars)

Τα νέα σύμβολα (π.χ. τα `US100.sim` της FTMO από το 2026) μπορεί να έχουν λιγότερα από 514 ημερήσια
bars. Τότε το bot **δεν κάνει συναλλαγές** και το λέει στο Telegram και στην ημερήσια αναφορά. Λύση:
δώσε παλιότερα ημερήσια bars της ίδιας αγοράς σε CSV:

```yaml
instruments:
  US100:
    history_csv: data/US100_D1_NQ_2019_2026.csv   # από research/export_csv.py, ή download --source yahoo
```

Το bot τα βάζει πριν από τα bars του broker, προσαρμοσμένα στο επίπεδο τιμής του broker, μόνο όσο
το ιστορικό του broker δεν φτάνει.

## Η ροή εργασίας (με αυτή τη σειρά)

### 1. Κανόνες σε ψευδοκώδικα
Δες το [`SPEC.md`](SPEC.md). Εκεί είναι οι ακριβείς κανόνες των δύο στρατηγικών και οι αποφάσεις που μένουν ανοιχτές.

### 2. Backtest με fees και slippage

```bash
# Ιστορικά από το MT5 της firm (στο VPS, πιο ρεαλιστικά: είναι τα CFD του broker σου)
python -m tradingbot download --source mt5 --symbol US100.cash --timeframe D1 --start 2012-01-01 --out data/US100_D1.csv
# ή ημερήσια από Yahoo (^NDX / ^GSPC), για γρήγορη έρευνα σε οποιονδήποτε υπολογιστή
python -m tradingbot download --source yahoo --symbol US100 --start 2000-01-01 --out data/US100_D1.csv

python -m tradingbot backtest --slot us100_mr --data data/US100_D1.csv
python -m tradingbot backtest --slot us100_mr --data data/US100_D1.csv --start 2023-01-01   # συναλλαγές από το 2023
```

Τα δεδομένα της έρευνας (NQ futures 2019–2026 χωρίς τα άλματα του roll, κ.λπ.) τα φτιάχνουν τα
`python research/fetch_data.py` και `python research/export_csv.py`, μέσα στο `data/`.

Βγάζει metrics, Monte Carlo και φάκελο `reports/...` με `trades.csv`, `equity.csv`, `equity.png`
και `risk_events.txt`. Κοίτα ιδιαίτερα το **Worst day %** (σε σχέση με το 5% της FTMO), το
**Max drawdown %** και τα risk events (πόσες φορές χτύπησε το ημερήσιο όριο).

- Τα metrics ξεκινούν από το πρώτο bar όπου η στρατηγική μπορεί να κάνει συναλλαγή: τα πρώτα bars
  (514 για το `mr_ensemble`) χρειάζονται μόνο για τους δείκτες. Με `--start` κρατάει τα bars πριν
  από την ημερομηνία για το warm-up.
- Τα κόστη στο config είναι σε basis points της τιμής (`spread_bp`, `slippage_bp`) και ετήσιο %
  της αξίας της θέσης (`swap_long_pct`), ώστε ένα backtest σε παλιά δεδομένα να μη «φορτώνεται»
  ψεύτικα κόστη. Βάλε τα πραγματικά του broker σου (Specification στο MT5).

Αν δοκιμάζεις παραλλαγές με το χέρι (`--params '{"leverage": 1.0}'`, στα Windows cmd:
`--params "{\"leverage\": 1.0}"`), δήλωσε πόσες έχεις δοκιμάσει με `--trials N`. Αλλιώς το Deflated
Sharpe σε κοροϊδεύει.

### 3. Out-of-sample και walk-forward

Για το `mr_ensemble` αυτό έχει γίνει ήδη στην έρευνα ([RESEARCH.md](RESEARCH.md)), και η στρατηγική
δεν έχει παραμέτρους προς βελτιστοποίηση. Για δική σου στρατηγική:

```bash
python -m tradingbot walkforward --slot <slot> --data data/US100_D1.csv
```

Βελτιστοποιεί στο grid του config σε 1.000 bars, το δοκιμάζει στα επόμενα 250 που δεν έχει δει,
προχωράει, και κρίνει **μόνο** το out-of-sample αποτέλεσμα. Όλα τα out-of-sample κομμάτια τρέχουν
σε **έναν** λογαριασμό, όπως θα γινόταν στο live (τα όρια ζημιάς και ένα kill μεταφέρονται). Αν σε
κάποιο κομμάτι καμία παράμετρος δεν κάνει αρκετά trades, μένει flat. Ο κανόνας είναι:

- **Deflated Sharpe > 0.95** → πάει σε paper trading.
- Αλλιώς → δεν πάει live. Πέταξέ το ή άλλαξε την υπόθεση, όχι τις παραμέτρους μέχρι να «βγει».

### 4. Paper trading 2–4 εβδομάδες
Δύο τρόποι:
- **Καλύτερος:** demo / free trial account της FTMO ή της E8, με `broker.type: mt5` και
  `broker.allow_orders: true` (ή `run_bot.bat --allow-orders`). Είναι πραγματικές εντολές σε ψεύτικα χρήματα.
- `broker.type: paper`: τιμές από το MT5, οι εντολές προσομοιώνονται τοπικά (`state/paper_account.json`).

Για να δοκιμάσεις όλο το live loop χωρίς MT5 και χωρίς να περιμένεις:
`python -m tradingbot dryrun --data US100=data/US100_D1_NQ_2019_2026.csv`. Κάθε dry run ξεκινάει
από καθαρό λογαριασμό. Τα αποτελέσματά του πρέπει να είναι κοντά στο backtest (όχι ίδια: το dry run
περνάει από τον πραγματικό executor, με tickets και backup stops).

### 5. Live
`broker.type: mt5`. Τι να περιμένεις από το challenge (προσομοίωση του bot όπως είναι χτισμένο,
με τον πραγματικό κανόνα της FTMO, [RESEARCH.md §5](RESEARCH.md)):

| Μόχλευση (διακοπή στο −9.5%) | 1.0× | 1.25× | **1.5× (default)** |
|---|---|---|---|
| Περνάει και τις δύο φάσεις (κάποια στιγμή) | 82% | 77% | **71%** |
| ... μέσα σε 2 χρόνια ανά φάση | 42% | 53% | 58% |
| Διάμεσος χρόνος, Phase 1 + Phase 2 | 20 + 9 μήνες | 15 + 7 μήνες | 11.5 + 5 μήνες |
| Μέσος χρόνος ως funded, αν ξαναπροσπαθείς μετά από αποτυχία | 45 μήνες | 35 μήνες | 28.5 μήνες |
| Φορές τον χρόνο που χτυπάει το ημερήσιο stop (Phase 1) | 2 | 3.1 | 4.3 |

Η στρατηγική κερδίζει λίγο τον χρόνο (+4–6% με 1×), οπότε το +10% / +5% θέλει χρόνο. Η FTMO δεν
έχει χρονικό όριο. Στο funded: διάμεσος περίπου +4.3%/χρόνο με 1.0×.

Στο funded λογαριασμό βάλε `leverage: 1.0` και `max_drawdown_pct: 9.0`.

### Πώς δουλεύει το `mr_ensemble` στο live
- Μία απόφαση την ημέρα, όταν κλείνει το D1 bar του MT5 (17:00 New York). Η εκτέλεση γίνεται στο
  άνοιγμα του επόμενου bar. Τότε η αγορά είναι συνήθως κλειστή για λίγο: το bot κρατάει την
  απόφαση και την ξαναδοκιμάζει (μετά από 1, 2, 4, 8, 15 λεπτά) μέχρι να εκτελεστεί, και μετά από
  restart.
- Υπολογίζει 52 κανόνες mean reversion και το μέγεθος θέσης που θέλει
  (`μόχλευση × ποσοστό κανόνων long × volatility scaling`). Μετά **προσαρμόζει** τη θέση:
  ανοίγει, προσθέτει ή κλείνει μέρος της.
- Κάθε ticket έχει backup SL από την πλευρά του broker, στο σημείο όπου το equity θα έφτανε το
  όριο ζημιάς του bot, για την περίπτωση που το bot είναι offline. Η κύρια προστασία είναι το
  ημερήσιο όριο στο equity, που ελέγχεται κάθε 30".
- Το ημερήσιο όριο μετράει από το μεγαλύτερο ανάμεσα σε balance και equity στην αρχή της ημέρας.
  Η θέση συνήθως κουβαλάει ζημιά από τις προηγούμενες μέρες (αγοράζει πτώσεις), οπότε το όριο
  κόβει στην πράξη τη **συνολική** ζημιά της ανοιχτής θέσης στο 3%: χτυπάει περίπου 1,5–2 φορές τον
  χρόνο με 1× και 4–5 με 1.5×. Αυτό είναι σκόπιμο: είναι πιο αυστηρό από τον κανόνα της FTMO (που
  επίσης μετράει τη ζημιά που κουβαλάει η θέση από χθες) και στην προσομοίωση δίνει τις
  περισσότερες επιτυχίες.
- Φρένο ασφαλείας: αν η αγορά πάψει να κάνει mean reversion (αυτοσυσχέτιση 2 ετών > +0.10),
  μηδενίζει τη θέση.
- **Έλεγξε** ότι τα D1 bars του MT5 κλείνουν στις 17:00 New York (server GMT+2/+3, όπως στη FTMO).

## 24/7 στο VPS

- `run_bot.bat`: τρέχει το bot και το ξαναξεκινά αν σταματήσει. Με `broker.type: mt5` βάλε
  `broker.allow_orders: true` στο config (αλλιώς, μετά από κάθε restart, το bot δεν ξεκινάει και σου
  το λέει στο Telegram). Αν το bot βγει με λάθος ρυθμίσεων, το `run_bot.bat` σταματάει αντί να
  ξαναπροσπαθεί κάθε λεπτό.
- Task Scheduler → «At log on» → `run_bot.bat`. Ρύθμισε auto-login στο VPS και βάλε το MT5 να ανοίγει στο startup.
- Μην κάνεις κλικ μέσα στο παράθυρο του bot: στα Windows ένα κλικ (QuickEdit) παγώνει το πρόγραμμα.
  Το bot το απενεργοποιεί όταν ξεκινάει, αλλά καλύτερα να το ελαχιστοποιείς.
- Μετά από restart το bot θυμάται τα πάντα από το `state/bot_state.json`: τη ζημιά της ημέρας, τα requests,
  το τελευταίο bar που επεξεργάστηκε (για να μην ξαναστείλει την ίδια εντολή), αποφάσεις που δεν
  εκτελέστηκαν ακόμα, τα tickets του και τον λογαριασμό. Αν το MT5 συνδεθεί σε **άλλο** λογαριασμό,
  το bot σταματάει τις συναλλαγές και σε ειδοποιεί.
- Logs: `logs/bot.log` (rotating) και `logs/trades.csv` (ημερολόγιο συναλλαγών).

### Telegram
1. Στο Telegram, `@BotFather` → `/newbot` → πάρε το token.
2. Στείλε ένα μήνυμα στο bot σου και πάρε το chat id από τον `@userinfobot`.
3. Βάλε τα `TELEGRAM_BOT_TOKEN` και `TELEGRAM_CHAT_ID` στο `.env`.

Λαμβάνεις: άνοιγμα/κλείσιμο θέσεων, χτυπήματα SL/TP, όρια ρίσκου, σφάλματα και ημερήσια αναφορά.

## Κανόνες ρίσκου και prop firm

Το `account.prop_firm` φορτώνει τα όρια της firm. Τα δικά σου όρια (`risk:`) πρέπει να είναι **αυστηρότερα**,
αλλιώς το bot αρνείται να ξεκινήσει.

| Preset | Daily / Max loss | Ειδικοί κανόνες |
|---|---|---|
| `ftmo_swing` | 5% / 10% | Επιτρέπει weekend. Μέχρι 200 ανοιχτές εντολές, 2.000 positions/ημέρα. Μόνο δικό σου bot. |
| `ftmo_standard` | 5% / 10% | Όπως πάνω, αλλά κλείνει πριν το Σαββατοκύριακο (funded). |
| `e8_one` | από το dashboard | 2.000 requests/ημέρα (μετράνε και οι αλλαγές SL/TP), 100 ανοιχτά, 50 lots/ticket. Επιτρέπει weekend. Βάλε `risk.firm_daily_loss_pct` από το dashboard και `day_reset_timezone` στη ζώνη ώρας του server ως όνομα IANA (π.χ. `Europe/Athens`, `Europe/Prague`· για σταθερό UTC+2 γράψε `Etc/GMT-2`, με ανάποδο πρόσημο, όχι `GMT+2`). |
| `e8_signature` | από το dashboard | Όπως το E8 One, αλλά κλείνει πριν το Σαββατοκύριακο. |

Οι κανόνες είναι όπως ίσχυαν στις **11/10/2026**. **Επιβεβαίωσέ τους** στο site της firm πριν το live.

Τι κάνει ο risk manager:
- **Στρατηγικές με SL** (`trend_following`, `quarters_sweep`):
  - **Sizing:** lots τέτοια ώστε, αν χτυπήσει το SL, να χάνεις `risk_per_trade_pct` του equity. Στον υπολογισμό μπαίνουν spread, slippage και commission. Επειδή το SL βασίζεται στο ATR, το μέγεθος προσαρμόζεται μόνο του στη μεταβλητότητα (Moreira & Muir, ομάδα 6).
  - **Ένα trade δεν ρισκάρει πάνω από** `max_trade_risk_share_of_daily` του ημερήσιου ορίου (κανόνας E8).
  - **Headroom:** ακόμα κι αν χτυπήσουν **όλα** τα SL μαζί, το equity μένει πάνω από τα όρια.
- **Στρατηγικές έκθεσης** (`mr_ensemble`): το μέγεθος το ορίζει η στρατηγική (πολλαπλάσιο του
  equity). Ο risk manager βάζει όριο `max_exposure` (3× equity, για όλο τον λογαριασμό), δεν
  αφήνει να μεγαλώσει η θέση όταν έχει χτυπήσει όριο ή είναι κοντά το Σαββατοκύριακο, και βάζει σε
  κάθε ticket backup SL στο σημείο όπου το equity θα έφτανε το όριο ζημιάς. Τα per-trade όρια
  (`risk_per_trade_pct`, `max_trade_risk_share_of_daily`) δεν εφαρμόζονται: την προστασία την
  δίνουν το ημερήσιο όριο και το max drawdown.
- **Ημερήσιο όριο** (από το μεγαλύτερο ανάμεσα σε balance και equity στην αρχή της ημέρας CE(S)T): κλείνει τα πάντα και σταματάει ως την επόμενη ημέρα.
  Η FTMO μετράει το δικό της 5% από το **balance** των μεσανυχτών, μαζί με τη ζημιά των ανοιχτών
  θέσεων. Το όριο του bot είναι πάντα πιο ψηλά. Για ασφάλεια υπάρχει και δεύτερο όριο που
  αντιγράφει τον κανόνα της FTMO (5% − 0,5% από το balance των μεσανυχτών), για την περίπτωση που
  το bot ήταν offline τα μεσάνυχτα και στο μεταξύ έκλεισε θέση με ζημιά.
- **Max drawdown:** κλείνει τα πάντα και **σταματάει οριστικά**. Για να ξαναξεκινήσει:
  `python -m tradingbot reset-kill` (το bot που τρέχει το εφαρμόζει μέσα σε 30" ή στο επόμενο
  ξεκίνημα). Το reset απορρίπτεται αν το equity είναι ακόμα κάτω από το όριο. Για **νέο ή
  επαναφερμένο λογαριασμό**: `python -m tradingbot reset-kill --rebase` (νέο αρχικό κεφάλαιο από
  το config, νέος λογαριασμός).
- **Kill switch:** φτιάξε ένα αρχείο `KILL_SWITCH` δίπλα στο bot (π.χ. με remote desktop από το κινητό). Κλείνει τα πάντα και σταματάει. Για να συνεχίσει: σβήσε το αρχείο και τρέξε `reset-kill`.
- **Σαββατοκύριακο** (`close_before_weekend`, υποχρεωτικό σε FTMO Standard funded / E8 Signature): καμία νέα θέση από Παρασκευή 16:00 UTC, κλείσιμο από 16:30 UTC (πριν από τα πρόωρα κλεισίματα), ξανά από Κυριακή 21:00 UTC. Αν η Παρασκευή είναι αργία στις ΗΠΑ (π.χ. Μεγάλη Παρασκευή), όλα γίνονται την Πέμπτη.
- **Server requests:** τις μετράει (μαζί με τα retries) και σταματάει πριν φτάσει το όριο.
- Το bot πειράζει **μόνο** θέσεις με το δικό του magic number, ποτέ τις χειροκίνητες.

## Δική σου στρατηγική (SMC/ICT)

1. Γράψε τους κανόνες στο `SPEC.md` με την ίδια αυστηρότητα (κάθε έννοια = ένας ορισμός).
2. Αντέγραψε το `tradingbot/strategies/quarters_sweep.py`. Υλοποίησε `prepare()` (δείκτες) και `on_bar()` (σήμα) χρησιμοποιώντας τα εργαλεία του `smc.py`.
3. Πρόσθεσέ την στο `STRATEGIES` στο `tradingbot/strategies/__init__.py` και στο `CONFIGS` του `tests/test_strategies.py`.
4. `python -m pytest`: τα tests ελέγχουν αυτόματα ότι η στρατηγική **δεν κοιτάει το μέλλον** (lookahead) και ότι κάθε είσοδος έχει SL.

## Checklist πριν το live

- [ ] Backtest του `mr_ensemble` σε D1 δεδομένα του δικού σου MT5 (`download --source mt5`), με το πραγματικό swap.
- [ ] 2–4 εβδομάδες paper χωρίς σφάλματα στο `logs/bot.log`, και αποτελέσματα κοντά στο backtest.
- [ ] Τα D1 bars κλείνουν στις 17:00 New York.
- [ ] Το `check` δείχνει σωστό σύμβολο και value/point, και Algo Trading ON.
- [ ] Επιβεβαιωμένοι οι τρέχοντες κανόνες της firm και ο τύπος λογαριασμού (FTMO Swing / E8 One για swing).
- [ ] Το Telegram δουλεύει (στάλθηκε το «Bot ξεκίνησε»).
- [ ] Δοκίμασες ότι το `KILL_SWITCH` κλείνει τις θέσεις σε demo.
- [ ] `leverage` 1.5 (ή 1.25) και `max_drawdown_pct` 9.5 για το challenge· 1.0 και 9.0 για funded.
- [ ] Το `check` λέει OK για το ιστορικό (514 D1 bars) ή έχεις βάλει `history_csv`.
- [ ] `broker.allow_orders: true` αν το τρέχεις από τον Task Scheduler.

## Σημειώσεις

- Οι ώρες των bars στο MT5 είναι **ώρα server** του broker. Το ημερήσιο όριο στο live μετριέται σε ώρα
  `day_reset_timezone` (Europe/Prague για FTMO). Στο backtest χρησιμοποιείται η ημερομηνία του bar και
  το equity στο άνοιγμά του.
- Το dryrun παίζει μία σειρά bars ανά σύμβολο: δύο slots στο ίδιο σύμβολο με διαφορετικό timeframe
  δεν γίνεται να τρέξουν μαζί στο dryrun.
- Το backtest χειρίζεται ένα σύμβολο τη φορά. Στο live τα slots μοιράζονται τα ίδια όρια ρίσκου του λογαριασμού.
- Το `MetaTrader5` πακέτο υπάρχει μόνο για Windows. Backtest, tests και dryrun τρέχουν παντού.
