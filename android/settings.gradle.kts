// `-PjvmOnly=true` builds only the pure Kotlin modules: no Android SDK and no Google Maven needed.
val jvmOnly = providers.gradleProperty("jvmOnly").orNull == "true"

pluginManagement {
    repositories {
        if (providers.gradleProperty("jvmOnly").orNull != "true") google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!jvmOnly) google()
        mavenCentral()
    }
}

rootProject.name = "dejavu-android"

include(":core:model", ":core:network", ":core:data")
if (!jvmOnly) include(":app-customer")
