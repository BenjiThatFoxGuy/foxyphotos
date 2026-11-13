plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":provider:contract"))
    implementation(project(":provider:google-core"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
