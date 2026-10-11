# Amendment 4: cross-market out-of-sample test (declared before running)

Written after the holdout and the first prop-firm simulation, **before**
running the frozen system on the markets below.

## Why

The US100 and US500 versions of MR-ENS-VT are 0.90 correlated (2010–2018),
so trading both adds little diversification. Other index CFDs that FTMO
offers (JP225, HK50, US30) could, and running the frozen system on markets
it was never designed on is also the strongest remaining test of the edge.

## Test

- Data: VatsalSin/Stock-Market-Indices-Dataset (Yahoo daily OHLC):
  Nikkei 225 (2005–2019), Hang Seng (2005–2019), Dow Jones Industrial
  Average (2000–2019), NIFTY 50 (2000–2019; not tradable at FTMO, included
  as one more market).
- System: MR-ENS-VT exactly as frozen in Amendment 3. No parameter, filter
  or sizing change. Mode A, pre-registered costs, plus stressed costs.
- First 400 days of each series are warm-up only.
- Every market is reported, whatever the result. +1 trial (total 89).

## Reading

- The edge is considered to generalise if it is positive after costs on the
  majority of these markets.
- Correlations of daily strategy returns with the US100 version are
  reported to judge diversification.
