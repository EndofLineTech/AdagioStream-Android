package com.adagiostream.android.service.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.adagiostream.android.R
import com.adagiostream.android.model.Channel
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.account.AccountManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlaybackService : MediaLibraryService() {

    @Inject lateinit var vlcPlayerWrapper: VLCPlayerWrapper
    @Inject lateinit var accountManager: AccountManager
    @Inject lateinit var persistenceService: PersistenceService

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastBrowsedParentId: String? = null

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val ROOT_ID = "root"
        private const val FAVORITES_ID = "favorites"
        private val TOGGLE_FAVORITE_COMMAND = SessionCommand("TOGGLE_FAVORITE", Bundle.EMPTY)
        private val SEEK_TO_LIVE_COMMAND = SessionCommand("SEEK_TO_LIVE", Bundle.EMPTY)
        private val TOGGLE_LOVED_TRACK_COMMAND = SessionCommand("TOGGLE_LOVED_TRACK", Bundle.EMPTY)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        buildSession()

        // Reactive browse tree updates
        serviceScope.launch {
            combine(
                accountManager.groups,
                accountManager.channels,
            ) { _, _ -> Unit }.collect {
                mediaLibrarySession?.connectedControllers?.forEach { controller ->
                    mediaLibrarySession?.notifyChildrenChanged(controller, ROOT_ID, 0, null)
                    mediaLibrarySession?.notifyChildrenChanged(controller, FAVORITES_ID, 0, null)
                }
            }
        }

        // Persist last played channel
        serviceScope.launch {
            vlcPlayerWrapper.currentChannel.collect { channel ->
                if (channel != null) {
                    persistenceService.saveLastPlayed(channel.id)
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildSession() {
        val sessionPlayer = VLCSessionPlayer(vlcPlayerWrapper)

        mediaLibrarySession?.release()
        mediaLibrarySession = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .build()
    }

    @OptIn(UnstableApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Immediately satisfy the foreground service requirement with a placeholder
        // notification. Media3 will replace it with proper media controls once playback starts.
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AdagioStream")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, 1001, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        vlcPlayerWrapper.stop()
        stopSelf()
    }

    override fun onDestroy() {
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    private fun channelListForContext(channel: Channel): List<Channel> {
        val channels = accountManager.channels.value
        return when (lastBrowsedParentId) {
            FAVORITES_ID -> channels.filter { it.isFavorite }
            null -> channels.filter { it.group == channel.group }
            else -> accountManager.groups.value
                .find { it.name == lastBrowsedParentId }
                ?.channels
                ?: channels.filter { it.group == channel.group }
        }
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

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(TOGGLE_FAVORITE_COMMAND)
                .add(SEEK_TO_LIVE_COMMAND)
                .add(TOGGLE_LOVED_TRACK_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == TOGGLE_FAVORITE_COMMAND.customAction) {
                val channel = vlcPlayerWrapper.currentChannel.value
                if (channel != null) {
                    serviceScope.launch {
                        accountManager.toggleFavorite(channel)
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == SEEK_TO_LIVE_COMMAND.customAction) {
                vlcPlayerWrapper.seekToLive()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == TOGGLE_LOVED_TRACK_COMMAND.customAction) {
                val channel = vlcPlayerWrapper.currentChannel.value
                if (channel != null) {
                    val track = accountManager.trackMetadata.value[channel.name]
                    if (track != null) {
                        serviceScope.launch {
                            accountManager.toggleLovedTrack(track, channel.name)
                        }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val channel = accountManager.channels.value.find { it.id == item.mediaId }
                if (channel != null) {
                    vlcPlayerWrapper.setChannelList(channelListForContext(channel))
                    vlcPlayerWrapper.play(channel)
                    item.buildUpon()
                        .setUri(channel.streamURL)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(channel.name)
                                .setStation(channel.name)
                                .setArtist(channel.group)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                                .setIsPlayable(true)
                                .apply { channel.logoURL?.let { setArtworkUri(Uri.parse(it)) } }
                                .build()
                        )
                        .build()
                } else {
                    item
                }
            }
            return Futures.immediateFuture(resolved)
        }

        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val lastPlayedId = kotlinx.coroutines.runBlocking {
                persistenceService.loadLastPlayed()
            }
            val channel = lastPlayedId?.let { id ->
                accountManager.channels.value.find { it.id == id }
            }
            if (channel != null) {
                vlcPlayerWrapper.setChannelList(channelListForContext(channel))
            }
            val items = if (channel != null) listOf(buildPlayableItem(channel)) else emptyList()
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, 0, 0L)
            )
        }

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
            val groups = accountManager.groups.value
            val channels = accountManager.channels.value
            val favorites = channels.filter { it.isFavorite }

            if (parentId != ROOT_ID) {
                lastBrowsedParentId = parentId
            }

            val items: List<MediaItem> = when (parentId) {
                ROOT_ID -> {
                    val result = mutableListOf<MediaItem>()
                    if (favorites.isNotEmpty()) {
                        result.add(
                            buildBrowsableItem(
                                FAVORITES_ID,
                                "Favorites",
                                "${favorites.size} channels",
                            )
                        )
                    }
                    result.addAll(groups.map {
                        buildBrowsableItem(
                            it.name,
                            it.name,
                            "${it.channels.size} channels",
                        )
                    })
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
            val results = accountManager.channels.value
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
            val results = accountManager.channels.value
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { buildPlayableItem(it) }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
            )
        }
    }

    private fun buildBrowsableItem(id: String, title: String, subtitle: String? = null): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { if (subtitle != null) setSubtitle(subtitle) }
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
    }

    private fun buildPlayableItem(channel: Channel): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(channel.name)
            .setStation(channel.name)
            .setArtist(channel.group)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)

        channel.logoURL?.let { url ->
            metadataBuilder.setArtworkUri(Uri.parse(url))
        }

        return MediaItem.Builder()
            .setMediaId(channel.id)
            .setUri(channel.streamURL)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}
