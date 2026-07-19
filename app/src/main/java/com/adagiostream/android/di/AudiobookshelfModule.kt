package com.adagiostream.android.di

import android.content.Context
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.audiobookshelf.ABSProgressSyncQueue
import com.adagiostream.android.service.audiobookshelf.AudiobookPlaybackCoordinator
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiFactory
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfAuth
import com.adagiostream.android.service.audiobookshelf.OfflineAudiobookSource
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.AudiobookPlaybackLauncher
import com.adagiostream.android.service.player.VLCPlayerWrapper
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudiobookshelfModule {

    /**
     * Provides an [AudiobookshelfApiFactory] backed by the app's shared
     * [OkHttpClient] (from [NetworkModule]) — which carries no logging
     * interceptor in release, so Bearer/JWT auth material is never logged.
     */
    @Provides
    @Singleton
    fun provideAudiobookshelfApiFactory(client: OkHttpClient): AudiobookshelfApiFactory =
        AudiobookshelfApiFactory { host, username, password, tokens, onTokensChanged ->
            AudiobookshelfApi(
                client = client,
                host = host,
                auth = AudiobookshelfAuth(
                    client = client,
                    host = host,
                    username = username,
                    password = password,
                    initialTokens = tokens,
                    onTokensChanged = onTokensChanged,
                ),
            )
        }

    /** Disk-persisted offline progress queue (beads_adagio-59p.1.5). */
    @Provides
    @Singleton
    fun provideAbsProgressSyncQueue(@ApplicationContext context: Context): ABSProgressSyncQueue =
        ABSProgressSyncQueue(File(context.filesDir, "abs_progress_queue.json"))

    /**
     * The audiobook playback engine (beads_adagio-59p.1.5). Driven through the
     * [VLCPlayerWrapper]'s AudiobookFilePlayer port; rotated JWTs are written
     * straight back to the encrypted account store via [AccountManager]
     * (Lazy — the manager pulls in the whole account graph, only needed on an
     * actual rotation).
     */
    @Provides
    @Singleton
    fun provideAudiobookPlaybackCoordinator(
        wrapper: VLCPlayerWrapper,
        queue: ABSProgressSyncQueue,
        apiFactory: AudiobookshelfApiFactory,
        persistenceService: PersistenceService,
        accountManager: Lazy<AccountManager>,
        offlineBooks: OfflineAudiobookSource,
    ): AudiobookPlaybackCoordinator {
        val tokenPersistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return AudiobookPlaybackCoordinator(
            player = wrapper,
            queue = queue,
            apiFactory = apiFactory,
            persistenceService = persistenceService,
            playerState = wrapper.playbackState,
            playbackSource = wrapper.playbackSource,
            onTokensRotated = { account, tokens ->
                val abs = account.type as? AccountType.Audiobookshelf
                if (abs != null) {
                    tokenPersistScope.launch {
                        // Persist-only: a token rotation must not re-fetch every
                        // channel list (review M2).
                        accountManager.get().updateAccountCredentials(
                            account.copy(
                                type = abs.copy(
                                    accessToken = tokens?.accessToken,
                                    refreshToken = tokens?.refreshToken,
                                ),
                            ),
                        )
                    }
                }
            },
            offlineBooks = offlineBooks,
        )
    }

    /**
     * Bridges the browsing UI's Resume/Play affordance (beads_adagio-59p.1.4)
     * to the playback engine (59p.1.5). Pure delegation — the coordinator
     * owns session open/close, resume position, and token rotation.
     */
    @Provides
    @Singleton
    fun provideAudiobookPlaybackLauncher(
        coordinator: AudiobookPlaybackCoordinator,
    ): AudiobookPlaybackLauncher =
        AudiobookPlaybackLauncher { account, libraryItemId, resumeOverride ->
            coordinator.playAudiobook(account, libraryItemId, resumeOverride)
        }
}
