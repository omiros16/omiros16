# Amendment 1 (after discovery, before validation)

Written after running the 85 trials on the discovery sets and **before** any
validation or holdout data was used. This is a deviation from the
pre-registration and is reported as such.

## What happened

Under the pre-registered rule 1 (median grid Sharpe > 0.3 **and** best-trial
Deflated Sharpe > 0.90 with N = 85) no hypothesis advanced. The closest was
H4 (RSI(2) reversion): median Sharpe 0.47, best Sharpe 0.74, DSR 0.67.

## Why the rule is changed

The DSR formula assumes N *independent* trials. The 85 trials are mostly
small variations of the same ideas: their daily returns are highly
correlated. Measured on the discovery sets:

| Set | Trials | Li & Ji (2005) effective N | Participation ratio | Clusters, distance < 0.5 |
|---|---|---|---|---|
| SPX-D | 82 | 29.0 | 6.0 | 12 |
| COMP-D | 85 | 33.0 | 6.3 | 12 |

## The change

- Rule 1 uses **N = 33** (the larger, more conservative Li & Ji estimate)
  instead of 85 in the Deflated Sharpe. The variance of the Sharpe ratios is
  still taken across all trials. Nothing else changes: same threshold (0.90),
  same median-Sharpe condition (> 0.3), same rule 2 for picking one parameter
  set, same validation and holdout rules.
- The amendment is counted as one extra trial in the final accounting.

## Why this does not compromise the test

The protection against overfitting is the untouched validation (2010–2019)
and holdout (2019–2026) data, which are still unseen and are each run once.
The amendment only decides which candidates reach that test.
