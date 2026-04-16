plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.shadow)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Fraggle stack — coding agent reuses the base filesystem/shell/web tools
    // from fraggle-tools (decoupled from fraggle-assistant during PR4 pre-work)
    // and adds its own edit_file tool on top via CodingToolRegistry.
    api(project(":fraggle-tools"))
    api(project(":fraggle-agent-core"))
    api(project(":fraggle-llm"))
    api(project(":fraggle-common"))

    // Owns rendering, event loop, layout primitives, the Editor,
    // Markdown → ANSI, and all base components.
    // Coding-agent-specific UI lives under fraggle.coding.ui on top of these.
    api(project(":fraggle-tui"))

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
