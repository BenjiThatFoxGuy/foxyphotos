import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    if (System.getProperty("os.name").startsWith("Mac OS")) {
        listOf(iosSimulatorArm64(), iosArm64()).forEach { target ->
            target.binaries.framework { baseName = "FoxyPhotosGoogleCore" }
        }
    }
    sourceSets {
        commonMain.dependencies { implementation(libs.kotlinx.coroutines.core) }
        jvmTest.dependencies { implementation(libs.junit) }
    }
}

tasks.withType<Test>().configureEach { useJUnit() }
