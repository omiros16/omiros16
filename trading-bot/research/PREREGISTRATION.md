# Pre-registration: search for a durable US100 / US500 swing edge

Written and committed **before** any hypothesis below was run on real data
(2026-10-11). The commit timestamp is the proof. Anything added after this
commit is marked as such in RESEARCH.md and counts as an extra trial.

## Goal

A long-term profitable, rule-based swing strategy for US100 / US500 CFDs at a
prop firm (FTMO / E8): positive after costs and swaps, in several market
regimes, on data it was not chosen on, with drawdowns that fit a 5% daily /
10% total loss limit.

## Data (all public, see research/fetch_data.py)

| Id | Series | Period | Use |
|---|---|---|---|
| SPX-D | S&P 500 cash index, daily OHLC (Yahoo via fja05680/dow-sp500-100-years) | 1990-01-02 → 2009-12-31 | discovery |
| COMP-D | NASDAQ Composite, daily OHLC (Yahoo via `arch` package) | 1999-01-04 → 2009-12-31 | discovery |
| SPX-V | S&P 500 daily OHLC | 2010-01-04 → 2019-12-23 | validation |
| COMP-V | NASDAQ Composite daily OHLC | 2010-01-04 → 2018-12-31 | validation |
| NDX-H | NASDAQ-100 cash index, daily OHLC (Chief-rich/nasdaq100-data) | 2019-01-02 → 2026-10-09 | final holdout, run once |
| NQ-H | NQ futures daily bars, 18:00–17:00 ET session (same repo) | 2019-01-02 → 2026-10-09 | final holdout, CFD-like bars |
| SPXC-H | S&P 500 closes only (skfolio + jamesbens/sp500-signals) | 2020-01-02 → 2026-10-09 | final holdout, close-only rules |
| SPX-E | S&P 500 daily OHLC | 1962-01-02 → 1989-12-29 | descriptive only (older regime) |
| VIX | CBOE VIX daily (datasets/finance-vix) | 1990 → 2026 | filter input |
| RF | US risk-free rate, daily (SteelCerberus/us-market-data) | 1990 → 2025 | swap cost model |

Known data defect: the S&P 500 daily *open* before 2010 equals the previous
close on most days (64–77%). Rules that need the open (overnight, open
execution) are therefore discovered on COMP-D only, and SPX-D is used for
them only from 2010 on.

## Cost model (applied to every test)

- Round-trip spread + slippage: **2.0 bp** of price (FTMO today: US100 ≈ 1.7 pt
  spread on ≈ 25,000 = 0.7 bp, US500 ≈ 0.55 pt on ≈ 6,500 = 0.9 bp; doubled
  for slippage and older, wider markets). Stress test at 4.0 bp.
- Close execution (orders placed a few minutes before the cash close using the
  partial day bar): extra 1.0 bp per round trip for the approximation.
- Swap per calendar night held: long pays (rf + 2.5%) / 360, short pays
  (2.5% − rf) / 360. No dividend credit (conservative: FTMO now folds
  dividends into the swap, which would help longs). Stress test with +1%.
- No commission (FTMO: zero commission on indices since 2026).

## Execution modes

- **A, at the close:** signal from today's bar, filled at today's close.
- **B, next open:** signal from today's bar, filled at the next open.
Selection uses mode A unless the rule is defined on the open. Mode B is
reported for every survivor as a robustness check.

## Hypotheses and parameter grids (every combination is one trial)

All long-only unless stated. C/H/L/O are daily close/high/low/open,
IBS = (C − L) / (H − L), SMA200 filter = trade only when C > SMA(200).

| # | Hypothesis (source) | Rule | Grid | Trials |
|---|---|---|---|---|
| H1 | Trend, moving average (Siegel 1994, Faber 2007, Zakamulin 2014) | long while C > SMA(n) | n ∈ {50, 100, 150, 200, 250} | 5 |
| H2 | Time-series momentum (Moskowitz et al. 2012) | long while C / C[−k] > 1 | k ∈ {63, 126, 189, 252} | 4 |
| H3 | IBS mean reversion (Pagonidis 2013) | enter IBS < a; exit next close, or exit C > H[−1] | a ∈ {.10,.15,.20,.25} × exit {next, C>H[−1]} × filter {none, SMA200} | 16 |
| H4 | RSI(2) mean reversion (Connors & Alvarez 2008) | enter RSI2 < a; exit C > SMA(5) or RSI2 > 70 | a ∈ {5,10,15,20} × exit {SMA5, RSI70} × filter {none, SMA200} | 16 |
| H5 | N-day low reversion | enter C = min of last N closes; exit C > H[−1] or 10 days | N ∈ {3,5,7,10} × filter {none, SMA200} | 8 |
| H6 | Consecutive down closes | enter after k down closes; exit first up close | k ∈ {2,3,4} × filter {none, SMA200} | 6 |
| H7 | Z-score / Bollinger reversion | z = (C − SMA20)/σ20 < −z0; exit z > 0 | z0 ∈ {1.0,1.5,2.0} × filter {none, SMA200} | 6 |
| H8 | Overnight drift (Cooper et al. 2008; Boyarchenko et al. 2023) | hold close → next open: always / if day down / if day < −1% | 3 variants (COMP-D for discovery) | 3 |
| H9 | Turn of the month (Ariel 1987; McConnell & Xu 2008) | enter close of k-th last day of month, exit close of m-th day of next month | (k,m) ∈ {(1,3),(2,3),(4,2),(1,1)} | 4 |
| H10 | Halloween (Bouman & Jacobsen 2002) | long Nov–Apr only | — | 1 |
| H11 | Short side | short IBS > b & C < SMA200, exit next close (b ∈ {.8,.9}); short while C < SMA200 | 3 variants | 3 |
| H12 | Donchian breakout (Turtle) | long C > max(H, n)[−1]; exit C < min(L, m)[−1] | (n,m) ∈ {(20,10),(55,20)} | 2 |
| H13 | SMC sweep + Quarters (the existing `quarters_sweep`, on daily bars) | sweep-and-reclaim of quarter level / swing; SL, TP = rr·R, 10-day time stop, SMA200 bias | level ∈ {quarters, swings, both} × rr ∈ {1.5,2,3} | 9 |
| H14 | Mean reversion only in high volatility | IBS < .2 and (VIX > 1-yr median) / (σ20 > 1-yr median σ20); exit next close | 2 variants | 2 |
| | | | **Total** | **85** |

Quarter size for H13 scales with price: big figure = 10^(floor(log10 P) − 1),
quarter = big figure / 4 (S&P at 1,500 → 25; NASDAQ-100 at 25,000 → 250).

## Decision rules

1. **Discovery** (SPX-D and COMP-D): every trial is run. A hypothesis
   *advances* only if
   - the median annualised Sharpe of its grid, averaged over the two
     discovery sets, is > 0.3 after costs, and
   - its best trial has a Deflated Sharpe > 0.90 with N = 85 trials.
2. For each advancing hypothesis exactly **one** parameter set goes forward:
   the one with the best *neighbourhood* (median Sharpe of itself and its
   adjacent grid values), not the single best.
3. **Validation** (SPX-V and COMP-V), run once: a frozen rule passes if its
   annualised Sharpe is ≥ 0.4 on SPX-V and > 0 on COMP-V, after costs, and it
   still has a positive return with costs at 4 bp and swap +1%.
4. **Portfolio**: validated rules are combined with inverse-volatility
   weights (estimated on discovery data only). +5 trials are charged for
   portfolio choices.
5. **Final holdout** (NDX-H, NQ-H, SPXC-H), run once, results reported as
   they are, whatever they are. If the holdout is negative the report says
   the strategy failed, and it is not recommended for live trading.
6. **Prop-firm check**: block-bootstrap the validated portfolio's daily
   returns (validation + holdout periods) to estimate the probability of
   passing the FTMO 2-step (10% then 5% target, 5% daily, 10% max loss) and
   the time it takes, for several leverage levels.
