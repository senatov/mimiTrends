plugins {
    kotlin("jvm") version "2.4.0"
}

repositories { mavenCentral() }
kotlin { jvmToolchain(25) }

dependencies {
    implementation(project(":core"))
    implementation(project(":database"))
}
