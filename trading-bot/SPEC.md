# Spec στρατηγικών και ρίσκου

Εδώ γράφονται οι κανόνες **πριν** τον κώδικα. Κάθε κανόνας πρέπει να είναι αρκετά ακριβής ώστε δύο
άνθρωποι να βγάλουν το ίδιο σήμα στο ίδιο chart. Ό,τι δεν μπορεί να γραφτεί έτσι, δεν μπορεί να γίνει backtest.

## Κοινές συμβάσεις

- Οι αποφάσεις παίρνονται **μόνο στο κλείσιμο ενός bar** και εκτελούνται στην επόμενη τιμή (στο backtest: open του επόμενου bar).
- Τα bars είναι τιμές **bid** (όπως στο MT5). Το long ανοίγει στο ask (bid + spread).
- Κάθε είσοδος έχει **SL**. Από την απόσταση του SL βγαίνει το μέγεθος της θέσης.
- Μία θέση ανά slot (στρατηγική × σύμβολο × timeframe).

## Στρατηγική 1: `trend_following` (ομάδα 1, time-series momentum)

**Υπόθεση:** δείκτης που ανέβηκε τους τελευταίους 1, 3 και 12 μήνες τείνει να συνεχίζει
(Moskowitz, Ooi & Pedersen 2012 · Hurst, Ooi & Pedersen 2017).
**Γιατί να υπάρχει:** αργή αντίδραση των επενδυτών στην πληροφορία, και μετά υπεραντίδραση και herding.

```
timeframe: D1
score = μέσος όρος του sign(close / close[k bars πριν] − 1) για k σε lookbacks (21, 63, 252)

αν δεν έχω θέση:
    αν score > entry_threshold (0) [και close > SMA(trend_ma) αν trend_ma > 0]:
        BUY,  SL = close − stop_atr(3) × ATR(20)
    αν allow_short και score < −entry_threshold [και close < SMA]:
        SELL, SL = close + stop_atr × ATR

αν έχω long:
    αν score < exit_threshold (0): CLOSE
    αλλιώς trailing: νέο SL = max(close από την είσοδο) − trail_atr(4) × ATR
             μετακίνηση μόνο προς τα πάνω και μόνο αν βελτιώνεται ≥ 0.5 ATR (λιγότερα server requests)
```

Short είναι κλειστό εξ ορισμού. Στους δείκτες μετοχών η short πλευρά του momentum ιστορικά είναι αδύναμη,
οπότε κράτα το κλειστό εκτός αν το walk-forward δείξει το αντίθετο.

## Στρατηγική 2: `quarters_sweep` (ομάδες 4–5, SMC liquidity sweep + Quarters Theory)

**Υπόθεση:** τα stop orders μαζεύονται λίγο πέρα από τους στρογγυλούς αριθμούς και τα swing points
(Osler 2000, 2003 · Donaldson & Kim 1993). Όταν η τιμή τα «τρέχει» αλλά κλείνει ξανά από μέσα στο
ίδιο bar, η κίνηση από τα stops απορροφήθηκε και η τιμή τείνει να γυρίσει.
**Αυτό είναι υπόθεση, όχι γνωστό edge.**

Ορισμοί (`tradingbot/smc.py`):

| Έννοια | Ορισμός |
|---|---|
| Quarter level | πολλαπλάσιο του `quarter_size` (US100: 250 = 1000-point εύρος / 4) |
| Swing low | low αυστηρά χαμηλότερο από τα `k` bars πριν και ≤ από τα `k` bars μετά. Το γνωρίζουμε μόνο `k` bars αργότερα |
| Bullish sweep του L | `close[−1] > L` και `low < L − min_penetration_atr × ATR` και `close > L` |
| Βάθος | `L − low ≤ max_penetration_atr × ATR`, για να είναι sweep και όχι σπάσιμο |

```
timeframe: H4
L = το πιο κοντινό επίπεδο (quarter ή swing low, ανάλογα με level_source) που έγινε sweep σε αυτό το bar

αν δεν έχω θέση και υπάρχει bullish sweep και close > SMA(200):
    BUY, SL = low − 0.2 × ATR(14), TP = close + rr(2) × (close − SL)
αν δεν έχω θέση και υπάρχει bearish sweep και close < SMA(200):
    SELL (καθρέφτης)
αν έχω θέση για max_bars (20) bars: CLOSE (time stop)
```

Έτοιμα εργαλεία στο `smc.py` που δεν χρησιμοποιούνται ακόμα σε στρατηγική:
**BOS / CHoCH** (`market_structure`) και **FVG** (`fair_value_gaps`), με ακριβείς ορισμούς στα docstrings.

## Ρίσκο (ισχύει για όλες)

| Παράμετρος | Default | Γιατί |
|---|---|---|
| Ρίσκο ανά trade | 0.5% του equity | 6 συνεχόμενες χασούρες = 3%, ακόμα μακριά από το 5% |
| Ημερήσιο όριο | 3% του αρχικού | κάτω από το 5% της FTMO, με περιθώριο για slippage και gaps |
| Max drawdown | 8% του αρχικού (static) | κάτω από το 10% της FTMO |
| Ένα trade | ≤ 50% του ημερήσιου ορίου | κανόνας E8 |
| Θέσεις | ≤ 2 ανοιχτές, ≤ 4 trades/ημέρα | swing: λίγα trades |
| Server requests | ≤ 200/ημέρα | E8 όριο 2.000 |

## Ανοιχτές αποφάσεις (από τη συζήτηση)

- [ ] **Δικοί σου κανόνες στρατηγικής** (entry/exit, SL/TP) αν θες κάτι άλλο από τις δύο υποθέσεις. Γράψ' τους εδώ πρώτα.
- [ ] **Ρίσκο ανά trade και max drawdown** που σε βολεύουν (τα defaults είναι συντηρητικά).
- [ ] **Firm και account type:** FTMO Swing ή E8 One για να κρατάς θέσεις το Σαββατοκύριακο.
- [ ] **Μελέτες:** συλλογή των PDF, ξεκινώντας από τις ομάδες 1–4 (`trading-research-reading-list.xlsx`).

## Υποθέσεις από τη λίστα μελετών

| Ομάδα | Θέμα | Κατάσταση στο bot |
|---|---|---|
| 1 | Trend following | ✅ `trend_following` |
| 2 | Overnight vs intraday | ⬜ Ιδέα: κράτα θέση μόνο overnight (close → open). Θέλει H1 δεδομένα. |
| 3 | Pre-FOMC drift / FOMC cycle | ⬜ Θέλει ημερολόγιο FOMC σε CSV. Υπάρχουν ενδείξεις ότι το drift έχει εξασθενήσει. |
| 4 | Round numbers (≈ Quarters) | ✅ quarter levels στο `quarters_sweep` |
| 5 | Stop clusters (≈ liquidity) | ✅ sweeps σε quarters / swing points |
| 6 | Volatility sizing | ✅ SL με ATR + sizing με σταθερό ρίσκο = έκθεση αντιστρόφως ανάλογη της μεταβλητότητας |
| 7 | Data snooping | ✅ walk-forward, Deflated Sharpe (Bailey & López de Prado 2014), Monte Carlo |

**Κριτήριο απόρριψης:** walk-forward out-of-sample με Deflated Sharpe < 0.95 → η υπόθεση πετιέται.
Οι anomalies εξασθενούν μετά τη δημοσίευση (McLean & Pontiff 2016) και πολλές δεν αναπαράγονται.
Για να μετρήσει ένα αποτέλεσμα θέλει t-stat > 3 (Harvey, Liu & Zhu 2016).
