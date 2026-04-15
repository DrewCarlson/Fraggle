plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.metro)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Shared models (ProviderConfig, AgentConfig, TraceEventRecord, etc.)
    api(project(":fraggle-common"))

    // DI (scopes, qualifiers, HTTP client qualifiers)
    api(project(":fraggle-di"))

    // LLM provider (LMStudioProvider, ChatRequest/Response, Message types)
    api(project(":fraggle-llm"))

    // Ktor client (RemoteToolClient forwards HTTP calls)
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)
    implementation(ktorLibs.client.logging)
    implementation(ktorLibs.serialization.kotlinx.json)

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
    testImplementation(ktorLibs.client.logging)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
