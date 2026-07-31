# MiMiTrends

<img src="./Doc/AppIcon-1024.png" alt="MiMiTrends application icon" width="128">

### A local-first market anomaly scanner for macOS

MiMiTrends is a Kotlin/JVM + JavaFX desktop application styled after MiMiComparator. It ranks a 100-instrument liquid US and European universe by unusual recent price or turnover activity and stores downloaded minute OHLCV history in SQLite.

## Main window

<img src="./Doc/MainWindow.png" alt="MiMiTrends main window with anomaly scanner and market chart" width="900">

## What it does

- scans a configurable US, European, or combined universe;
- compares the rolling 0–5, 5–10, and 10–15 minute windows with historical local baselines;
- ranks price and volume anomalies instead of requiring every instrument to pass fixed thresholds;
- keeps the visible table unchanged while scanning and publishes the completed ranking atomically;
- scans every three minutes by default and shows a countdown or animated hourglass;
- uses SQLite first and requests Yahoo Finance only when a market is open and local data is stale;
- downloads five days on first use, then requests only the missing tail and upserts overlapping minutes;
- displays candlesticks, volume, a turquoise close line, zoom/pan, tooltips, and a mouse crosshair;
- shows cached company favicons and marketing names; ticker and exchange remain in the delayed help popup;
- shows prices in EUR by default, with USD selectable in Settings;
- remembers window geometry, divider position, selected instrument, columns, colors, and fonts.

The scanner is informational and does not place orders.

## Data providers

Yahoo Finance is the default OHLCV/profile source and needs no API key. Its public chart endpoint is not a contracted market-data API, so availability and fields can change; this project is intended for personal/demo use. For redistribution or a commercial product, replace it with a licensed provider behind the isolated `market-data` module.

Finnhub is optional and is currently used only as a cached company-profile/logo fallback when `FINNHUB_API_KEY` is configured. Missing Finnhub credentials never block startup.

Credential lookup order is environment variable, ignored project `.env`, then:

```text
~/.mimi/trends/finnhub.properties
```

Never commit credentials. Revoke any key that has been shared publicly.

## Build and run

```zsh
./gradlew run
./gradlew test
./gradlew build
```

In IntelliJ IDEA, import the root as a Gradle project and use the shared `MiMiTrends [run]` configuration. The packaged entry point is `org.senatov.mimitrends.LauncherKt`. If Run/Debug is disabled, reload the Gradle project and remove an obsolete configuration pointing directly at `App`.

To create a macOS bundle with a JDK containing `jpackage`:

```zsh
./gradlew packageMacApp
```

## Architecture

```text
core/         Domain models and shared log markers
database/     SQLite WAL connection, schema, profiles, and minute-bar repository
market-data/  Yahoo Finance HTTP client and response mapping
scanner/      Configurable anomaly scoring and settings persistence
charts/       Reusable JFreeChart-FX candlestick/volume component
finnhub-ws/   Optional Finnhub profile and legacy realtime adapter
app/          JavaFX lifecycle, dialogs, state, and orchestration
```

The UI composes these modules; database, provider, scanner, and chart code do not depend on JavaFX application classes.

Market history is stored in:

```text
~/.mimi/trends/mimitrends.db
```

SQLite runs in WAL mode with `synchronous=NORMAL`. Minute rows use an upsert key, so a repeated or incomplete provider bar is updated rather than duplicated. Settings and UI state are stored beside the database.

## Anomaly score

For each of the three most recent five-minute windows, the scanner calculates:

- absolute price return divided by the median historical absolute return;
- window volume divided by the median historical five-minute volume;
- a recency-weighted score, favouring activity still present in the latest five minutes.

The winning window is displayed as `now–5m`, `5–10m ago`, or `10–15m ago`, together with `Price ↑`, `Price ↓`, or `Volume` as its source. Activity older than 15 minutes cannot keep an otherwise quiet instrument at the top. The table is sorted by score and limited to 50 rows by default. A cold database receives a neutral baseline until enough comparable periods exist, avoiding artificial extreme scores. Minimum source price and session turnover remain configurable quality/liquidity guards.

The expanded default watchlist contains 100 liquid US and European listings. Yahoo suffixes such as `.DE`, `.PA`, `.AS`, and `.MI` identify European exchanges. The universe, region, interval, result count, liquidity guards, and baseline length are editable under Settings.

## Chart

The lower pane uses JFreeChart through JFreeChart-FX. It renders locally stored OHLC candles and volume with a shared time axis. A close-price line makes sparse movement readable; moving the mouse over the graph shows synchronized crosshair lines and value tooltips. Selecting a table row loads that instrument from SQLite immediately.

## Logging and errors

Application, UI, API, state, I/O, and database operations use SLF4J + Log4j2 and appear in the run console and rolling file:

```text
/tmp/MiMiTrends.log
```

The active log rolls at 10 MB and retains four compressed archives. The status line reports the symbol currently being read or analyzed. A red details button opens the complete error report. Credentials are never logged.

## Icon and licenses

The generated 1024 px icon is in `Doc/`; runtime sizes are under `app/src/main/resources/icons/`. JFreeChart and JFreeChart-FX are LGPL libraries; consult their upstream projects when distributing the application.
