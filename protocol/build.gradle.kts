// Pure Kotlin — no Android. The HDHomeRun protocol: packet codec, discovery, the control client, the status parser
// and the HTTP helpers. Android pieces (MulticastLock, the Wi-Fi broadcast address) are passed in from outside, so
// everything here runs and is tested on the JVM, and could later run headless on a server.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
