pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        // Зеркало Maven Central от Google: стабильнее при частых запросах из CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Зеркало Maven Central от Google: стабильнее при частых запросах из CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "QuietFiles"

include(":app")
include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:storage")
include(":core:data")
include(":core:operations")
include(":feature:home")
include(":feature:browser")
include(":feature:search")
include(":feature:settings")
include(":feature:editor")
include(":feature:viewer")
