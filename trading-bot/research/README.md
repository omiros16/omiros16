# research/

Η έρευνα πίσω από τη στρατηγική `mr_ensemble`. Η αναφορά με όλα τα αποτελέσματα είναι στο
[../RESEARCH.md](../RESEARCH.md).

| Αρχείο | Τι κάνει |
|---|---|
| `PREREGISTRATION.md` | Υποθέσεις, παράμετροι και κανόνες απόφασης, γραμμένα πριν από κάθε δοκιμή |
| `AMENDMENT_1..4.md` | Κάθε απόκλιση από το σχέδιο, γραμμένη πριν το επόμενο βήμα |
| `fetch_data.py` | Κατεβάζει τα δημόσια δεδομένα στο `data_raw/` (δεν ανεβαίνει στο git) |
| `datasets.py` | Φόρτωση και χωρισμός σε ανακάλυψη / επικύρωση / holdout |
| `lab.py` | Γρήγορος backtester ημερήσιων κανόνων με κόστη και swaps ανά νύχτα |
| `hypotheses.py` | Οι 85 προκαθορισμένες δοκιμές |
| `run_discovery.py`, `effective_trials.py` | Φάση 1 και Amendment 1 |
| `run_validation.py`, `run_holdout.py`, `run_crossmarket.py` | Out-of-sample, το καθένα μία φορά |
| `prop_sim.py`, `prop_sim2.py` | Προσομοίωση FTMO (μόχλευση, ημερήσιο stop) |
| `export_csv.py` | Γράφει τα δεδομένα σε CSV για το `python -m tradingbot backtest` |
| `results/` | Τα αποτελέσματα που αναφέρονται στο RESEARCH.md |
| `test_lab.py` | Έλεγχοι ορθότητας και causality του research engine |
