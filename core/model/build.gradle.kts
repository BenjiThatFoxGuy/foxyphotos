import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // These modules are pure domain code with no Android dependencies, so the
    // Android app consumes the JVM variant exactly as it did when they were
    // plain kotlin-jvm modules. AGP 9 refuses to sit alongside the KMP plugin in
    // the same project, so there is no androidTarget() here.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Kotlin/Native cannot link Apple targets on non-macOS hosts, so the iOS
    // targets and their framework binaries are only declared on macOS.
    if (System.getProperty("os.name").startsWith("Mac OS")) {
        listOf(iosSimulatorArm64(), iosArm64()).forEach { target ->
            target.binaries.framework { baseName = "FoxyPhotosModel" }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:platform"))
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

// Keep the JVM tests on JUnit 4, matching the rest of the repository.
tasks.withType<Test>().configureEach {
    useJUnit()
}
