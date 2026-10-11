# Amendment 2 (after discovery, before validation)

Written after Amendment 1 and before any validation or holdout data was
used, and before computing any statistic of the candidate defined below.

## Binding result so far

Even with N = 33 effective trials, **no single pre-registered rule passed
rule 1** (best: H4, DSR 0.76). By the pre-registered process, no strategy is
confirmed. That conclusion stands and is reported first in RESEARCH.md.

## One exploratory candidate, defined without looking at its numbers

Discovery shows a family-level pattern rather than a single winning rule:
the daily mean-reversion rules H3–H7 (52 trials) are positive on both
discovery sets for most of their grid, in line with the literature on daily
reversal in US index returns. Picking the best variant is exactly what the
Deflated Sharpe punishes, so the candidate makes **no choice inside the
family**:

**MR-ENS** = the equal-weight average of the positions of all 52 H3–H7 trials
as pre-registered (all thresholds, both exit rules, with and without the
SMA200 filter). Its position each day is the fraction of the 52 rules that
are long (0 to 1 × equity). Execution mode A (at the close), same costs.

## How it is judged

- Validation (SPX-V, COMP-V) once, with the pre-registered rule 3:
  Sharpe ≥ 0.4 on SPX-V, > 0 on COMP-V, positive under stressed costs.
- Final holdout (NDX-H, NQ-H; SPXC-H is not used because H3/H5 need the daily
  range) once, reported as it is.
- Per-family rule-2 picks (one per H3–H7) are reported for information only;
  no decision depends on them.
- Trial accounting: 85 pre-registered + 2 amendments = 87.
- RESEARCH.md labels MR-ENS as **exploratory**: it did not pass the
  pre-registered discovery bar.
