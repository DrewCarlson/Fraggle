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
    }
}

include(":fraggle")
include(":fraggle-signal")
include(":fraggle-skills")
include(":backend")
include(":app")
