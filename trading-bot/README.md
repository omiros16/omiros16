# Trading bot: US100 / US500 swing σε MT5 (FTMO / E8)

Αυτόματο bot που ανοίγει και κλείνει θέσεις μόνο του, 24 ώρες το 24ωρο, σε MetaTrader 5 μέσω Python.
Είναι χτισμένο πάνω στη συζήτηση στο [`docs/conversation.md`](docs/conversation.md) (9–11/10/2026).

> **Η στρατηγική που τρέχει (`mr_ensemble`) βγήκε από έρευνα 89 δοκιμών σε 36 χρόνια
> δεδομένων: δες το [RESEARCH.md](RESEARCH.md).** Είναι ημερήσιο mean reversion σε US100/US500
> με volatility targeting. Out-of-sample (2019–2026, NASDAQ-100): Sharpe περίπου 1.0, +5.5–6.6%
> τον χρόνο χωρίς μόχλευση, max drawdown −6%. Είναι μικρό, σταθερό edge, όχι εγγύηση: δούλεψε
> μόνο σε αμερικανικούς δείκτες και δεν δούλευε πριν το 1990. Πριν το live: 2–4 εβδομάδες paper.

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
4. Συμπλήρωσε το `.env` (MT5 login/password/server, Telegram) και έλεγξε το `config/config.yaml`.
5. Τρέξε `python -m tradingbot check`. Συνδέεται στο MT5 και δείχνει:
   - αν ο λογαριασμός είναι DEMO ή REAL,
   - αν είναι ανοιχτό το Algo Trading,
   - το πραγματικό value/point/lot, τα lots και το spread για κάθε σύμβολο.

   Διόρθωσε το `value_per_point` στο config αν διαφέρει: το backtest το χρειάζεται σωστό.

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
```

Τα δεδομένα της έρευνας (NQ futures 2019–2026 κ.λπ.) τα φτιάχνουν τα
`python research/fetch_data.py` και `python research/export_csv.py`, μέσα στο `data/`.

Βγάζει metrics, Monte Carlo και φάκελο `reports/...` με `trades.csv`, `equity.csv` και `equity.png`.
Κοίτα ιδιαίτερα το **Worst day %** (σε σχέση με το 5% της FTMO) και το **Max drawdown %**.

Αν δοκιμάζεις παραλλαγές με το χέρι (`--params '{"stop_atr": 2.5}'`), δήλωσε πόσες έχεις δοκιμάσει
με `--trials N`. Αλλιώς το Deflated Sharpe σε κοροϊδεύει.

### 3. Out-of-sample και walk-forward

Για το `mr_ensemble` αυτό έχει γίνει ήδη στην έρευνα ([RESEARCH.md](RESEARCH.md)), και η στρατηγική
δεν έχει παραμέτρους προς βελτιστοποίηση. Για δική σου στρατηγική:

```bash
python -m tradingbot walkforward --slot <slot> --data data/US100_D1.csv
```

Βελτιστοποιεί στο grid του config σε 1.000 bars, το δοκιμάζει στα επόμενα 250 που δεν έχει δει,
προχωράει, και κρίνει **μόνο** το ενωμένο out-of-sample αποτέλεσμα. Ο κανόνας είναι:

- **Deflated Sharpe > 0.95** → πάει σε paper trading.
- Αλλιώς → δεν πάει live. Πέταξέ το ή άλλαξε την υπόθεση, όχι τις παραμέτρους μέχρι να «βγει».

### 4. Paper trading 2–4 εβδομάδες
Δύο τρόποι:
- **Καλύτερος:** demo / free trial account της FTMO ή της E8, με `broker.type: mt5` και `run_bot.bat --allow-orders`. Είναι πραγματικές εντολές σε ψεύτικα χρήματα.
- `broker.type: paper`: τιμές από το MT5, οι εντολές προσομοιώνονται τοπικά (`state/paper_account.json`).

Για να δοκιμάσεις όλο το live loop χωρίς MT5 και χωρίς να περιμένεις: `python -m tradingbot dryrun`.

### 5. Live
`broker.type: mt5`. Για το challenge το default config (μόχλευση 1.5×, ημερήσιο stop 3%,
οριστική διακοπή στο −9%) έχει, κατά την προσομοίωση, περίπου 75% πιθανότητα να περάσει και τις
δύο φάσεις σε περίπου 10 + 5 μήνες. Στο funded λογαριασμό βάλε `leverage: 1.0`.

### Πώς δουλεύει το `mr_ensemble` στο live
- Μία απόφαση την ημέρα, όταν κλείνει το D1 bar του MT5 (17:00 New York). Η εκτέλεση γίνεται στο
  άνοιγμα του επόμενου bar.
- Υπολογίζει 52 κανόνες mean reversion και το μέγεθος θέσης που θέλει
  (`μόχλευση × ποσοστό κανόνων long × volatility scaling`). Μετά **προσαρμόζει** τη θέση:
  ανοίγει, προσθέτει ή κλείνει μέρος της.
- Κάθε ticket έχει backup SL από την πλευρά του broker, για την περίπτωση που το bot είναι
  offline. Η κύρια προστασία είναι το ημερήσιο όριο στο equity, που ελέγχεται κάθε 30".
- Φρένο ασφαλείας: αν η αγορά πάψει να κάνει mean reversion (αυτοσυσχέτιση 2 ετών > +0.10),
  μηδενίζει τη θέση.
- **Έλεγξε** ότι τα D1 bars του MT5 κλείνουν στις 17:00 New York (server GMT+2/+3, όπως στη FTMO).

## 24/7 στο VPS

- `run_bot.bat` (ή `run_bot.bat --allow-orders` για MT5): τρέχει το bot και το ξαναξεκινά αν σταματήσει.
- Task Scheduler → «At log on» → `run_bot.bat`. Ρύθμισε auto-login στο VPS και βάλε το MT5 να ανοίγει στο startup.
- Μετά από restart το bot θυμάται τα πάντα από το `state/bot_state.json`: τη ζημιά της ημέρας, τα requests,
  το τελευταίο bar που επεξεργάστηκε (για να μην ξαναστείλει την ίδια εντολή) και τα tickets του.
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
| `e8_one` | από το dashboard | 2.000 requests/ημέρα (μετράνε και οι αλλαγές SL/TP), 100 ανοιχτά, 50 lots/ticket. Επιτρέπει weekend. |
| `e8_signature` | από το dashboard | Όπως το E8 One, αλλά κλείνει πριν το Σαββατοκύριακο. |

Οι κανόνες είναι όπως ίσχυαν στις **11/10/2026**. **Επιβεβαίωσέ τους** στο site της firm πριν το live.

Τι κάνει ο risk manager:
- **Sizing:** lots τέτοια ώστε, αν χτυπήσει το SL, να χάνεις `risk_per_trade_pct` του equity. Στον υπολογισμό μπαίνουν spread, slippage και commission. Επειδή το SL βασίζεται στο ATR, το μέγεθος προσαρμόζεται μόνο του στη μεταβλητότητα (Moreira & Muir, ομάδα 6).
- **Ένα trade δεν ρισκάρει πάνω από** `max_trade_risk_share_of_daily` του ημερήσιου ορίου (κανόνας E8).
- **Headroom:** ακόμα κι αν χτυπήσουν **όλα** τα SL μαζί, το equity μένει πάνω από τα όρια.
- **Ημερήσιο όριο** (από το μεγαλύτερο ανάμεσα σε balance και equity στην αρχή της ημέρας CE(S)T): κλείνει τα πάντα και σταματάει ως την επόμενη ημέρα.
- **Max drawdown:** κλείνει τα πάντα και **σταματάει οριστικά** μέχρι να κάνεις `python -m tradingbot reset-kill`.
- **Kill switch:** φτιάξε ένα αρχείο `KILL_SWITCH` δίπλα στο bot (π.χ. με remote desktop από το κινητό). Κλείνει τα πάντα και σταματάει.
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
- [ ] `leverage` 1.5 για το challenge και 1.0 για funded.

## Σημειώσεις

- Οι ώρες των bars στο MT5 είναι **ώρα server** του broker. Το ημερήσιο όριο στο live μετριέται σε ώρα
  `day_reset_timezone` (Europe/Prague για FTMO). Στο backtest χρησιμοποιείται η ημερομηνία του bar.
- Το backtest χειρίζεται ένα σύμβολο τη φορά. Στο live τα slots μοιράζονται τα ίδια όρια ρίσκου του λογαριασμού.
- Το `MetaTrader5` πακέτο υπάρχει μόνο για Windows. Backtest, tests και dryrun τρέχουν παντού.
