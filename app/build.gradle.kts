plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set("17")
    }
}

android {
    namespace = "gay.benjifox.foxyphotos"
    compileSdk = 36

    defaultConfig {
        applicationId = "gay.benjifox.foxyphotos"
        minSdk = 29
        targetSdk = 36

        // versionCode: in CI this is the GitHub Actions run number, which is
        // monotonically increasing across every build of this project. That
        // guarantees the installed APK is never reported as a downgrade.
        // Locally we keep a small marker file and increment the same way.
        versionCode = getVersionCode()
        // versionName: human facing version comes from the git tag, so a tagged
        // release "v0.1" produces versionName "0.1" (nightlies fall back to the
        // VERSION file, e.g. "0.1").
        versionName = getVersionName()
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        create("release") {
            val keyStorePath = System.getenv("FOXYPHOTOS_KEYSTORE_PATH")
            if (keyStorePath != null) {
                storeFile = file(keyStorePath)
                storePassword = System.getenv("FOXYPHOTOS_STORE_PASSWORD")
                keyAlias = System.getenv("FOXYPHOTOS_KEY_ALIAS")
                keyPassword = System.getenv("FOXYPHOTOS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug package ID so debug builds never collide with a release or
            // a previous debug build on a device, and stay installable anywhere.
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:backup"))
    implementation(project(":core:database"))
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

private fun getVersionCode(): Int {
    // CI run number is globally monotonically increasing, so it is the cleanest
    // versionCode by construction and never collides with a previous release.
    val githubRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    return githubRunNumber ?: getLocalVersionCode()
}

private fun getLocalVersionCode(): Int {
    val marker = file("local.version.marker")
    val current = marker.takeIf { it.exists() }?.readText()?.toIntOrNull() ?: 0
    val next = current + 1
    marker.writeText("${next}\n")
    return next
}

private fun getVersionName(): String {
    val githubRef = System.getenv("GITHUB_REF")
    // refs/tags/v0.1  ->  "0.1"
    val tag = githubRef?.takeIf { it.startsWith("refs/tags/") }?.removePrefix("refs/tags/")
    if (!tag.isNullOrEmpty()) return tag.removePrefix("v")
    val versionFile = file("VERSION")
    val raw = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
    return raw.removePrefix("v").ifBlank { "0.1" }
}
