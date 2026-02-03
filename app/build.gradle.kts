plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "org.drewcarlson"
version = "0.0.1"

application {
    mainClass.set("org.drewcarlson.fraggle.MainKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":fraggle"))
    implementation(project(":fraggle-signal"))
    implementation(project(":fraggle-skills"))

    // Kotlinx
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // YAML Configuration
    implementation(libs.kaml)

    // CLI
    implementation(libs.clikt)

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

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
    environment("FRAGGLE_ROOT", rootProject.projectDir.resolve("runtime-dev").absolutePath)
}
