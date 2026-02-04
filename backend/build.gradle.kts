plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

group = "org.drewcarlson"
version = "0.0.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Shared models
    api(project(":shared"))

    // Fraggle core module
    implementation(project(":fraggle"))

    // Ktor Server - expose core and netty via api for app module
    api(libs.ktor.server.core)
    api(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Kotlinx
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}
