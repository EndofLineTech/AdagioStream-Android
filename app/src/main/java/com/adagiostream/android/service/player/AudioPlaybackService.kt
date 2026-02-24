package com.adagiostream.android.service.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.adagiostream.android.model.Channel
import com.adagiostream.android.service.provider.ProviderManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlaybackService : MediaLibraryService() {

    @Inject lateinit var vlcPlayer: VLCPlayerWrapper
    @Inject lateinit var providerManager: ProviderManager

    private var mediaLibrarySession: MediaLibrarySession? = null

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val ROOT_ID = "root"
        private const val FAVORITES_ID = "favorites"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val forwardingPlayer = VLCForwardingPlayer(vlcPlayer)
        mediaLibrarySession = MediaLibrarySession.Builder(this, forwardingPlayer, LibraryCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Audio playback controls"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    @OptIn(UnstableApi::class)
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .setTitle("AdagioStream")
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val groups = providerManager.groups.value
            val channels = providerManager.channels.value
            val favorites = channels.filter { it.isFavorite }

            val items: List<MediaItem> = when (parentId) {
                ROOT_ID -> {
                    val result = mutableListOf<MediaItem>()
                    if (favorites.isNotEmpty()) {
                        result.add(buildBrowsableItem(FAVORITES_ID, "Favorites"))
                    }
                    result.addAll(groups.map { buildBrowsableItem(it.name, it.name) })
                    result
                }
                FAVORITES_ID -> favorites.map { buildPlayableItem(it) }
                else -> {
                    groups.find { it.name == parentId }
                        ?.channels
                        ?.map { buildPlayableItem(it) }
                        ?: emptyList()
                }
            }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val results = providerManager.channels.value
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { buildPlayableItem(it) }

            mediaLibrarySession?.notifySearchResultChanged(
                browser, query, results.size, params
            )
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val results = providerManager.channels.value
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { buildPlayableItem(it) }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
            )
        }

        private fun buildBrowsableItem(id: String, title: String): MediaItem {
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
        }

        private fun buildPlayableItem(channel: Channel): MediaItem {
            return MediaItem.Builder()
                .setMediaId(channel.id)
                .setUri(channel.streamURL)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(channel.name)
                        .setStation(channel.name)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                        .build()
                )
                .build()
        }
    }
}
