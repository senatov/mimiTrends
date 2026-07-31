package org.senatov.mimitrends

import java.util.Properties
import java.util.jar.Manifest

object BuildInfo {
    private val fallback = Properties().apply {
        BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use(::load)
    }
    private val manifest: Manifest? = BuildInfo::class.java.classLoader
        .getResources("META-INF/MANIFEST.MF")
        .asSequence()
        .mapNotNull { url -> runCatching { url.openStream().use(::Manifest) }.getOrNull() }
        .firstOrNull { it.mainAttributes.getValue("Implementation-Title") == "MiMiTrends" }

    val version: String = manifest?.mainAttributes?.getValue("Implementation-Version")
        ?: fallback.getProperty("version", "development")
    val buildNumber: String = manifest?.mainAttributes?.getValue("Build-Number")
        ?: fallback.getProperty("build", "dev")
    val buildType: String = manifest?.mainAttributes?.getValue("Build-Type")
        ?: fallback.getProperty("type", "DEV BUILD")
    val buildTime: String = manifest?.mainAttributes?.getValue("Build-Time")
        ?: fallback.getProperty("time", "unknown")
    val buildHost: String = manifest?.mainAttributes?.getValue("Build-Host")
        ?: fallback.getProperty("host", "unknown")
    val displayVersion: String = "$version · build $buildNumber"
}
