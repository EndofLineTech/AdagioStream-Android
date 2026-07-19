package com.adagiostream.android.di

import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApiFactory
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfAuth
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
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
}
