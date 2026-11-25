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
    testImplementation(kotlin("test"))
}

tasks.test {
    jvmArgs("-Djava.library.path=${projectDir}/../../target/release")
    testLogging.showStandardStreams = true
}
