plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    id("vite-serve")
}

kotlin {
    js(IR) {
        useEsModules()
        browser()
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            // Shared models
            implementation(project(":shared"))

            // Compose HTML
            implementation(libs.compose.html.core)
            implementation(libs.compose.runtime)

            // Ktor Client for API calls
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            // Kotlin JS Wrappers
            implementation(libs.kotlinjs.browser)
            implementation(libs.kotlinjs.web)

            // Kotlinx
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Client-side routing
            implementation(libs.routing.compose)

            // NPM dependencies
            implementation(npm("@fontsource/inter", "5.2.8"))
            implementation(npm("@fontsource/jetbrains-mono", "5.2.8"))
            implementation(npm("bootstrap-icons", "1.13.1"))
            implementation(npm("highlight.js", "11.11.1"))
        }
    }
}
