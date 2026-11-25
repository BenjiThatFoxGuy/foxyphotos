plugins {
    kotlin("jvm") version "2.4.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDir("../../out/kotlin/uniffi/foxy_spike_codec")
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.12.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    jvmArgs(
        "-Djava.library.path=${projectDir}/../../target/release",
        "-Djna.library.path=${projectDir}/../../target/release",
    )
    testLogging.showStandardStreams = true
}
