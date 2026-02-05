rootProject.name = "Fraggle"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
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

include(":documented-annotations")
include(":documented-processor")
include(":fraggle-common")
include(":fraggle-agent")
include(":fraggle-signal")
include(":fraggle-skills")
include(":fraggle-api")
include(":fraggle-cli")
include(":fraggle-dashboard")
