plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.drewcarlson"
version = "0.0.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":libs:documented-annotations"))
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
}
