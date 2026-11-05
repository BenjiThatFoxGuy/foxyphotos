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
    ":core:model",
    ":core:database",
    ":core:media",
    ":core:backup",
    ":core:security",
    ":provider:contract",
    ":provider:google-native",
    ":feature:library",
    ":feature:backup",
    ":feature:settings",
    ":test:fake-provider",
)
