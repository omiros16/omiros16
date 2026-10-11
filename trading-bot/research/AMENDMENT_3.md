# Amendment 3: freeze the final system before the holdout

Written after validation (MR-ENS passed rule 3) and **before** the final
holdout (NDX-H, NQ-H) was run.

## Validation result that led here

MR-ENS passed rule 3: Sharpe 0.54 on SPX-V and 0.63 on COMP-V, positive
under stressed costs (CAGR 2.8% / 3.8%), similar with next-open execution.
Its worst day at full position was about −5% (2011), the size of the FTMO
daily loss limit.

## Sizing overlay (decided on discovery data only)

Volatility targeting (Moreira & Muir 2017):
`scale_t = min(2.0, 15% / σ20_t)`, σ20 = annualised standard deviation of the
last 20 daily close-to-close index returns, known at the close of t.
Position = MR-ENS position × scale.

On the discovery sets (validation was not used for this choice):

| Set | Variant | Sharpe | Max DD | Worst day | Worst day / avg position |
|---|---|---|---|---|---|
| SPX-D | raw | 0.62 | −13.0% | −5.9% | −0.27 |
| SPX-D | vol-targeted | 0.56 | −13.1% | −4.8% | −0.19 |
| COMP-D | raw | 0.35 | −24.5% | −9.7% | −0.46 |
| COMP-D | vol-targeted | 0.43 | −11.7% | −2.6% | −0.16 |

Average Sharpe is unchanged (0.49 vs 0.49) while the tail losses that end
prop-firm accounts fall sharply, so the overlay is adopted. +1 trial
(total 88).

## Frozen final system: MR-ENS-VT

1. Daily bars. At the close (mode A), compute the 52 H3–H7 rules exactly as
   pre-registered; MR-ENS = fraction of them that are long.
2. Position (× equity) = MR-ENS × min(2.0, 0.15 / σ20).
3. Costs as pre-registered. No other filters, stops or parameters.

## Holdout (run once, results reported as they are)

- NDX-H: NASDAQ-100 cash index 2019-01-02 → 2026-10-09.
- NQ-H: NQ futures bars (18:00–17:00 ET), the closest public analogue to the
  MT5 US100 CFD bars, same period.
- Reported for MR-ENS-VT (decision) and raw MR-ENS (information), modes A
  and B, normal and stressed costs.
- Note: both holdout series start on 2019-01-02 with no earlier history, so
  200-day filters only start working in late 2019.
