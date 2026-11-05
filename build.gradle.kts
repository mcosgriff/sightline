plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

subprojects {
    // A failing test prints its whole stack, causes and all: CI's log is often the only place a failure that
    // doesn't happen locally can be read.
    tasks.withType<Test>().configureEach {
        testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

// Robolectric's Android jar. Left to itself, Robolectric fetches android-all-instrumented from Maven Central at test
// time, outside Gradle: no cache holds it, and a Maven Central hiccup fails every screen test in CI. Here Gradle
// resolves the jar (version in libs.versions.toml beside `robolectric`), copies it to build/robolectric, and :app's
// unit tests read it from there with Robolectric kept offline.
abstract class RobolectricOffline : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: DirectoryProperty

    override fun asArguments() = listOf(
        "-Drobolectric.offline=true",
        "-Drobolectric.dependency.dir=${jars.get().asFile.absolutePath}",
    )
}

project(":app") {
    val androidAll = configurations.create("robolectricAndroidAllSdk35") {
        isCanBeConsumed = false
        isTransitive = false
    }.also { dependencies.add(it.name, rootProject.libs.robolectric.androidAll.sdk35) }
    val copyJars = tasks.register<Sync>("robolectricAndroidAll") {
        description = "Copies the Android jar Robolectric runs the unit tests on, so it never downloads it."
        from(androidAll)
        into(layout.buildDirectory.dir("robolectric"))
    }
    val offline = objects.newInstance<RobolectricOffline>().apply {
        jars.fileProvider(copyJars.map { it.destinationDir })
    }
    tasks.withType<Test>().configureEach { jvmArgumentProviders.add(offline) }
}
