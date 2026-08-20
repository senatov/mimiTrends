plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.smile.core) {
        exclude(group = "org.duckdb", module = "duckdb_jdbc")
    }
    implementation(libs.sqlite.jdbc)
    implementation(libs.duckdb.jdbc)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
