# MiMiTrends

<img src="./Doc/AppIcon-1024.png" alt="MiMiTrends application icon" width="128">

### A local-first market anomaly scanner for macOS

MiMiTrends is a Kotlin/JVM + JavaFX desktop application styled after MiMiComparator. It detects sudden directional one-minute price impulses in a liquid US and European universe and stores historical Yahoo and optional live Finnhub OHLCV data in SQLite.

## Main window

<img src="./Doc/MainWindow.png" alt="MiMiTrends main window with anomaly scanner and market chart" width="900">

## What it does

- scans a configurable US, European, or combined universe;
- examines only the latest minute candle and the immediately preceding configurable freshness horizon;
- detects `Impulse ↑` and `Impulse ↓` using robust return/range Z-scores, candle shape, and volume confirmation;
- never promotes a symbol for volume alone when its price is quiet;
- requires at least two cached sessions and a configurable absolute move floor (0.20% by default);
- fills a sparse strict-impulse list to 12 rows by default with relaxed fresh impulses and statistically persistent rising trends;
- excludes closed exchanges from the fresh ranking instead of recycling their final cached candle;
- keeps the visible table unchanged while scanning and publishes the completed ranking atomically;
- scans every three minutes by default and shows a countdown or animated hourglass;
- uses SQLite first, Yahoo Finance for history/fallback, and optional Finnhub WebSocket trades for live US candles;
- downloads five days on first use, then requests only the missing tail and upserts overlapping minutes;
- displays candlesticks, volume, a turquoise close line, zoom/pan, tooltips, and a mouse crosshair;
- shows cached company favicons and marketing names; ticker and exchange remain in the delayed help popup;
- copies the selected company name with `⌘C`/`Ctrl+C`; the row menu can copy either name or ticker;
- shows prices in EUR by default, with USD selectable in Settings;
- remembers window geometry, divider position, selected instrument, columns, colors, and fonts.

The scanner is informational and does not place orders.

## Data providers

Yahoo Finance is the default OHLCV/profile source and needs no API key. Its public chart endpoint is not a contracted market-data API, so availability and fields can change; this project is intended for personal/demo use. Yahoo documents US Nasdaq quotes as real-time, but common European feeds are delayed: Xetra, Paris, and Amsterdam by 15 minutes and Milan by 20 minutes. The scanner labels every result as `YAHOO RT`, `DELAYED 15m`, `DELAYED 20m`, `LIVE`, or `CACHE` instead of presenting delayed data as current.

Finnhub is optional. When configured, one WebSocket connection subscribes to the selected US symbols, aggregates trade ticks into updatable minute OHLCV bars, and writes them to SQLite. It also remains a company-profile/logo fallback. A WebSocket failure automatically leaves Yahoo/SQLite active; missing credentials never block startup. European real-time availability depends on the user's Finnhub plan and exchange licensing, so Yahoo European results remain explicitly delayed in the default configuration.

Users can enter or replace their own Finnhub API key under **Settings → Finnhub live feed**. Leaving the field blank preserves the current local key. Credentials can also be supplied through `FINNHUB_API_KEY` or an ignored project `.env` file.

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

The application version starts at `0.0.0.1`. Gradle generates `build-info.properties` for every build with a unique build ID, build type, timestamp, and host; the same metadata is embedded in the JAR manifest and displayed in the title bar and About dialog. Override it when needed with `-PappVersion=… -PbuildNumber=… -PbuildType=…`.

## Native distributions

Gradle builds self-contained packages with a private Java runtime, so users do not need to install a JDK. Native installers must be built on their target OS: DMG on macOS, EXE on Windows, and `tar.gz`/DEB on Linux.

The simplest macOS build uses the project script. It automatically finds the first available `Developer ID Application` certificate:

```zsh
./Scripts/build-macos-dmg.zsh
./Scripts/build-macos-dmg.zsh --notarize
```

```zsh
# Local unsigned macOS application
./gradlew :app:packageMacApp

# Developer ID signed macOS DMG
MAC_SIGNING_KEY_USER_NAME="Iakov Senatov (G2V9T9AD95)" \
  ./gradlew :app:packageMacDmg

# Signed + Apple-notarized + stapled DMG
APPLE_NOTARY_PROFILE="MiMiTrends-notary" \
MAC_SIGNING_KEY_USER_NAME="Iakov Senatov (G2V9T9AD95)" \
  ./gradlew :app:packageNotarizedMacDmg
```

On Windows run `gradlew.bat :app:packageWindowsExe`; on Linux run `./gradlew :app:packageLinuxPortable :app:packageLinuxDeb`. Outputs are written below `app/build/distributions/native/`. See [Native packaging](Doc/NativePackaging.md) for prerequisites, Apple credential setup, output names, and verification.

The displayed application version remains `0.0.0.1`. Native package metadata uses `1.0.1`, because `jpackage` requires one to three numeric components and macOS rejects a zero major package version.

## Architecture

```text
core/         Domain models and shared log markers
database/     SQLite WAL cache plus versioned analytical repository and migrations
market-data/  Yahoo Finance HTTP client and response mapping
scanner/      Configurable anomaly scoring and settings persistence
charts/       Reusable JFreeChart-FX candlestick/volume component
finnhub-ws/   Optional Finnhub profiles, WebSocket client, and minute aggregator
app/          JavaFX lifecycle, dialogs, state, and orchestration
```

The UI composes these modules; database, provider, scanner, and chart code do not depend on JavaFX application classes.

The database module separates the operational minute cache from the analytical repository. It stores
instrument/exchange metadata, corporate actions, observed sessions, ECB FX rates, feed quality,
5/15/60-minute aggregates, robust time-of-day baselines, every accepted/rejected scan candidate and
realized 5/10/30-minute outcomes for published signals. See [Analytics storage](Doc/AnalyticsStorage.md).

Market history is stored in:

```text
~/.mimi/trends/mimitrends.db
```

SQLite runs in WAL mode with `synchronous=NORMAL`. Minute rows use an upsert key, so a repeated or incomplete provider bar is updated rather than duplicated. Settings and UI state are stored beside the database.

## Fresh impulse score

For the latest eligible one-minute candles, the scanner calculates:

- a robust return jump Z-score using median absolute deviation (MAD);
- a robust high–low range Z-score;
- log-volume Z-score and relative volume against a local median;
- candle body/range ratio and closing location, rejecting weak wicks and random ticks;
- same-direction continuation and exponential freshness decay.

A signal requires an exceptional price return or range plus confirmation from candle shape, relative/log volume, or immediate continuation. Volume by itself cannot qualify a symbol. The default maximum age is two minutes and the score decays exponentially, so a completed move disappears unless fresh bars continue it. Trend fallback uses up to three hours only to establish context; publication additionally requires at least +0.60% over the latest ten minutes and +0.15% over the latest five minutes. The first table columns are Company, color-coded Trend, and sortable signed `Δ 10m, %`; this percentage always describes the same current ten-minute interval for impulses and trends. Baselines prefer prior sessions at comparable times of day and fall back to recent cached bars when history is still short. Thresholds, freshness, liquidity guards, universe, region, and scan interval are editable under Settings.

At least two historical sessions are required before a symbol can generate a signal. A cold or incomplete cache is bootstrapped from Yahoo before evaluation. Only completed, minute-aligned bars with positive volume participate in statistics; malformed zero-volume quote snapshots are removed during database migration. The 0.20% default absolute-move floor prevents a mathematically large Z-score on economically insignificant movements such as 0.02–0.10% in an unusually quiet stock. Such a symbol can still qualify as `Trend ↑` when its multi-hour path shows meaningful persistent growth.

If fewer than the configured minimum number of strict impulses are available, the scanner adds two lower-priority fallback classes. A relaxed impulse reduces the strict thresholds moderately while retaining recency and the absolute-move guard. `Trend ↑` examines up to 180 minutes of the current session and requires at least 0.45% net growth by default, a positive least-squares slope, R² of at least 0.18, and an efficiency ratio of at least 0.08 (net progress divided by total travelled price path). This permits pullbacks without treating a noisy sideways chart as sustained growth. Strict results are never removed to make room for fallbacks.

The expanded default watchlist contains 100 liquid US and European listings. Yahoo suffixes such as `.DE`, `.PA`, `.AS`, and `.MI` identify European exchanges. The universe, region, interval, result count, liquidity guards, and baseline length are editable under Settings.

Signal colors follow trading conventions: saturated green/red indicates a strong current upward/downward move, muted green/red marks a weak trend, amber marks a relaxed or statistically questionable result, and gray marks an aging signal. Hovering the Signal cell explains its classification.

## Chart

The lower pane uses JFreeChart through JFreeChart-FX. It renders locally stored OHLC candles and volume with a shared time axis. A close-price line makes sparse movement readable; moving the mouse over the graph shows synchronized crosshair lines and value tooltips. Selecting a table row loads that instrument from SQLite immediately.

## Logging and errors

Application, UI, API, state, I/O, and database operations use SLF4J + Log4j2 and appear in the run console and rolling file:

```text
/tmp/MiMiTrends.log
```

The default `INFO` level records lifecycle events, scan summaries, warnings, and errors without per-tick, per-row, or per-query noise. For temporary diagnostics, start with `-Dmimitrends.logLevel=DEBUG`. The active log rolls at 10 MB and retains four compressed archives. The status line reports the symbol currently being read or analyzed. A red details button opens the complete error report. Credentials are never logged.

## Icon and licenses

The generated 1024 px icon is in `Doc/`; runtime sizes are under `app/src/main/resources/icons/`. JFreeChart and JFreeChart-FX are LGPL libraries; consult their upstream projects when distributing the application.
