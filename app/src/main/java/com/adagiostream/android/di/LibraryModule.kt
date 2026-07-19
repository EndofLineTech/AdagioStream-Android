package com.adagiostream.android.di

import android.content.Context
import com.adagiostream.android.service.audiobookshelf.OfflineAudiobookSource
import com.adagiostream.android.service.download.AppFileDownloadStore
import com.adagiostream.android.service.download.AudiobookDownloadActions
import com.adagiostream.android.service.download.AudiobookDownloadManager
import com.adagiostream.android.service.download.DownloadFileStore
import com.adagiostream.android.service.download.DownloadLocator
import com.adagiostream.android.service.download.RoomDownloadLocator
import com.adagiostream.android.service.library.db.AudiobookDownloadDao
import com.adagiostream.android.service.library.db.DownloadDao
import com.adagiostream.android.service.library.db.LibraryDao
import com.adagiostream.android.service.library.db.NavidromeCacheDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the E6 offline-download + library-cache layer.
 *
 * Mirrors the [NetworkModule]/[AppModule] patterns: the Room database and its DAOs
 * are application singletons; interface seams ([DownloadFileStore],
 * [DownloadLocator]) are provided so the coordinator/manager depend on abstractions
 * that are swappable in tests.
 */
@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {

    @Provides
    @Singleton
    fun provideNavidromeCacheDatabase(
        @ApplicationContext context: Context,
    ): NavidromeCacheDatabase = NavidromeCacheDatabase.build(context)

    @Provides
    @Singleton
    fun provideDownloadDao(db: NavidromeCacheDatabase): DownloadDao = db.downloadDao()

    @Provides
    @Singleton
    fun provideLibraryDao(db: NavidromeCacheDatabase): LibraryDao = db.libraryDao()

    @Provides
    @Singleton
    fun provideDownloadFileStore(
        @ApplicationContext context: Context,
    ): DownloadFileStore = AppFileDownloadStore(context)

    @Provides
    @Singleton
    fun provideDownloadLocator(locator: RoomDownloadLocator): DownloadLocator = locator

    @Provides
    @Singleton
    fun provideAudiobookDownloadDao(db: NavidromeCacheDatabase): AudiobookDownloadDao =
        db.audiobookDownloadDao()

    /** The detail-screen download button seam (beads_adagio-59p.1.6). */
    @Provides
    @Singleton
    fun provideAudiobookDownloadActions(manager: AudiobookDownloadManager): AudiobookDownloadActions =
        manager

    /** The coordinator's offline-playback source (beads_adagio-59p.1.6). */
    @Provides
    @Singleton
    fun provideOfflineAudiobookSource(manager: AudiobookDownloadManager): OfflineAudiobookSource =
        manager
}
