@file:OptIn(kotlinx.coroutines.FlowPreview::class)

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
import androidx.media3.session.CommandButton
import com.adagiostream.android.R
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.util.DebugLogger
import com.adagiostream.android.util.DebugLogger.Category.AUTO
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlaybackService : MediaLibraryService() {

    @Inject lateinit var vlcPlayerWrapper: VLCPlayerWrapper
    @Inject lateinit var accountManager: AccountManager
    @Inject lateinit var persistenceService: PersistenceService
    @Inject lateinit var espnScoreService: ESPNScoreService

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastBrowsedParentId: String? = null

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val ROOT_ID = "root"
        private const val FAVORITES_ID = "favorites"
        private const val LOVED_SONGS_ID = "loved_songs"
        private val TOGGLE_FAVORITE_COMMAND = SessionCommand("TOGGLE_FAVORITE", Bundle.EMPTY)
        private val SEEK_TO_LIVE_COMMAND = SessionCommand("SEEK_TO_LIVE", Bundle.EMPTY)
        private val TOGGLE_LOVED_TRACK_COMMAND = SessionCommand("TOGGLE_LOVED_TRACK", Bundle.EMPTY)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        DebugLogger.log("AudioPlaybackService.onCreate()", AUTO)
        createNotificationChannel()
        buildSession()
        DebugLogger.log("MediaLibrarySession built, session=$mediaLibrarySession", AUTO)

        // Reactive browse tree updates (debounced to batch rapid changes during init)
        serviceScope.launch {
            combine(
                accountManager.groups,
                accountManager.channels,
            ) { groups, channels ->
                val favorites = channels.filter { it.isFavorite }
                Triple(groups.size, channels.size, favorites.size)
            }
                .debounce(500L)
                .distinctUntilChanged()
                .collect { (groupCount, channelCount, favCount) ->
                    val rootChildCount = groupCount + if (favCount > 0) 1 else 0
                    val controllers = mediaLibrarySession?.connectedControllers ?: emptyList()
                    DebugLogger.log("Browse tree data changed: $groupCount groups, $channelCount channels, $favCount favorites, notifying ${controllers.size} controller(s)", AUTO)
                    controllers.forEach { controller ->
                        DebugLogger.log("  Notifying controller: pkg=${controller.packageName}, rootChildCount=$rootChildCount, favCount=$favCount", AUTO)
                        mediaLibrarySession?.notifyChildrenChanged(controller, ROOT_ID, rootChildCount, null)
                        if (favCount > 0) {
                            mediaLibrarySession?.notifyChildrenChanged(controller, FAVORITES_ID, favCount, null)
                        }
                    }
                }
        }

        // Reactive browse tree updates for dynamic subtitle data (ESPN scores, SXM tracks, EPG)
        serviceScope.launch {
            combine(
                accountManager.feedMetadata,
                espnScoreService.gamesByChannel,
                accountManager.epgEntries,
            ) { feed, espn, epg -> Triple(feed.size, espn.size, epg.size) }
                .debounce(1000L)
                .distinctUntilChanged()
                .collect { (feedCount, espnCount, epgCount) ->
                    val controllers = mediaLibrarySession?.connectedControllers ?: emptyList()
                    if (controllers.isNotEmpty() && (feedCount > 0 || espnCount > 0 || epgCount > 0)) {
                        DebugLogger.log("Dynamic subtitle data changed: feed=$feedCount, espn=$espnCount, epg=$epgCount — refreshing browse tree", AUTO)
                        val groups = accountManager.groups.value
                        val favorites = accountManager.channels.value.filter { it.isFavorite }
                        controllers.forEach { controller ->
                            groups.forEach { group ->
                                mediaLibrarySession?.notifyChildrenChanged(controller, group.name, group.channels.size, null)
                            }
                            if (favorites.isNotEmpty()) {
                                mediaLibrarySession?.notifyChildrenChanged(controller, FAVORITES_ID, favorites.size, null)
                            }
                        }
                    }
                }
        }

        // Persist last played channel + start XM metadata polling for now-playing display
        serviceScope.launch {
            vlcPlayerWrapper.currentChannel.collect { channel ->
                if (channel != null) {
                    persistenceService.saveLastPlayed(channel.id)
                    accountManager.startTrackMetadataPolling(channel)
                } else {
                    accountManager.stopTrackMetadataPolling()
                }
            }
        }

        // Stop foreground when playback goes idle + update widget
        serviceScope.launch {
            combine(
                vlcPlayerWrapper.playbackState,
                vlcPlayerWrapper.currentChannel,
            ) { state, channel -> state to channel }.collect { (state, channel) ->
                if (state is PlaybackState.Idle) {
                    DebugLogger.log("Playback idle — removing foreground", AUTO)
                    ServiceCompat.stopForeground(this@AudioPlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
                // Update home screen widget
                val statusText = when (state) {
                    is PlaybackState.Playing -> "Playing"
                    is PlaybackState.Paused -> "Paused"
                    is PlaybackState.Buffering -> "Buffering..."
                    is PlaybackState.CatchingUp -> "Catching up..."
                    is PlaybackState.Error -> state.message
                    else -> ""
                }
                com.adagiostream.android.widget.NowPlayingWidgetProvider.updateWidget(
                    this@AudioPlaybackService,
                    channel?.name,
                    statusText,
                    state is PlaybackState.Playing || state is PlaybackState.CatchingUp,
                )
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildSession() {
        DebugLogger.log("buildSession() - creating VLCSessionPlayer and MediaLibrarySession", AUTO)
        val sessionPlayer = VLCSessionPlayer(vlcPlayerWrapper, accountManager, espnScoreService)

        mediaLibrarySession?.release()
        mediaLibrarySession = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .build()
        DebugLogger.log("buildSession() - session created: token=${mediaLibrarySession?.token}", AUTO)
    }

    @OptIn(UnstableApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.log("onStartCommand() - intent=${intent?.action}, flags=$flags, startId=$startId", AUTO)
        super.onStartCommand(intent, flags, startId)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AdagioStream")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, 1001, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        DebugLogger.log("onStartCommand() - foreground service started", AUTO)
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        DebugLogger.log("onGetSession() - controller: pkg=${controllerInfo.packageName}, uid=${controllerInfo.uid}, session=${mediaLibrarySession != null}", AUTO)
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLogger.log("onTaskRemoved()", AUTO)
        vlcPlayerWrapper.stop()
        stopSelf()
    }

    override fun onDestroy() {
        DebugLogger.log("onDestroy() - releasing session and cancelling scope", AUTO)
        serviceScope.cancel()
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
            DebugLogger.log("onConnect() - controller: pkg=${controller.packageName}, uid=${controller.uid}, pid=${controller.controllerVersion}", AUTO)

            // Auto-play startup stream on Android Auto connect if nothing is playing
            if (vlcPlayerWrapper.currentChannel.value == null) {
                serviceScope.launch {
                    accountManager.awaitInitialLoad()
                    val settings = persistenceService.loadSettings()
                    val startupId = settings.startupStreamID
                    if (startupId != null) {
                        val channel = accountManager.channels.value.find { it.id == startupId }
                        if (channel != null) {
                            DebugLogger.log("onConnect() - auto-playing startup channel: ${channel.name}", AUTO)
                            vlcPlayerWrapper.setChannelList(channelListForContext(channel))
                            vlcPlayerWrapper.play(channel)
                        }
                    }
                }
            }
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(TOGGLE_FAVORITE_COMMAND)
                .add(SEEK_TO_LIVE_COMMAND)
                .add(TOGGLE_LOVED_TRACK_COMMAND)
                .build()
            val customButtons = listOf(
                CommandButton.Builder()
                    .setDisplayName("Favorite")
                    .setIconResId(R.drawable.ic_star)
                    .setSessionCommand(TOGGLE_FAVORITE_COMMAND)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName("Love Track")
                    .setIconResId(R.drawable.ic_heart)
                    .setSessionCommand(TOGGLE_LOVED_TRACK_COMMAND)
                    .build(),
            )
            DebugLogger.log("onConnect() - accepting connection with custom commands: TOGGLE_FAVORITE, SEEK_TO_LIVE, TOGGLE_LOVED_TRACK", AUTO)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(customButtons)
                .build()
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            DebugLogger.log("onDisconnected() - controller: pkg=${controller.packageName}, uid=${controller.uid}", AUTO)
            super.onDisconnected(session, controller)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            DebugLogger.log("onCustomCommand() - command=${customCommand.customAction}, from=${controller.packageName}", AUTO)
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
            DebugLogger.log("onAddMediaItems() - ${mediaItems.size} item(s) from ${controller.packageName}", AUTO)
            mediaItems.forEach { DebugLogger.log("  mediaId=${it.mediaId}, uri=${it.localConfiguration?.uri}", AUTO) }
            val future = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                // Wait for channels to load before resolving media items
                accountManager.awaitInitialLoad()
                val resolved = mediaItems.map { item ->
                    val channel = accountManager.channels.value.find { it.id == item.mediaId }
                        // Fallback: match by name if UUID is stale (e.g. AA cached old browse tree)
                        ?: item.mediaMetadata.title?.let { title ->
                            accountManager.channels.value.find { it.name == title.toString() }
                        }
                    if (channel != null) {
                        DebugLogger.log("  Resolved channel: ${channel.name} (group=${channel.group})", AUTO)
                        vlcPlayerWrapper.setChannelList(channelListForContext(channel))
                        vlcPlayerWrapper.play(channel)
                        val subtitle = channelSubtitle(channel)
                        item.buildUpon()
                            .setUri(channel.streamURL)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(channel.name)
                                    .setStation(channel.name)
                                    .setArtist(subtitle)
                                    .setSubtitle(subtitle)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                                    .setIsPlayable(true)
                                    .apply { channel.logoURL?.let { setArtworkUri(Uri.parse(it)) } }
                                    .build()
                            )
                            .build()
                    } else {
                        DebugLogger.log("  WARN: No channel found for mediaId=${item.mediaId}", AUTO)
                        item
                    }
                }
                future.set(resolved)
            }
            return future
        }

        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            DebugLogger.log("onPlaybackResumption() - from ${controller.packageName}", AUTO)
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val lastPlayedId = persistenceService.loadLastPlayed()
                DebugLogger.log("onPlaybackResumption() - lastPlayedId=$lastPlayedId", AUTO)
                val channel = lastPlayedId?.let { id ->
                    accountManager.channels.value.find { it.id == id }
                }
                if (channel != null) {
                    DebugLogger.log("onPlaybackResumption() - resuming: ${channel.name}", AUTO)
                    vlcPlayerWrapper.setChannelList(channelListForContext(channel))
                    vlcPlayerWrapper.play(channel)
                } else {
                    DebugLogger.log("onPlaybackResumption() - no channel found to resume", AUTO)
                }
                val items = if (channel != null) listOf(buildPlayableItem(channel)) else emptyList()
                future.set(MediaSession.MediaItemsWithStartPosition(items, 0, 0L))
            }
            return future
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            DebugLogger.log("onGetLibraryRoot() - browser: pkg=${browser.packageName}, uid=${browser.uid}, params=$params", AUTO)
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
            DebugLogger.log("onGetLibraryRoot() - returning root=$ROOT_ID", AUTO)
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
            DebugLogger.log("onGetChildren() - parentId=$parentId, page=$page, pageSize=$pageSize, browser=${browser.packageName}", AUTO)

            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                // Wait for initial channel load so browse tree isn't empty
                accountManager.awaitInitialLoad()

                val groups = accountManager.groups.value
                val channels = accountManager.channels.value
                val favorites = channels.filter { it.isFavorite }
                DebugLogger.log("onGetChildren() - ${groups.size} groups, ${channels.size} channels, ${favorites.size} favorites", AUTO)

                if (parentId != ROOT_ID) {
                    lastBrowsedParentId = parentId
                }

                val lovedTracks = accountManager.lovedTracks.value

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
                        if (lovedTracks.isNotEmpty()) {
                            result.add(
                                buildBrowsableItem(
                                    LOVED_SONGS_ID,
                                    "Loved Songs",
                                    "${lovedTracks.size} tracks",
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
                    LOVED_SONGS_ID -> lovedTracks.map { track ->
                        MediaItem.Builder()
                            .setMediaId("loved_${track.artist}_${track.title}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(track.title)
                                    .setArtist(track.artist)
                                    .setSubtitle("${track.artist} \u2013 ${track.channelName}")
                                    .setIsPlayable(false)
                                    .setIsBrowsable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .apply { track.albumArtURL?.let { setArtworkUri(Uri.parse(it)) } }
                                    .build()
                            )
                            .build()
                    }
                    else -> {
                        groups.find { it.name == parentId }
                            ?.channels
                            ?.map { buildPlayableItem(it) }
                            ?: emptyList()
                    }
                }

                DebugLogger.log("onGetChildren() - returning ${items.size} items for parentId=$parentId", AUTO)
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            DebugLogger.log("onSearch() - query='$query' from ${browser.packageName}", AUTO)
            val results = accountManager.channels.value
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { buildPlayableItem(it) }

            DebugLogger.log("onSearch() - ${results.size} results", AUTO)
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
            DebugLogger.log("onGetSearchResult() - query='$query', page=$page, pageSize=$pageSize from ${browser.packageName}", AUTO)
            val results = accountManager.channels.value
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { buildPlayableItem(it) }

            DebugLogger.log("onGetSearchResult() - returning ${results.size} results", AUTO)
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

    /**
     * Resolves a dynamic subtitle for a channel: SXM track > ESPN game > EPG program > group name.
     */
    private fun channelSubtitle(channel: Channel): String {
        // SXM feed metadata takes priority
        accountManager.feedMetadata.value[channel.id]?.let { track ->
            return "${track.artist} \u2013 ${track.title}"
        }
        // ESPN game score
        espnScoreService.gamesByChannel.value[channel.id]?.let { game ->
            return game.displayText
        }
        // EPG current program
        channel.epgChannelID?.let { epgId ->
            accountManager.epgEntries.value[epgId]?.firstOrNull { it.isCurrentlyAiring }?.let { entry ->
                return entry.title
            }
        }
        // Default: group name
        return channel.group
    }

    private fun buildPlayableItem(channel: Channel): MediaItem {
        val subtitle = channelSubtitle(channel)
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(channel.name)
            .setStation(channel.name)
            .setArtist(subtitle)
            .setSubtitle(subtitle)
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
