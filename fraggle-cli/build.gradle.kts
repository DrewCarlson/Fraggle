plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.kover)
    alias(libs.plugins.metro)
    application
}

application {
    mainClass.set("fraggle.MainKt")
    applicationName = "fraggle"
}

distributions.configureEach {
    distributionBaseName.set("fraggle")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":fraggle-common"))
    implementation(project(":fraggle-assistant"))
    implementation(project(":fraggle-signal"))
    implementation(project(":fraggle-discord"))
    implementation(project(":fraggle-tools"))
    implementation(project(":fraggle-api"))

    // Kotlinx
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // YAML Configuration
    implementation(libs.kaml)

    // CLI
    implementation(libs.clikt)

    // Logging
    implementation(libs.logback.classic)

    // Ktor Server
    implementation(ktorLibs.server.autoHeadResponse)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.callLogging)

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

tasks.shadowDistZip {
    archiveVersion.set("")
}

tasks.shadowDistTar {
    archiveVersion.set("")
}

val dashboard = evaluationDependsOn(":fraggle-dashboard")

tasks.shadowJar {
    dependsOn(dashboard.tasks.getByName("jsBrowserProductionDist"))
    archiveFileName.set("fraggle.jar")
    archiveBaseName.set("fraggle")
    archiveClassifier.set("fraggle")
    archiveVersion.set("")
    manifest {
        attributes(mapOf("Main-Class" to application.mainClass.get()))
    }
    mergeServiceFiles()
    from(dashboard.layout.buildDirectory.file("vite/js/productionExecutable")) {
        into("dashboard")
    }
}
