package com.adagiostream.android.di

import android.content.Context
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.metadata.ITunesSearchApi
import com.adagiostream.android.service.metadata.XMPlaylistApi
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.VLCPlayerWrapper
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import dagger.Module
import okio.Path.Companion.toOkioPath
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Provides
    @Singleton
    fun providePersistenceService(
        @ApplicationContext context: Context,
        json: Json,
    ): PersistenceService = PersistenceService(context, json)

    @Provides
    @Singleton
    fun provideVLCPlayerWrapper(
        @ApplicationContext context: Context,
        persistenceService: PersistenceService,
    ): VLCPlayerWrapper {
        val settings = persistenceService.loadSettingsSync()
        return VLCPlayerWrapper(context, settings.bufferDurationSeconds.coerceIn(5, 15))
    }

    @Provides
    @Singleton
    fun provideXMPlaylistApi(client: OkHttpClient): XMPlaylistApi = XMPlaylistApi(client)

    @Provides
    @Singleton
    fun provideESPNScoreService(client: OkHttpClient): ESPNScoreService = ESPNScoreService(client)

    @Provides
    @Singleton
    fun provideITunesSearchApi(client: OkHttpClient): ITunesSearchApi = ITunesSearchApi(client)

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
}
