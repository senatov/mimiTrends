# SQLite storage

MiMiTrends stores scanner observations and imported transactions in
`~/.mimi/trends/mimitrends.db`. `MarketRepository` writes minute candles, provider observations,
company profiles, and the watchlist. `AnalyticsRepository` writes scan runs and candidates,
reference data, and broker transactions. Both share the same SQLite connection state.

## Schema

The append-only `schema_migrations` table tracks transactional migrations. Current tables include:

| Table                                                            | Purpose                                                     |
|------------------------------------------------------------------|-------------------------------------------------------------|
| `minute_bars`                                                    | Recent OHLCV candles by symbol and minute.                  |
| `provider_minute_bars` and `provider_quotes`                     | Venue observations and the latest executable quote.         |
| `provider_instruments`                                           | Provider symbol, ISIN, venue, and currency mappings.        |
| `scan_runs` and `scan_candidates`                                | Scanner passes, rejected candidates, and published signals. |
| `curve_candidates`                                               | Recent chart breaks and review decisions.                   |
| `broker_transactions`                                            | Deduplicated imported executions and optional signal links. |
| `instrument_metadata`, `company_profiles`, and `company_domains` | Instrument identities and display data.                     |
| `user_watchlist`                                                 | Instruments explicitly followed by the user.                |
| `corporate_actions` and `fx_rates`                               | Price adjustment and currency reference data.               |

Migration 21 removes the former aggregate, baseline, research, prediction, and outcome tables.
Historical migrations remain in source so older databases can upgrade safely. The application no
longer opens DuckDB; it removes the old DuckDB file after a successful SQLite migration.

## Data flow and retention

The scanner saves candidate decisions and marks published rows when a scan completes. Foreign keys
link saved signals to scans. Imported trades remain in the database when old scan runs expire; the
linked run ID is cleared while the instrument symbol remains available.

Minute candles, provider observations, scan runs, and curve-review history expire after seven days.
Retention runs at startup, once a day while open, and during shutdown. Static mappings, FX rates,
corporate actions, the watchlist, and imported transactions are retained.

SQLite uses WAL, foreign keys, a busy timeout, prepared statements, and bounded in-memory caching.
Startup maintenance checks integrity and creates a daily backup when due. Shutdown maintenance
keeps the three newest daily backups and removes expired temporary files.