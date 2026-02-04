plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

group = "org.drewcarlson"
version = "0.0.1"

kotlin {
    jvm()
    js(IR) {
        browser()
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":documented-annotations"))
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.datetime)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // Make generated sources visible to JS (JVM already gets them from KSP automatically)
        val jsMain by getting {
            kotlin.srcDir("build/generated/ksp/jvm/jvmMain/kotlin")
        }
    }
}

dependencies {
    add("kspJvm", project(":documented-processor"))
}

// Ensure KSP runs before common compilation
tasks.matching { it.name == "compileKotlinJs" || it.name == "compileCommonMainKotlinMetadata" }.configureEach {
    dependsOn("kspKotlinJvm")
}
