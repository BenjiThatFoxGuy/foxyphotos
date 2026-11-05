plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "gay.benjifox.foxyphotos.core.media"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
