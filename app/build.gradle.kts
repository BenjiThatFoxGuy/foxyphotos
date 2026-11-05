plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "gay.benjifox.foxyphotos"
    compileSdk = 36

    defaultConfig {
        applicationId = "gay.benjifox.foxyphotos"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }
}
