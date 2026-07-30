# MiMiTrends

<img src="./Doc/AppIcon-1024.png" alt="MiMiTrends application icon" width="128">

### A compact Finnhub market-trends demo for macOS

[![Kotlin 2.4](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)](#build-and-run)
[![JavaFX 26](https://img.shields.io/badge/JavaFX-26-0A66C2)](#about)
[![JDK 25](https://img.shields.io/badge/JDK-25-007396?logo=openjdk&logoColor=white)](#build-and-run)
[![Finnhub](https://img.shields.io/badge/Data-Finnhub-1f9d76)](https://finnhub.io/)

## About

MiMiTrends is a small Kotlin/JVM + JavaFX application that loads a live stock quote and daily closing prices from Finnhub. Its compact
Cupertino-style toolbar, SF Pro typography, light surfaces, borders, buttons, and status bar follow the visual language of
MiMiComparator without sharing its business logic.

The demo includes:

- ticker input and 1/3/6/12-month ranges;
- current price, daily change, open, high, low, and previous close;
- a responsive JavaFX line chart;
- asynchronous network requests, loading state, and readable API errors;
- quote-derived fallback points if candle history is unavailable on the active Finnhub plan;
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
app/src/main/
├── kotlin/org/senatov/mimitrends/
│   ├── App.kt                 # JavaFX stage, theme, fonts, and icon
│   ├── MainController.kt      # interface and chart workflow
│   ├── ApiKeyResolver.kt      # environment/.env lookup
│   ├── api/FinnhubClient.kt   # asynchronous REST client and JSON parsing
│   └── model/MarketModels.kt  # quote and candle models
└── resources/
    ├── fonts/                 # UI fonts matching MiMiComparator
    ├── icons/                 # generated app icon sizes
    └── org/senatov/mimitrends/MiMiTrends.css
```

## Finnhub usage

The application calls:

- `GET /api/v1/quote`
- `GET /api/v1/stock/candle`

Availability and history depth can depend on the Finnhub subscription. When candle history is unavailable but a quote loads, MiMiTrends
draws a minimal previous-close-to-current-price fallback so that the demo remains usable and labels this state in the status bar.

## Security notes

- Only `FINNHUB_API_KEY` is used by the current market-data client.
- The key is sent only to `https://finnhub.io/api/v1`.
- An optional webhook secret can be read from local configuration but is not sent or used yet.
- No account password, email address, 2FA setting, or passkey information is read or stored.
- This demo is read-only and does not place orders.

## Icon

The application icon was generated specifically for MiMiTrends with OpenAI image generation. The project keeps the 1024 px source in
`Doc/` and runtime sizes in `app/src/main/resources/icons/`.