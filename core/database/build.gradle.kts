plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "gay.benjifox.foxyphotos.core.database"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":core:backup"))
}
