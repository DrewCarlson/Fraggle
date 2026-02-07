plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.metro) apply false
}

dependencies {
    kover(project(":fraggle-cli"))
    kover(project(":fraggle-agent"))
    kover(project(":fraggle-signal"))
    kover(project(":fraggle-discord"))
    kover(project(":fraggle-tools"))
    kover(project(":fraggle-api"))
}
