plugins {
    kotlin("jvm") version "2.4.0"
}

repositories { mavenCentral() }
kotlin { jvmToolchain(25) }

val javafxVersion = "26"
val javafxPlatform = providers.systemProperty("os.name").zip(providers.systemProperty("os.arch")) { os, arch ->
    val normalizedArch = if (arch.lowercase() in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
    when {
        os.lowercase().contains("mac") -> "mac-$normalizedArch"
        os.lowercase().contains("win") -> "win-$normalizedArch"
        else -> "linux-$normalizedArch"
    }
}.get()

dependencies {
    implementation(project(":core"))
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
    implementation("org.jfree:org.jfree.chart.fx:2.0.2")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
