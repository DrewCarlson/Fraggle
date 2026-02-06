plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Note: Metro runtime is automatically added by the metro plugin

    // Shared models
    api(project(":fraggle-common"))

    // Ktor Client (shared across all modules)
    api(ktorLibs.client.core)
    api(ktorLibs.client.cio)
    api(ktorLibs.client.contentNegotiation)
    api(ktorLibs.client.logging)
    api(ktorLibs.serialization.kotlinx.json)

    // Kotlinx
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.logback.classic)
}
