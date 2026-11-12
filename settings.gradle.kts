pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FoxyPhotos"

include(
    ":app",
    ":core:platform",
    ":core:model",
    ":core:updater",
    ":core:database",
    ":core:media",
    ":core:backup",
    ":core:security",
    ":provider:contract",
    ":provider:foundation",
    ":provider:google-native",
    ":provider:icloud",
    ":feature:library",
    ":feature:backup",
    ":feature:settings",
    ":test:fake-provider",
)
