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
    // Fraggle stack — coding agent reuses the base filesystem/shell/web tools
    // from fraggle-tools (decoupled from fraggle-assistant during PR4 pre-work)
    // and adds its own edit_file tool on top via CodingToolRegistry.
    api(project(":fraggle-tools"))
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
