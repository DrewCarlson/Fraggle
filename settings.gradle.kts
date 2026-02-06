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

include(":libs:documented-annotations")
include(":libs:documented-processor")

include(":fraggle-di")
include(":fraggle-common")
include(":fraggle-agent")
include(":fraggle-signal")
include(":fraggle-discord")
include(":fraggle-skills")
include(":fraggle-api")
include(":fraggle-cli")
include(":fraggle-dashboard")
