# MiMiTrends

<img src="./Doc/AppIcon-1024.png" alt="MiMiTrends application icon" width="128">

### A compact Finnhub market-trends demo for macOS

[![Kotlin 2.4](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)](#build-and-run)
[![JavaFX 26](https://img.shields.io/badge/JavaFX-26-0A66C2)](#about)
[![JDK 25](https://img.shields.io/badge/JDK-25-007396?logo=openjdk&logoColor=white)](#build-and-run)
[![Finnhub](https://img.shields.io/badge/Data-Finnhub-1f9d76)](https://finnhub.io/)

## About

MiMiTrends is a small Kotlin/JVM + JavaFX application that scans realtime Finnhub trades and builds local minute price/volume history. Its compact
Cupertino-style toolbar, SF Pro typography, light surfaces, borders, buttons, and status bar follow the visual language of
MiMiComparator without sharing its business logic.

The demo includes:

- locally retained chart-range state;
- a focused price chart without a redundant quote-characteristics panel;
- a responsive JavaFX price chart paired with locally collected trading volume;
- a WebSocket momentum scanner with configurable 1/5-minute, price, volume, and relative-volume filters;
- locally aggregated one-minute OHLCV bars and a volume chart for confirming price moves;
- EUR price display by default, switchable to USD in scanner Settings, using the cached daily ECB reference rate;
- a live request/read status line with readable API errors;
- SQLite-backed accumulation of real minute price and volume history;
- a generated MiMiTrends application icon and a macOS `jpackage` task.

## API key

The Finnhub API key is intentionally not stored in this repository. Webhook secrets, account email, 2FA status, and passkey settings
are not needed by this read-only demo.

Choose one of these local setup methods:

```zsh
# Option 1: environment variable for the current terminal
export FINNHUB_API_KEY="your_key_here"
./gradlew run
```

```zsh
# Option 2: ignored local file
cp .env.example .env
# edit .env and replace the placeholder
./gradlew run
```

The `.env` file is excluded by `.gitignore`. It may also contain `FINNHUB_WEBHOOK_SECRET` for future webhook handling; the current
read-only UI does not use that value. If a key has ever been committed or shared publicly, revoke it in the Finnhub dashboard and
create a replacement.

If no API key is available on the first launch, MiMiTrends opens a setup dialog with a Finnhub registration link and fields for the
API key and optional webhook secret. Values saved by the dialog are stored outside the repository:

```text
~/.mimi/trends/finnhub.properties
```

On POSIX-compatible systems, the application restricts this file to the current user (`0600`). Resolution priority is environment
variables, project-local `.env`, then the user settings file.

## Build and run

Requirements: macOS, Linux, or Windows with a JDK available to Gradle. The checked-in Foojay resolver can provision the JDK 25
compilation toolchain.

```zsh
./gradlew run
./gradlew test
./gradlew build
```

### IntelliJ IDEA

Import the project as a **Gradle project** and allow Gradle synchronization to finish. Select the shared
`MiMiTrends [run]` configuration in the toolbar for reliable Run and Debug support. Like MiMiComparator, the packaged entry point is
the separate `org.senatov.mimitrends.LauncherKt` class.

Alternatively, create a Gradle run configuration with:

```text
Task: :app:run
```

If an older `App` run configuration already exists, delete it and reload the Gradle project.

To create a macOS application bundle (requires a full JDK 26 with `jpackage`):

```zsh
./gradlew packageMacApp
```

Output:

```text
app/build/jpackage/output/MiMiTrends.app
```

## Project structure

```text
core/           Domain models, realtime trade model, and shared log markers
database/       SQLite connection policy, minute-bar schema and repository
finnhub-ws/      WebSocket lifecycle, subscriptions, and realtime trade decoding
scanner/         Minute-bar aggregation, configurable momentum rules, and RVOL calculation
charts/          Reusable JavaFX TrendChartView and market-series rendering
app/             JavaFX lifecycle, credentials, dialogs, UI orchestration, and resources
```

The dependency direction is one-way: infrastructure and presentation modules depend on `core`; `app` composes them. Database,
WebSocket, database, scanner, and chart code do not depend on the UI, so each can be tested or replaced independently.

Run every module test from the root:

```zsh
./gradlew test
```

Run only a module test suite:

```zsh
./gradlew :database:test
./gradlew :finnhub-ws:test
```

## Finnhub usage

The application uses `wss://ws.finnhub.io` for realtime trades in the scanner watchlist. It does not request REST quotes or Premium candles.

### Momentum scanner

The upper table contains only symbols that currently pass every rule configured under **Settings**. The default rules are RVOL > 3,
1-minute change > 1%, 5-minute change > 1.2%, price > $8, and accumulated session volume > 500,000 shares. Click a matching
row to open its chart. A watchlist may contain more than 50 symbols: it is divided into configurable batches of at most 50 active
WebSocket subscriptions. After the configured interval the scanner unsubscribes the current batch and activates the next one.

Minute scanning is possible without the Premium candle endpoint: MiMiTrends receives individual Finnhub WebSocket trades, aggregates
them into real one-minute OHLCV bars, and stores those bars in SQLite. Changes are calculated from the minute closes actually observed
while a symbol's batch was active. Consequently a multi-batch scan is best-effort rather than a guaranteed once-per-minute full-market
scan. RVOL compares the
current cumulative session volume with the average cumulative volume of prior sessions at the same time of day. It is shown as
`N/A` until at least three prior local sessions exist, so a new installation cannot immediately satisfy the RVOL rule. Scanner settings
are kept in `~/.mimi/trends/scanner.properties`; market bars remain in `~/.mimi/trends/mimitrends.db`.

Price presentation defaults to EUR and can be changed to USD under **Settings**. US-market prices are converted using the official
daily ECB EUR/USD reference rate. The rate is fetched asynchronously and cached in `~/.mimi/trends/exchange-rate.properties`; if the
ECB is temporarily unavailable, the latest cached value remains in use. Instruments with common euro-exchange suffixes such as `.DE`,
`.F`, `.PA`, and `.AS` are treated as EUR-native and are not converted when EUR display is selected. SQLite always retains the raw
Finnhub values.

## Restored session

On a normal application close, MiMiTrends remembers the window's size, position, maximized state, selected instrument, and chart range.
It restores them on the next launch. If a remembered position belonged to a monitor that is no longer connected, the window is moved
back to the primary display instead of opening off-screen. UI state is stored in `~/.mimi/trends/ui-state.properties`; scanner settings,
exchange-rate cache, and accumulated SQLite market history remain in their respective files in the same directory.

Availability and local history depth depend on how long symbols have been actively scanned. MiMiTrends never fabricates or interpolates
chart points. The status line directly below the title reports current Finnhub subscription requests, realtime trades read from the
WebSocket, SQLite chart reads, and ECB exchange-rate requests.

## Logging

MiMiTrends uses the same SLF4J + Log4j2 approach as MiMiComparator. Application, UI, API, state, I/O, and database operations are
tagged with markers and written at DEBUG level to both the run console and a rolling log file:

```text
/tmp/MiMiTrends.log
```

The active file rolls at 10 MB and keeps four compressed archives at `/tmp/MiMiTrends.N.log.gz`. API keys and webhook secrets are
never written to the log. A complete asynchronous exception stack trace is also available through the red details button in the
application status bar. A complete Finnhub operation is limited to 15 seconds so a stalled service request cannot leave the UI in a
permanent loading state.

## Security notes

- Only `FINNHUB_API_KEY` is used by the current market-data client.
- The key is sent only to Finnhub's official API hosts: `https://finnhub.io/api/v1` and, during temporary endpoint failure,
  `https://api.finnhub.io/api/v1`.
- An optional webhook secret can be read from local configuration but is not sent or used yet.
- No account password, email address, 2FA setting, or passkey information is read or stored.
- This demo is read-only and does not place orders.

## Icon

The application icon was generated specifically for MiMiTrends with OpenAI image generation. The project keeps the 1024 px source in
`Doc/` and runtime sizes in `app/src/main/resources/icons/`.
