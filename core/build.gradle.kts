plugins {
    kotlin("jvm") version "2.4.0"
}

repositories { mavenCentral() }
kotlin { jvmToolchain(25) }

dependencies {
    api("org.slf4j:slf4j-api:2.0.17")
}
