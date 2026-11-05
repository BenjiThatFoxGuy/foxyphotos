plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":provider:contract"))
    implementation(project(":provider:foundation"))
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
