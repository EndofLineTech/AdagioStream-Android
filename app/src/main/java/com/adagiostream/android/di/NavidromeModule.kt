package com.adagiostream.android.di

import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NavidromeModule {

    /**
     * Provides a [NavidromeApiFactory] backed by the app's shared [OkHttpClient]
     * (from [NetworkModule]) — which carries no logging interceptor in release,
     * so Subsonic auth params (u/t/s) are never logged.
     *
     * The factory creates a transient [NavidromeApi] for one-shot credential
     * validation in the add/edit form.
     */
    @Provides
    @Singleton
    fun provideNavidromeApiFactory(client: OkHttpClient): NavidromeApiFactory =
        NavidromeApiFactory { host, username, password ->
            NavidromeApi(
                client = client,
                host = host,
                username = username,
                password = password,
            )
        }
}
