package io.cosgriff.sightline.data.di

import javax.inject.Qualifier

/** For work that outlives any one screen, such as releasing a stale tuner lock at start. Lives as long as the app. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
