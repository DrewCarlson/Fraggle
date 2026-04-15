plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kover)
    alias(libs.plugins.shadow)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "fraggle.coding.spike.MosaicSpikeKt"
}

dependencies {
    // Fraggle stack (filesystem/shell tools reused from fraggle-tools after PR4 pre-work)
    api(project(":fraggle-agent-core"))
    api(project(":fraggle-llm"))
    api(project(":fraggle-common"))

    // Terminal UI — Compose-for-terminal (handles rendering AND key events)
    implementation(libs.mosaic.runtime)

    // Kotlinx
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
