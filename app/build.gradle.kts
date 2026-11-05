plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:media"))
    implementation(project(":core:security"))
    implementation(project(":provider:contract"))
    implementation(project(":provider:google-native"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
}
