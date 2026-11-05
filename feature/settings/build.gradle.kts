plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "gay.benjifox.foxyphotos.feature.settings"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":core:model"))
}
