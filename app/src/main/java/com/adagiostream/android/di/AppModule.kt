package com.adagiostream.android.di

import android.content.Context
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.metadata.ITunesSearchApi
import com.adagiostream.android.service.metadata.SXMMetadataService
import com.adagiostream.android.service.metadata.StellarTunerLogApi
import com.adagiostream.android.service.metadata.XMPlaylistApi
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.CastManager
import com.adagiostream.android.service.player.LibraryTrackPlayer
import com.adagiostream.android.service.player.VLCPlayerWrapper
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.adagiostream.android.service.navidrome.NavidromeCoverArtFetcher
import com.adagiostream.android.service.navidrome.NavidromeCoverArtKeyer
import com.adagiostream.android.service.navidrome.NavidromeCoverArtRequest
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
    fun provideCastManager(
        @ApplicationContext context: Context,
    ): CastManager = CastManager(context)

    @Provides
    @Singleton
    fun provideVLCPlayerWrapper(
        @ApplicationContext context: Context,
        persistenceService: PersistenceService,
        castManager: CastManager,
    ): VLCPlayerWrapper {
        val settings = persistenceService.loadSettingsSync()
        return VLCPlayerWrapper(context, settings.bufferDurationSeconds.coerceIn(5, 15), castManager)
    }

    /**
     * Binds the [VLCPlayerWrapper] singleton as the [LibraryTrackPlayer] port the
     * [com.adagiostream.android.service.player.MusicPlaybackCoordinator] drives —
     * the coordinator depends on the interface (not the concrete wrapper) so it is
     * unit-testable on the JVM without native libVLC.
     */
    @Provides
    @Singleton
    fun provideLibraryTrackPlayer(wrapper: VLCPlayerWrapper): LibraryTrackPlayer = wrapper

    @Provides
    @Singleton
    fun provideXMPlaylistApi(client: OkHttpClient): XMPlaylistApi = XMPlaylistApi(client)

    @Provides
    @Singleton
    fun provideStellarTunerLogApi(client: OkHttpClient): StellarTunerLogApi = StellarTunerLogApi(client)

    @Provides
    @Singleton
    fun provideSXMMetadataService(
        xmPlaylistApi: XMPlaylistApi,
        stellarTunerLogApi: StellarTunerLogApi,
        persistenceService: PersistenceService,
    ): SXMMetadataService = SXMMetadataService(
        xmPlaylistApi = xmPlaylistApi,
        stellarTunerLogApi = stellarTunerLogApi,
        initialSource = persistenceService.loadSettingsSync().sxmMetadataSource,
    )

    @Provides
    @Singleton
    fun provideESPNScoreService(client: OkHttpClient): ESPNScoreService = ESPNScoreService(client)

    @Provides
    @Singleton
    fun provideITunesSearchApi(client: OkHttpClient): ITunesSearchApi = ITunesSearchApi(client)

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        client: OkHttpClient,
    ): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // Standard OkHttp fetcher for regular https:// URLs.
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                // Navidrome cover art: stable cache key + authenticated URL fetch.
                // The keyer MUST be registered before the fetcher factory so Coil
                // uses it during cache-key resolution for NavidromeCoverArtRequest.
                add(NavidromeCoverArtKeyer())
                add(NavidromeCoverArtFetcher.Factory(client))
            }
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
