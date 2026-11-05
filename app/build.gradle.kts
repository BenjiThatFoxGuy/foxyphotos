import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "gay.benjifox.foxyphotos"
    compileSdk = 36

    defaultConfig {
        applicationId = "gay.benjifox.foxyphotos"
        minSdk = 29
        targetSdk = 36

        // Nightlies and tags use one workflow's rolling run number. Re-running
        // a run preserves its code; local markers are separate from CI.
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
    testImplementation(libs.junit)
}

private fun getVersionCode(): Int {
    val githubRunNumber = System.getenv("GITHUB_RUN_NUMBER")
    if (githubRunNumber != null) {
        return githubRunNumber.toInt().also {
            require(it in 1..2_100_000_000) { "Invalid Android versionCode: $it" }
        }
    }
    check(System.getenv("CI") != "true") { "CI requires GITHUB_RUN_NUMBER" }
    return getLocalVersionCode()
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
    val versionFile = rootProject.file("VERSION")
    val raw = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
    return raw.removePrefix("v").ifBlank { "0.1" }
}
