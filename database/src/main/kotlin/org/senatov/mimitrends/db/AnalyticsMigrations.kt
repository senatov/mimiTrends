package org.senatov.mimitrends.db

internal object AnalyticsMigrations {
    val values = listOf(
        1 to listOf(
            """CREATE TABLE IF NOT EXISTS instrument_metadata(symbol TEXT PRIMARY KEY, name TEXT NOT NULL, exchange TEXT NOT NULL, currency TEXT NOT NULL, timezone TEXT NOT NULL, isin TEXT, wkn TEXT, aliases TEXT, tradable INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS corporate_actions(symbol TEXT NOT NULL, action_type TEXT NOT NULL, effective_epoch INTEGER NOT NULL, ratio REAL, amount REAL, currency TEXT, source TEXT NOT NULL, PRIMARY KEY(symbol, action_type, effective_epoch))""",
            """CREATE TABLE IF NOT EXISTS trading_sessions(symbol TEXT NOT NULL, session_date TEXT NOT NULL, open_epoch INTEGER NOT NULL, close_epoch INTEGER NOT NULL, bar_count INTEGER NOT NULL, volume REAL NOT NULL, turnover REAL NOT NULL, source TEXT NOT NULL, PRIMARY KEY(symbol, session_date))""",
            """CREATE TABLE IF NOT EXISTS market_calendar_rules(market TEXT PRIMARY KEY, timezone TEXT NOT NULL, weekdays TEXT NOT NULL, open_local TEXT NOT NULL, close_local TEXT NOT NULL, updated_at INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS fx_rates(base_currency TEXT NOT NULL, quote_currency TEXT NOT NULL, rate_epoch INTEGER NOT NULL, rate REAL NOT NULL, source TEXT NOT NULL, PRIMARY KEY(base_currency, quote_currency, rate_epoch))""",
            """CREATE TABLE IF NOT EXISTS data_quality(id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL, source TEXT NOT NULL, observed_at INTEGER NOT NULL, latest_bar_epoch INTEGER, bar_count INTEGER NOT NULL, status TEXT NOT NULL, note TEXT)""",
            """CREATE INDEX IF NOT EXISTS idx_quality_symbol_time ON data_quality(symbol, observed_at DESC)""",
            """CREATE TABLE IF NOT EXISTS aggregate_bars(symbol TEXT NOT NULL, resolution_minutes INTEGER NOT NULL, bucket_epoch INTEGER NOT NULL, open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL, PRIMARY KEY(symbol, resolution_minutes, bucket_epoch))""",
            """CREATE INDEX IF NOT EXISTS idx_aggregate_symbol_time ON aggregate_bars(symbol, resolution_minutes, bucket_epoch)""",
            """CREATE TABLE IF NOT EXISTS baseline_stats(symbol TEXT NOT NULL, minute_of_session INTEGER NOT NULL, sample_count INTEGER NOT NULL, median_return REAL NOT NULL, mad_return REAL NOT NULL, median_log_volume REAL NOT NULL, mad_log_volume REAL NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(symbol, minute_of_session))""",
            """CREATE TABLE IF NOT EXISTS scan_runs(id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, completed_at INTEGER, region TEXT NOT NULL, requested_symbols INTEGER NOT NULL, evaluated_symbols INTEGER NOT NULL DEFAULT 0, accepted_symbols INTEGER NOT NULL DEFAULT 0, published_symbols INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0, interval_seconds INTEGER NOT NULL, status TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS scan_candidates(run_id INTEGER NOT NULL REFERENCES scan_runs(id) ON DELETE CASCADE, symbol TEXT NOT NULL, evaluated_at INTEGER NOT NULL, accepted INTEGER NOT NULL, published INTEGER NOT NULL, rejection_reason TEXT, signal TEXT, score REAL, change_10m REAL, jump_z REAL, range_z REAL, volume_z REAL, rvol REAL, price REAL, turnover REAL, source TEXT NOT NULL, PRIMARY KEY(run_id, symbol))""",
            """CREATE INDEX IF NOT EXISTS idx_candidates_symbol_time ON scan_candidates(symbol, evaluated_at DESC)"""
        ),
        2 to listOf(
            """CREATE TABLE IF NOT EXISTS signal_outcomes(run_id INTEGER NOT NULL, symbol TEXT NOT NULL, horizon_minutes INTEGER NOT NULL, entry_price REAL NOT NULL, observed_price REAL NOT NULL, return_percent REAL NOT NULL, observed_at INTEGER NOT NULL, PRIMARY KEY(run_id, symbol, horizon_minutes), FOREIGN KEY(run_id, symbol) REFERENCES scan_candidates(run_id, symbol) ON DELETE CASCADE)""",
            """CREATE INDEX IF NOT EXISTS idx_outcomes_symbol_time ON signal_outcomes(symbol, observed_at DESC)""",
            """INSERT OR IGNORE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('US', 'America/New_York', '1,2,3,4,5', '09:30', '16:00', CAST(strftime('%s','now') AS INTEGER))""",
            """INSERT OR IGNORE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('EUROPE', 'Europe/Berlin', '1,2,3,4,5', '09:00', '17:30', CAST(strftime('%s','now') AS INTEGER))"""
        ),
        3 to listOf(
            "ALTER TABLE scan_candidates ADD COLUMN signal_epoch INTEGER",
            "ALTER TABLE scan_candidates ADD COLUMN entry_price REAL",
            "ALTER TABLE signal_outcomes ADD COLUMN elapsed_minutes REAL",
            "UPDATE scan_candidates SET signal_epoch=evaluated_at WHERE signal_epoch IS NULL",
            "UPDATE scan_candidates SET entry_price=price WHERE entry_price IS NULL",
            "UPDATE signal_outcomes SET elapsed_minutes=horizon_minutes WHERE elapsed_minutes IS NULL",
            "DELETE FROM market_calendar_rules WHERE market='EUROPE'",
            "INSERT OR REPLACE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('US', 'America/New_York', '1,2,3,4,5', '09:30', '16:00', CAST(strftime('%s','now') AS INTEGER))",
            "INSERT OR REPLACE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('XETRA', 'Europe/Berlin', '1,2,3,4,5', '09:00', '17:30', CAST(strftime('%s','now') AS INTEGER))",
            "INSERT OR REPLACE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('EURONEXT', 'Europe/Berlin', '1,2,3,4,5', '09:00', '17:30', CAST(strftime('%s','now') AS INTEGER))",
            "INSERT OR REPLACE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('HELSINKI', 'Europe/Helsinki', '1,2,3,4,5', '10:00', '18:30', CAST(strftime('%s','now') AS INTEGER))"
        ),
        4 to listOf(
            """CREATE TABLE IF NOT EXISTS broker_transactions(
                id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, reference TEXT, fingerprint TEXT NOT NULL UNIQUE,
                occurred_at INTEGER NOT NULL, status TEXT NOT NULL, description TEXT NOT NULL, asset_type TEXT NOT NULL,
                transaction_type TEXT NOT NULL, isin TEXT, shares REAL NOT NULL, price REAL NOT NULL, amount REAL NOT NULL,
                fee REAL NOT NULL, tax REAL NOT NULL, currency TEXT NOT NULL, imported_at INTEGER NOT NULL,
                linked_run_id INTEGER, linked_symbol TEXT,
                FOREIGN KEY(linked_run_id, linked_symbol) REFERENCES scan_candidates(run_id, symbol) ON DELETE SET NULL)""",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_broker_source_reference ON broker_transactions(source, reference) WHERE reference IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_broker_isin_time ON broker_transactions(isin, occurred_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_broker_signal ON broker_transactions(linked_run_id, linked_symbol)"
        ),
        5 to listOf(
            "ALTER TABLE signal_outcomes ADD COLUMN maximum_return_percent REAL",
            "ALTER TABLE signal_outcomes ADD COLUMN minimum_return_percent REAL",
            """CREATE TABLE signal_excursions(run_id INTEGER NOT NULL, symbol TEXT NOT NULL,
                maximum_return_percent REAL NOT NULL, minimum_return_percent REAL NOT NULL, last_observed_at INTEGER NOT NULL,
                PRIMARY KEY(run_id, symbol), FOREIGN KEY(run_id, symbol) REFERENCES scan_candidates(run_id, symbol) ON DELETE CASCADE)"""
        ),
        6 to listOf(
            "ALTER TABLE scan_candidates ADD COLUMN data_epoch INTEGER",
            "UPDATE scan_candidates SET data_epoch=signal_epoch WHERE data_epoch IS NULL"
        ),
        7 to listOf(
            """CREATE TABLE research_samples(
                id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER NOT NULL, symbol TEXT NOT NULL,
                observed_epoch INTEGER NOT NULL, entry_price REAL NOT NULL, family TEXT NOT NULL,
                direction INTEGER NOT NULL, accepted INTEGER NOT NULL, published INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL, score REAL, jump_z REAL, range_z REAL, volume_z REAL, rvol REAL,
                return_1m REAL, return_3m REAL, return_5m REAL, return_10m REAL, return_30m REAL, return_60m REAL,
                range_10m REAL, volatility_30m REAL, vwap_distance REAL, session_high_distance REAL,
                session_low_distance REAL, volume_ratio_10m REAL, trend_efficiency_10m REAL,
                UNIQUE(symbol, observed_epoch, family, direction))""",
            "CREATE INDEX idx_research_samples_symbol_time ON research_samples(symbol, observed_epoch)",
            "CREATE INDEX idx_research_samples_family_time ON research_samples(family, direction, observed_epoch)",
            """CREATE TABLE research_outcomes(
                sample_id INTEGER NOT NULL REFERENCES research_samples(id) ON DELETE CASCADE,
                horizon_minutes INTEGER NOT NULL, observed_price REAL NOT NULL, return_percent REAL NOT NULL,
                elapsed_minutes REAL NOT NULL, maximum_return_percent REAL, minimum_return_percent REAL,
                observed_at INTEGER NOT NULL, PRIMARY KEY(sample_id, horizon_minutes))""",
            """CREATE TABLE research_excursions(
                sample_id INTEGER PRIMARY KEY REFERENCES research_samples(id) ON DELETE CASCADE,
                maximum_return_percent REAL NOT NULL, minimum_return_percent REAL NOT NULL,
                last_observed_at INTEGER NOT NULL)"""
        ),
        8 to listOf(
            """CREATE TABLE predictive_models(
                id INTEGER PRIMARY KEY AUTOINCREMENT, horizon_minutes INTEGER NOT NULL,
                feature_version INTEGER NOT NULL, trained_at INTEGER NOT NULL, training_cutoff INTEGER NOT NULL,
                training_samples INTEGER NOT NULL, validation_samples INTEGER NOT NULL,
                validation_days INTEGER NOT NULL, model_brier REAL NOT NULL, baseline_brier REAL NOT NULL,
                average_net_return REAL NOT NULL, top_quartile_net_return REAL NOT NULL,
                means TEXT NOT NULL, scales TEXT NOT NULL, weights TEXT NOT NULL,
                status TEXT NOT NULL, rejection_reason TEXT)""",
            "CREATE INDEX idx_predictive_models_horizon_time ON predictive_models(horizon_minutes, trained_at DESC)",
            "CREATE UNIQUE INDEX idx_predictive_models_active ON predictive_models(horizon_minutes) WHERE status='ACTIVE'"
        )
    )
}
