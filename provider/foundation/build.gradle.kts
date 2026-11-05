plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
