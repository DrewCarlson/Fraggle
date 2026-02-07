rootProject.name = "Fraggle"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("ktorLibs") {
            val ktorVersion = file("gradle/libs.versions.toml")
                .useLines { lines ->
                    lines.firstNotNullOfOrNull { line ->
                        line.substringAfter("ktor = \"", "")
                            .trimEnd('"')
                            .takeIf(String::isNotBlank)
                    }
                }
            from("io.ktor:ktor-version-catalog:${ktorVersion}")
        }
    }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

include(":libs:documented-annotations")
include(":libs:documented-processor")

include(":fraggle-di")
include(":fraggle-common")
include(":fraggle-agent")
include(":fraggle-signal")
include(":fraggle-discord")
include(":fraggle-tools")
include(":fraggle-api")
include(":fraggle-cli")
include(":fraggle-dashboard")
