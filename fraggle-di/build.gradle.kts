plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.metro)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Note: Metro runtime is automatically added by the metro plugin

    // Ktor Client (shared across all modules)
    api(libs.ktor.client.core)
    api(libs.ktor.client.cio)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.client.logging)
    api(libs.ktor.serialization.kotlinx.json)

    // Kotlinx
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.logback.classic)
}
