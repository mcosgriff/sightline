// Pure Kotlin — no Android. The HDHomeRun protocol: packet codec, discovery, the control client, the status parser
// and the HTTP helpers. Android pieces (MulticastLock, the Wi-Fi broadcast address) are passed in from outside, so
// everything here runs and is tested on the JVM, and could later run headless on a server.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // FakeHdhr lives in src/testFixtures: these tests use it, and debug builds of :app use it as the Demo tuner.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

// The captures from a real unit (docs/protocol-captures) are the golden test data, read from where they live.
sourceSets.test {
    resources.srcDir(rootProject.file("docs/protocol-captures"))
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
