# research/

Η έρευνα πίσω από τη στρατηγική `mr_ensemble`. Η αναφορά με όλα τα αποτελέσματα είναι στο
[../RESEARCH.md](../RESEARCH.md).

| Αρχείο | Τι κάνει |
|---|---|
| `PREREGISTRATION.md` | Υποθέσεις, παράμετροι και κανόνες απόφασης, γραμμένα πριν από κάθε δοκιμή |
| `AMENDMENT_1..4.md` | Κάθε απόκλιση από το σχέδιο, γραμμένη πριν το επόμενο βήμα |
| `fetch_data.py`, `data_manifest.json` | Κατεβάζει τα δημόσια δεδομένα στο `data_raw/` (δεν ανεβαίνει στο git) και ελέγχει ότι είναι ακριβώς αυτά της έρευνας (SHA-256) |
| `datasets.py` | Φόρτωση και χωρισμός σε ανακάλυψη / επικύρωση / holdout. `nq_ohlc_spot`: τα NQ futures χωρίς τα άλματα του roll και χωρίς carry, σαν CFD |
| `lab.py` | Γρήγορος backtester ημερήσιων κανόνων με κόστη και swaps ανά νύχτα |
| `hypotheses.py` | Οι 85 προκαθορισμένες δοκιμές |
| `run_discovery.py`, `effective_trials.py` | Φάση 1 και Amendment 1 |
| `run_validation.py`, `run_holdout.py`, `run_crossmarket.py` | Out-of-sample, το καθένα μία φορά |
| `run_analysis.py` | Οι περιγραφικοί πίνακες του RESEARCH.md §2–§4 (volatility targeting, φρένο καθεστώτος, πίνακας ετών, DSR του συνδυασμού, συσχετίσεις) |
| `prop_ledger.py`, `run_prop_ledger.py` | **Η προσομοίωση FTMO του RESEARCH.md §5**: το bot όπως είναι χτισμένο (tickets, balance/equity, ημερήσιο stop του `risk.py`, κανόνας μεσανύχτων της FTMO), σε πραγματικές διαδρομές και σε bootstrap. Με `pip install numba` τρέχει σε λίγα λεπτά. Το `prop_ledger_check.py` είναι δεύτερη, ανεξάρτητη υλοποίηση· το `test_lab.py` ελέγχει ότι δίνουν τα ίδια |
| `prop_sim.py`, `prop_sim2.py` | Παλιότερα μοντέλα με αποδόσεις (όχι tickets). Το stop εφαρμόζεται στο equity του προηγούμενου κλεισίματος, άρα **υπερεκτιμούν** τις πιθανότητες. Μένουν για σύγκριση |
| `export_csv.py` | Γράφει τα δεδομένα σε CSV για το `python -m tradingbot backtest` |
| `results/` | Τα αποτελέσματα που αναφέρονται στο RESEARCH.md |
| `test_lab.py` | Έλεγχοι ορθότητας και causality του research engine |
