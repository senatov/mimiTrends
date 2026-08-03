# Analytics storage

MiMiTrends keeps operational market bars and derived analytics in the same SQLite WAL database at
`~/.mimi/trends/mimitrends.db`. `MarketRepository` owns minute OHLCV and company-profile caching;
`AnalyticsRepository` owns versioned analytical tables. Keeping the responsibilities separate avoids
ORM/session state in the market-data path while retaining atomic SQL, prepared statements, batches,
foreign keys, and independent tests.

## Versioned schema

`schema_migrations` records each transactional migration. The analytical schema contains:

| Table                   | Purpose                                                                                 |
| ----------------------- | --------------------------------------------------------------------------------------- |
| `instrument_metadata`   | Exchange, currency, timezone, ISIN/WKN placeholders, aliases and tradability.           |
| `corporate_actions`     | Yahoo splits and dividends, available for spike validation/normalization.               |
| `market_calendar_rules` | Exchange timezone and regular trading hours.                                            |
| `trading_sessions`      | Observed session boundaries, coverage, volume and turnover.                             |
| `fx_rates`              | Dated ECB reference rates.                                                              |
| `data_quality`          | Source, freshness, bar count, realtime/delayed/cache status and diagnostics.            |
| `aggregate_bars`        | Locally generated 5, 15 and 60 minute OHLCV bars.                                       |
| `baseline_stats`        | Median/MAD return and log-volume by instrument and minute of trading day.               |
| `scan_runs`             | One durable record for every complete scanner pass.                                     |
| `scan_candidates`       | Accepted and rejected symbols, rejection reason, metrics, source and publication state. |
| `signal_outcomes`       | Realized 5/10/30 minute return plus observed favorable/adverse excursions.              |
| `signal_excursions`     | In-progress high/low return tracking for active published signals.                      |
| `broker_transactions`   | Deduplicated Scalable operations with optional links to saved scanner signals.           |

## Data flow

1. Yahoo or Finnhub bars are UPSERTed into `minute_bars`.
2. A completed scan refreshes observed sessions, aggregate bars, quality and robust time-of-day baselines.
3. Every evaluated instrument is written to `scan_candidates`; empty results use `NO_CURRENT_SIGNAL`,
   while provider failures retain the concrete error.
4. Published rows are marked only after ranking completes, so the table can be evaluated without
   survivorship ambiguity.
5. Later bars backfill `signal_outcomes` at 5, 10 and 30 minutes. These records are the basis for
   empirical threshold tuning and future precision/continuation reports.

When every selected market is closed, the scanner does not clear the table or reinterpret old closing
bars as fresh signals. It restores the most recent published snapshot from `scan_candidates`; rows are
marked `SAVED SNAPSHOT`, their age is calculated from wall-clock time, and the status bar states that the
markets are closed.
On the first upgraded launch, when no published snapshot exists yet, MiMiTrends may build the table once
from the last locally cached close. This fallback performs no provider request and is explicitly labelled
`CLOSED CACHE` / `not live`; wall-clock age prevents cached closing bars from appearing current.
The table also displays a mouse-transparent closed-market overlay with the bundled sleeping-dog
illustration, a large closed-market heading, and a `not live` subtitle. It disappears as soon as a real
open-market scan begins.

## Retention and performance

- raw minute bars: 90 days;
- 5/15/60 minute aggregates: 730 days;
- scan runs, candidates and quality observations: 180 days;
- instrument metadata, corporate actions, sessions, FX rates, baselines and outcomes: retained.

SQLite uses WAL, foreign keys, a five-second busy timeout, memory temporary storage, a 20 MiB page
cache and a 256 MiB memory-map ceiling. Writes use prepared statements and batches. `PRAGMA optimize`
runs when the analytics repository closes. SQLite remains appropriate at this scale; Hibernate would
add lifecycle and mapping overhead without improving the append/upsert-heavy workload.

Minute history supplied by Yahoo is short, so coverage grows as the application runs. Reliable
time-of-day baselines normally need at least 20–30 complete sessions; 60 is preferable. Aggregate bars
preserve longer context without repeatedly consuming provider quota.
