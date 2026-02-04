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
include(":shared")
include(":fraggle")
include(":fraggle-signal")
include(":fraggle-skills")
include(":backend")
include(":app")
include(":dashboard")
