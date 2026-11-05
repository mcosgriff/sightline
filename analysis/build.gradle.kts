// Pure Kotlin — no Android. Signal health from thresholds, the dip detector, aggregations for the charts and the
// diagnosis rules, so every rule is unit-tested on the JVM without an emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":protocol"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    // FakeHdhr's signal patterns, fed straight into the dip detector.
    testImplementation(testFixtures(project(":protocol")))
}
