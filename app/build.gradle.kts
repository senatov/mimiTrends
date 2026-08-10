import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.WriteProperties
import java.net.InetAddress
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    application
    kotlin("jvm") version "2.4.0"
}

val appVersion = providers.gradleProperty("appVersion").get()
version = appVersion
val buildTime = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss z"))
val buildNumber = providers.environmentVariable("BUILD_NUMBER")
    .orElse(providers.gradleProperty("buildNumber"))
    .getOrElse(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
val buildType = providers.gradleProperty("buildType")
    .orElse(providers.environmentVariable("BUILD_TYPE"))
    .getOrElse("DEV BUILD")
val buildHost = providers.environmentVariable("HOSTNAME")
    .getOrElse(runCatching { InetAddress.getLocalHost().hostName }.getOrElse { "unknown" })
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/build-info")

val generateBuildInfo = tasks.register<WriteProperties>("generateBuildInfo") {
    group = "build"
    description = "Generates build metadata embedded in the application resources."
    destinationFile = generatedBuildInfoDir.map { it.file("build-info.properties") }
    property("version", appVersion)
    property("build", buildNumber)
    property("type", buildType)
    property("time", buildTime)
    property("host", buildHost)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateBuildInfo)
    from(generatedBuildInfoDir)
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

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
    implementation(project(":database"))
    implementation(project(":finnhub-ws"))
    implementation(project(":market-data"))
    implementation(project(":scanner"))
    implementation(project(":charts"))
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
    implementation("io.github.mkpaz:atlantafx-base:2.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation(platform("org.apache.logging.log4j:log4j-bom:2.26.1"))
    implementation("org.apache.logging.log4j:log4j-api")
    implementation("org.apache.logging.log4j:log4j-core")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

application {
    // A separate launcher prevents the JDK launcher from treating App as a modular
    // JavaFX entry point while the JavaFX libraries are supplied on the classpath.
    mainClass = "org.senatov.mimitrends.LauncherKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=javafx.graphics,ALL-UNNAMED")
}

// Keep JavaFX itself on the module path even though the Kotlin application is
// intentionally non-modular. This is the supported JavaFX launch layout and
// avoids PlatformImpl's "classes were loaded from unnamed module" warning.
tasks.named<JavaExec>("run") {
    doFirst {
        val javafxJars = classpath.filter { file ->
            file.extension == "jar" && file.name.startsWith("javafx-")
        }
        classpath = classpath.filter { file -> file !in javafxJars }
        jvmArgs(
            "--module-path", javafxJars.asPath,
            "--add-modules", "javafx.controls,javafx.graphics"
        )
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to "MiMiTrends",
            "Implementation-Version" to appVersion,
            "Build-Number" to buildNumber,
            "Build-Type" to buildType,
            "Build-Time" to buildTime,
            "Build-Host" to buildHost,
            "Main-Class" to application.mainClass.get()
        )
    }
}

val jpackageInputDir = layout.buildDirectory.dir("jpackage/input")
val nativeOutputDir = layout.buildDirectory.dir("distributions/native")
val macOutputDir = nativeOutputDir.map { it.dir("macos") }
val windowsOutputDir = nativeOutputDir.map { it.dir("windows") }
val linuxOutputDir = nativeOutputDir.map { it.dir("linux") }
val nativePackageVersion = appVersion.split('.').let { parts ->
    val major = parts.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val minor = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val patch = parts.drop(2).joinToString("").toIntOrNull()?.coerceAtLeast(0) ?: 0
    "$major.$minor.$patch"
}
val hostOs = System.getProperty("os.name").lowercase()
val isMac = hostOs.contains("mac")
val isWindows = hostOs.contains("win")
val isLinux = hostOs.contains("linux")
val jpackageExecutable = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(26)
}.get().metadata.installationPath.asFile.resolve("bin/jpackage${if (isWindows) ".exe" else ""}")
val macDmgFile = macOutputDir.map { it.file("MiMiTrends-$nativePackageVersion.dmg") }
val linuxAppImage = linuxOutputDir.map { it.dir("MiMiTrends") }

val prepareJpackageInput = tasks.register<Sync>("prepareJpackageInput") {
    group = "distribution"
    description = "Collects the application JAR and runtime dependencies for jpackage."
    dependsOn(tasks.named("jar"))
    into(jpackageInputDir)
    from(tasks.named<Jar>("jar"))
    from(configurations.runtimeClasspath)
}

val macOut = macOutputDir.get().asFile
val windowsOut = windowsOutputDir.get().asFile
val linuxOut = linuxOutputDir.get().asFile
val macIcon = file("src/main/resources/icons/MiMiTrends.icns")
val windowsIcon = file("src/main/resources/icons/MiMiTrends.ico")
val linuxIcon = file("src/main/resources/icons/icon_512x512.png")
val cleanMacApp = tasks.register<Delete>("cleanMacAppPackage") {
    group = "distribution"
    description = "Removes the previously packaged macOS application image."
    delete(macOut.resolve("MiMiTrends.app"))
}
val cleanMacDmg = tasks.register<Delete>("cleanMacDmgPackage") {
    group = "distribution"
    description = "Removes previously packaged macOS disk images."
    delete(fileTree(macOut) { include("*.dmg") })
}
val cleanWindowsExe = tasks.register<Delete>("cleanWindowsExePackage") {
    group = "distribution"
    description = "Removes previously packaged Windows installers."
    delete(fileTree(windowsOut) { include("*.exe") })
}
val cleanLinuxApp = tasks.register<Delete>("cleanLinuxAppPackage") {
    group = "distribution"
    description = "Removes the previously packaged Linux application image."
    delete(linuxAppImage)
}
val cleanLinuxDeb = tasks.register<Delete>("cleanLinuxDebPackage") {
    group = "distribution"
    description = "Removes previously packaged Debian archives."
    delete(fileTree(linuxOut) { include("*.deb") })
}
val validateDmgVersionBump = tasks.register("validateDmgVersionBump") {
    group = "verification"
    description = "Requires the version-bumping DMG wrapper to be used."
    inputs.property("dmgVersionBumped", providers.gradleProperty("dmgVersionBumped").map { it == "true" }.orElse(false))
    doLast {
        check(inputs.properties["dmgVersionBumped"] == true) {
            "Build DMGs through Scripts/build-macos-dmg.zsh so the application version is incremented first"
        }
    }
}
val signMacNativeJars = tasks.register<Exec>("signMacNativeJars") {
    group = "distribution"
    description = "Signs Mach-O libraries embedded in runtime JARs before jpackage runs."
    dependsOn(prepareJpackageInput, validateDmgVersionBump)
    onlyIf("signMacNativeJars requires macOS") { System.getProperty("os.name").lowercase().contains("mac") }
    val signingIdentity = providers.environmentVariable("MAC_SIGNING_KEY_USER_NAME").orNull
    doFirst {
        check(!signingIdentity.isNullOrBlank()) {
            "Set MAC_SIGNING_KEY_USER_NAME to a Developer ID Application identity from: security find-identity -v -p codesigning"
        }
    }
    commandLine(
        "zsh",
        rootProject.file("Scripts/sign-macos-native-jars.zsh").absolutePath,
        jpackageInputDir.get().asFile.absolutePath,
        signingIdentity.orEmpty()
    )
}

tasks.register<Exec>("packageMacApp") {
    group = "distribution"
    description = "Builds an unsigned macOS MiMiTrends.app image for local testing."
    dependsOn(prepareJpackageInput, cleanMacApp)
    onlyIf("packageMacApp requires macOS") { System.getProperty("os.name").lowercase().contains("mac") }
    commandLine(commonJpackageArgs("app-image", macOut) + listOf(
            "--icon", macIcon.absolutePath,
            "--mac-package-identifier", "org.senatov.mimitrends",
            "--mac-app-category", "public.app-category.finance"
    ))
}

tasks.register<Exec>("packageMacDmg") {
    group = "distribution"
    description = "Builds a Developer ID signed macOS DMG. Requires MAC_SIGNING_KEY_USER_NAME."
    dependsOn(signMacNativeJars, cleanMacDmg)
    onlyIf("packageMacDmg requires macOS") { System.getProperty("os.name").lowercase().contains("mac") }
    val signingIdentity = providers.environmentVariable("MAC_SIGNING_KEY_USER_NAME").orNull
    val macDmgArgs = commonJpackageArgs("dmg", macOut).toMutableList().apply {
            addAll(listOf(
                "--icon", macIcon.absolutePath,
                "--mac-package-identifier", "org.senatov.mimitrends",
                "--mac-app-category", "public.app-category.finance",
                "--mac-sign"
            ))
            signingIdentity?.takeIf(String::isNotBlank)?.let {
                addAll(listOf("--mac-signing-key-user-name", it))
            }
            providers.environmentVariable("MAC_SIGNING_KEYCHAIN").orNull?.takeIf(String::isNotBlank)?.let {
                addAll(listOf("--mac-signing-keychain", it))
            }
    }
    doFirst {
        check(!signingIdentity.isNullOrBlank()) {
            "Set MAC_SIGNING_KEY_USER_NAME to a Developer ID Application identity from: security find-identity -v -p codesigning"
        }
    }
    commandLine(macDmgArgs)
}

val signMacDmgContainer = tasks.register<Exec>("signMacDmgContainer") {
    group = "distribution"
    description = "Signs the completed DMG container with a secure timestamp."
    dependsOn("packageMacDmg")
    onlyIf("signMacDmgContainer requires macOS") { System.getProperty("os.name").lowercase().contains("mac") }
    val signingIdentity = providers.environmentVariable("MAC_SIGNING_KEY_USER_NAME").orNull
    doFirst {
        check(!signingIdentity.isNullOrBlank()) { "Set MAC_SIGNING_KEY_USER_NAME to a Developer ID Application identity" }
    }
    commandLine(
        "codesign", "--force", "--timestamp", "--sign",
        signingIdentity?.let { if (it.startsWith("Developer ID Application:")) it else "Developer ID Application: $it" }.orEmpty(),
        macDmgFile.get().asFile.absolutePath
    )
}

val verifySignedMacDmg = tasks.register<Exec>("verifySignedMacDmg") {
    group = "verification"
    description = "Verifies the DMG, app, and every Mach-O library embedded in dependency JARs."
    dependsOn(signMacDmgContainer)
    onlyIf("verifySignedMacDmg requires macOS") { System.getProperty("os.name").lowercase().contains("mac") }
    commandLine(
        "zsh",
        rootProject.file("Scripts/verify-macos-dmg.zsh").absolutePath,
        macDmgFile.get().asFile.absolutePath
    )
}

val submitMacNotarization = tasks.register<Exec>("submitMacNotarization") {
    group = "distribution"
    description = "Submits the signed DMG to Apple Notary Service and waits for acceptance."
    dependsOn(verifySignedMacDmg)
    onlyIf("submitMacNotarization requires macOS") { System.getProperty("os.name").lowercase().contains("mac") }
    val dmg = macDmgFile.get().asFile
    val profile = providers.environmentVariable("APPLE_NOTARY_PROFILE").orNull
    val keyFile = providers.environmentVariable("APPLE_NOTARY_KEY_FILE").orNull
    val keyId = providers.environmentVariable("APPLE_NOTARY_KEY_ID").orNull
    val issuer = providers.environmentVariable("APPLE_NOTARY_ISSUER_ID").orNull
    val authentication = when {
            !profile.isNullOrBlank() -> listOf("--keychain-profile", profile)
            !keyFile.isNullOrBlank() && !keyId.isNullOrBlank() && !issuer.isNullOrBlank() ->
                listOf("--key", keyFile, "--key-id", keyId, "--issuer", issuer)
            else -> emptyList()
    }
    doFirst {
        check(dmg.isFile) { "Signed DMG was not created: $dmg" }
        check(authentication.isNotEmpty()) { "Set APPLE_NOTARY_PROFILE, or APPLE_NOTARY_KEY_FILE + APPLE_NOTARY_KEY_ID + APPLE_NOTARY_ISSUER_ID" }
    }
    commandLine(listOf("xcrun", "notarytool", "submit", dmg.absolutePath, "--wait") + authentication)
}

val stapleMacDmg = tasks.register<Exec>("stapleMacDmg") {
    group = "distribution"
    description = "Staples the Apple notarization ticket to the DMG."
    dependsOn(submitMacNotarization)
    onlyIf("stapleMacDmg requires macOS") { System.getProperty("os.name").lowercase().contains("mac") }
    commandLine("xcrun", "stapler", "staple", macDmgFile.get().asFile.absolutePath)
}

val validateNotarizedMacDmg = tasks.register<Exec>("validateNotarizedMacDmg") {
    group = "verification"
    description = "Validates the stapled notarization ticket."
    dependsOn(stapleMacDmg)
    onlyIf("validateNotarizedMacDmg requires macOS") { System.getProperty("os.name").lowercase().contains("mac") }
    commandLine("xcrun", "stapler", "validate", macDmgFile.get().asFile.absolutePath)
}

tasks.register("packageNotarizedMacDmg") {
    group = "distribution"
    description = "Builds, signs, notarizes, staples, and validates the distributable macOS DMG."
    dependsOn(validateNotarizedMacDmg)
}

tasks.register<Exec>("packageWindowsExe") {
    group = "distribution"
    description = "Builds a self-contained Windows EXE installer (requires WiX)."
    dependsOn(prepareJpackageInput, cleanWindowsExe)
    onlyIf("packageWindowsExe requires Windows") { System.getProperty("os.name").lowercase().contains("win") }
    commandLine(commonJpackageArgs("exe", windowsOut) + listOf(
            "--icon", windowsIcon.absolutePath,
            "--win-menu", "--win-menu-group", "MiMiTrends",
            "--win-shortcut", "--win-dir-chooser", "--win-per-user-install"
    ))
}

tasks.register<Exec>("packageLinuxAppImage") {
    group = "distribution"
    description = "Builds a portable self-contained Linux application directory."
    dependsOn(prepareJpackageInput, cleanLinuxApp)
    onlyIf("packageLinuxAppImage requires Linux") { System.getProperty("os.name").lowercase().contains("linux") }
    commandLine(commonJpackageArgs("app-image", linuxOut) + listOf("--icon", linuxIcon.absolutePath))
}

tasks.register<Tar>("packageLinuxPortable") {
    group = "distribution"
    description = "Builds a portable Linux tar.gz containing the executable and Java runtime."
    dependsOn("packageLinuxAppImage")
    onlyIf("packageLinuxPortable requires Linux") { System.getProperty("os.name").lowercase().contains("linux") }
    compression = Compression.GZIP
    archiveBaseName = "MiMiTrends"
    archiveVersion = nativePackageVersion
    archiveClassifier = "linux-${System.getProperty("os.arch")}"
    destinationDirectory = linuxOutputDir
    from(linuxAppImage) { into("MiMiTrends-$nativePackageVersion") }
}

tasks.register<Exec>("packageLinuxDeb") {
    group = "distribution"
    description = "Builds a self-contained Debian/Ubuntu package (requires fakeroot)."
    dependsOn(prepareJpackageInput, cleanLinuxDeb)
    onlyIf("packageLinuxDeb requires Linux") { System.getProperty("os.name").lowercase().contains("linux") }
    commandLine(commonJpackageArgs("deb", linuxOut) + listOf(
            "--icon", linuxIcon.absolutePath,
            "--linux-package-name", "mimitrends",
            "--linux-menu-group", "Office",
            "--linux-app-category", "Finance",
            "--linux-shortcut",
            "--linux-deb-maintainer", "senatov@outlook.de"
    ))
}

fun commonJpackageArgs(type: String, outputDir: File): List<String> = listOf(
    jpackageExecutable.absolutePath,
    "--type", type,
    "--name", "MiMiTrends",
    "--dest", outputDir.absolutePath,
    "--input", jpackageInputDir.get().asFile.absolutePath,
    "--main-jar", tasks.named<Jar>("jar").get().archiveFileName.get(),
    "--main-class", application.mainClass.get(),
    "--app-version", nativePackageVersion,
    "--vendor", "MiMiTrends",
    "--description", "Local-first market anomaly scanner",
    "--copyright", "Copyright 2026 MiMiTrends",
    "--java-options", "--enable-native-access=javafx.graphics,ALL-UNNAMED"
)
