plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.drewcarlson"
version = "0.0.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.koog.agents)

    testImplementation(libs.kotlin.test.junit)
}
