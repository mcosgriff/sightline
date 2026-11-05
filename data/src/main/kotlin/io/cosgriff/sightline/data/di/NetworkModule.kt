package io.cosgriff.sightline.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.cosgriff.sightline.data.net.AndroidDiscoveryEnvironment
import io.cosgriff.sightline.protocol.Discovery
import io.cosgriff.sightline.protocol.DiscoveryEnvironment
import io.cosgriff.sightline.protocol.HttpApi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** The protocol pieces, with their Android parts filled in. Only the tuner and Open-Meteo are ever called. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds
    abstract fun discoveryEnvironment(impl: AndroidDiscoveryEnvironment): DiscoveryEnvironment

    companion object {
        @Provides
        @Singleton
        fun okHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            // An ATSC 3.0 stream (ADR-0004) is read for as long as the tuner is held; a gap this long means it ended.
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        @Provides
        @Singleton
        fun httpApi(client: OkHttpClient): HttpApi = HttpApi(client)

        @Provides
        fun discovery(environment: DiscoveryEnvironment): Discovery = Discovery(environment)
    }
}
