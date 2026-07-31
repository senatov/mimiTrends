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

val appVersion = providers.gradleProperty("appVersion").getOrElse("0.0.0.1")
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
    implementation(platform("org.apache.logging.log4j:log4j-bom:2.26.1"))
    implementation("org.apache.logging.log4j:log4j-api")
    implementation("org.apache.logging.log4j:log4j-core")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
val appImageOutputDir = layout.buildDirectory.dir("jpackage/output")

val prepareJpackageInput = tasks.register<Copy>("prepareJpackageInput") {
    dependsOn(tasks.named("jar"))
    into(jpackageInputDir)
    from(tasks.named<Jar>("jar"))
    from(configurations.runtimeClasspath)
}

tasks.register<Exec>("packageMacApp") {
    group = "distribution"
    description = "Builds a macOS MiMiTrends.app image."
    dependsOn(prepareJpackageInput)
    val javaHome = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(26)
    }.get().metadata.installationPath.asFile
    val inputDir = jpackageInputDir.get().asFile
    val outputDir = appImageOutputDir.get().asFile
    val iconFile = project.file("src/main/resources/icons/MiMiTrends.icns")
    val args = mutableListOf(
        javaHome.resolve("bin/jpackage").absolutePath,
        "--type", "app-image",
        "--name", "MiMiTrends",
        "--dest", outputDir.absolutePath,
        "--input", inputDir.absolutePath,
        "--main-jar", tasks.named<Jar>("jar").get().archiveFileName.get(),
        "--main-class", application.mainClass.get(),
        "--app-version", appVersion,
        "--java-options", "--enable-native-access=javafx.graphics,ALL-UNNAMED"
    )
    if (iconFile.exists()) args += listOf("--icon", iconFile.absolutePath)
    doFirst {
        outputDir.mkdirs()
        outputDir.resolve("MiMiTrends.app").deleteRecursively()
    }
    commandLine(args)
}
