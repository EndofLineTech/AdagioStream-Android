package com.adagiostream.android.di

import android.content.Context
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.VLCPlayerWrapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
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
    ): VLCPlayerWrapper = VLCPlayerWrapper(context)
}
