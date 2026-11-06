plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":provider:contract"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit)
}
