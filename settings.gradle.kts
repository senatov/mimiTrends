plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "MiMiTrends"
include("app", "core", "database", "finnhub-ws", "market-data", "scanner", "charts")
