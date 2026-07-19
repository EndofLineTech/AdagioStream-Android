@file:kotlin.OptIn(kotlinx.coroutines.FlowPreview::class)

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
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.CommandButton
import com.adagiostream.android.R
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.AutoSourceOrder
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.ESPNGameInfo
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.library.MusicLibraryRepository
import com.adagiostream.android.service.metadata.ESPNScoreService
import com.adagiostream.android.service.navidrome.Album
import com.adagiostream.android.service.navidrome.AlbumListType
import com.adagiostream.android.service.navidrome.NavidromeApi
import com.adagiostream.android.service.navidrome.NavidromeApiFactory
import com.adagiostream.android.service.navidrome.NavidromePlaylist
import com.adagiostream.android.service.navidrome.Track
import com.adagiostream.android.service.persistence.LastPlayed
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.account.AccountManager
import com.adagiostream.android.service.audiobookshelf.AudiobookPlaybackCoordinator
import com.adagiostream.android.service.playlist.CustomPlaylistManager
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
import kotlinx.coroutines.delay
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
    @Inject lateinit var customPlaylistManager: CustomPlaylistManager
    @Inject lateinit var musicPlaybackCoordinator: MusicPlaybackCoordinator
    @Inject lateinit var musicLibraryRepository: MusicLibraryRepository
    @Inject lateinit var navidromeApiFactory: NavidromeApiFactory
    @Inject lateinit var audiobookPlaybackCoordinator: AudiobookPlaybackCoordinator

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastBrowsedParentId: String? = null
    private var customButtonJob: kotlinx.coroutines.Job? = null

    // ---- Navidrome / music browse state (baw.7.1) -------------------------

    /**
     * Resolved [NavidromeApi] for the currently-configured Subsonic account, or
     * null when no Subsonic account exists. Updated reactively in [onCreate].
     *
     * Reads from [AccountManager.accounts] (never blocks the Auto callback).
     */
    private var subsonicApi: NavidromeApi? = null

    /**
     * In-memory playlist cache (baw.7.1). Populated by a background coroutine
     * whenever the Subsonic API changes; served immediately from memory in
     * [onGetChildren] so the Auto callback never blocks on the network.
     */
    private val cachedMusicPlaylists = mutableListOf<NavidromePlaylist>()

    /**
     * Per-playlist track cache keyed by playlist ID (baw.7.1). Populated on
     * demand when the user first opens a playlist; empty list is returned until
     * the background fetch completes, then [notifyChildrenChanged] is fired.
     */
    private val cachedMusicPlaylistTracks = mutableMapOf<String, List<Track>>()

    /**
     * In-memory "newest 50 albums" cache for the [MusicAutoMediaMapper.MUSIC_ALBUMS_ID]
     * root node (baw.7.1, iOS parity). Refreshed alongside the playlist cache.
     */
    private val cachedMusicAlbums = mutableListOf<Album>()

    /**
     * In-memory "random 100 songs" cache for the [MusicAutoMediaMapper.MUSIC_SONGS_ID]
     * root node (baw.7.1, iOS parity: random 100).
     */
    private val cachedRandomSongs = mutableListOf<Track>()

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val ROOT_ID = "root"
        private const val FAVORITES_ID = "favorites"
        private const val LOVED_SONGS_ID = "loved_songs"
        private const val CUSTOM_PLAYLIST_PREFIX = "custom_playlist_"
        private const val CUSTOM_GROUP_PREFIX = "custom_group_"
        private val TOGGLE_FAVORITE_COMMAND = SessionCommand("TOGGLE_FAVORITE", Bundle.EMPTY)
        private val SEEK_TO_LIVE_COMMAND = SessionCommand("SEEK_TO_LIVE", Bundle.EMPTY)
        private val TOGGLE_LOVED_TRACK_COMMAND = SessionCommand("TOGGLE_LOVED_TRACK", Bundle.EMPTY)
        private val TOGGLE_SHUFFLE_COMMAND = SessionCommand("TOGGLE_SHUFFLE", Bundle.EMPTY)
        private val CYCLE_REPEAT_COMMAND = SessionCommand("CYCLE_REPEAT", Bundle.EMPTY)
        private val CYCLE_SPEED_COMMAND = SessionCommand("CYCLE_SPEED", Bundle.EMPTY)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        DebugLogger.log("AudioPlaybackService.onCreate()", AUTO)
        createNotificationChannel()
        // Auto-advance seam (baw.3.3): when a library track ends naturally, the
        // wrapper fires this; the coordinator advances the queue (honouring
        // RepeatMode.One restart) and starts the next track.
        vlcPlayerWrapper.onLibraryTrackEnded = { musicPlaybackCoordinator.onTrackEnded() }
        // Audiobook file chaining (beads_adagio-59p.1.5): when the active FILE of a
        // multi-file book ends, the coordinator loads the next file on the timeline.
        vlcPlayerWrapper.onAudiobookFileEnded = { audiobookPlaybackCoordinator.onFileEnded() }
        buildSession()
        DebugLogger.log("MediaLibrarySession built, session=$mediaLibrarySession", AUTO)

        // Reactive browse tree updates (debounced to batch rapid changes during init)
        serviceScope.launch {
            combine(
                accountManager.groups,
                accountManager.channels,
                customPlaylistManager.playlists,
            ) { groups, channels, playlists ->
                val favorites = channels.filter { it.isFavorite }
                Triple(groups.size + playlists.size, channels.size, favorites.size)
            }
                .debounce(500L)
                .distinctUntilChanged()
                .collect { (totalGroups, channelCount, favCount) ->
                    val rootChildCount = currentRootChildCount()
                    val controllers = mediaLibrarySession?.connectedControllers ?: emptyList()
                    DebugLogger.log("Browse tree data changed: $totalGroups groups, $channelCount channels, $favCount favorites, notifying ${controllers.size} controller(s)", AUTO)
                    controllers.forEach { controller ->
                        DebugLogger.log("  Notifying controller: pkg=${controller.packageName}, rootChildCount=$rootChildCount, favCount=$favCount", AUTO)
                        mediaLibrarySession?.notifyChildrenChanged(controller, ROOT_ID, rootChildCount, null)
                        mediaLibrarySession?.notifyChildrenChanged(controller, FAVORITES_ID, favCount, null)
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
                    persistenceService.saveLastPlayed(LastPlayed.Channel(channel.id))
                    accountManager.startTrackMetadataPolling(channel)
                } else {
                    accountManager.stopTrackMetadataPolling()
                }
            }
        }

        // Persist last played library track + position (baw.10): mirrors the
        // channel save above so onPlaybackResumption can rebuild library
        // playback (queue + track + position), not just channels.
        //
        // Write strategy (baw.14 review): the full queue is serialized ONCE per
        // queue (identity change — MusicQueueManager keeps the same List across
        // track advances); every other trigger (track advance, pause, the 10s
        // tick) only rewrites the tiny position sidecar. Emissions are deduped
        // on source identity + state class — never full Track-list equality —
        // and the writes themselves run on Dispatchers.IO inside
        // PersistenceService, off this Main-dispatcher scope.
        serviceScope.launch {
            var fullySavedQueue: List<Track>? = null
            combine(
                vlcPlayerWrapper.playbackSource,
                vlcPlayerWrapper.playbackState,
            ) { source, state -> source to state }
                .distinctUntilChanged { (oldSource, oldState), (newSource, newState) ->
                    oldSource === newSource && oldState::class == newState::class
                }
                .collect { (source, _) ->
                    if (source !is PlaybackSource.Library) return@collect
                    if (source.queue !== fullySavedQueue) {
                        fullySavedQueue = source.queue
                        saveLibraryLastPlayed(source)
                    } else {
                        persistenceService.saveLastPlayedPosition(source.index, vlcPlayerWrapper.currentPositionMs())
                    }
                }
        }
        serviceScope.launch {
            while (true) {
                delay(10_000L)
                val source = vlcPlayerWrapper.playbackSource.value
                if (source is PlaybackSource.Library && vlcPlayerWrapper.playbackState.value is PlaybackState.Playing) {
                    persistenceService.saveLastPlayedPosition(source.index, vlcPlayerWrapper.currentPositionMs())
                }
            }
        }

        // Watch for Subsonic account changes → resolve API, pre-fetch playlists (baw.7.1)
        serviceScope.launch {
            accountManager.accounts.collect { accounts ->
                val subsonic = accounts
                    .filter { it.isEnabled }
                    .firstNotNullOfOrNull { it.type as? AccountType.Subsonic }
                val newApi = subsonic?.let { s ->
                    navidromeApiFactory.create(s.host, s.username, s.password)
                }
                val apiChanged = newApi?.let { true } ?: (subsonicApi != null)
                subsonicApi = newApi
                if (apiChanged) {
                    // Notify Root children so the Music node appears/disappears
                    val controllers = mediaLibrarySession?.connectedControllers ?: emptyList()
                    val rootChildCount = currentRootChildCount()
                    controllers.forEach { controller ->
                        mediaLibrarySession?.notifyChildrenChanged(controller, ROOT_ID, rootChildCount, null)
                    }
                }
                // Background-fetch playlists/albums/songs for the in-memory caches
                // (non-blocking for Auto — see prefetchMusicList()).
                if (newApi != null) {
                    prefetchMusicList(MusicAutoMediaMapper.MUSIC_PLAYLISTS_ID, cachedMusicPlaylists, "playlist") {
                        newApi.getPlaylists()
                    }
                    prefetchMusicList(MusicAutoMediaMapper.MUSIC_ALBUMS_ID, cachedMusicAlbums, "album") {
                        newApi.getAlbumList2(type = AlbumListType.NEWEST, size = 50)
                    }
                    prefetchMusicList(MusicAutoMediaMapper.MUSIC_SONGS_ID, cachedRandomSongs, "song") {
                        newApi.getRandomSongs(size = 100)
                    }
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
        val sessionPlayer = VLCSessionPlayer(
            vlcPlayerWrapper,
            musicPlaybackCoordinator,
            accountManager,
            espnScoreService,
            audiobookPlaybackCoordinator,
        )

        mediaLibrarySession?.release()
        mediaLibrarySession = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .build()
        DebugLogger.log("buildSession() - session created: token=${mediaLibrarySession?.token}", AUTO)

        // Use our pre-created "playback" channel + ID so Media3's MediaStyle notification
        // replaces the placeholder posted in onStartCommand (instead of racing on a different channel).
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .setNotificationId(NOTIFICATION_ID)
                .build()
        )

        // Reactively update custom button icons: favorite/loved for radio, or
        // shuffle/repeat for library playback (baw.7.2 — buttons branch by source).
        customButtonJob?.cancel()
        customButtonJob = serviceScope.launch {
            combine(
                vlcPlayerWrapper.currentChannel,
                accountManager.channels,
                accountManager.trackMetadata,
                accountManager.lovedTracks,
                vlcPlayerWrapper.playbackSource,
            ) { current, channels, trackMeta, lovedTracks, source ->
                val isFav = current != null && channels.find { it.id == current.id }?.isFavorite == true
                val track = current?.let { trackMeta[it.name] }
                val isLoved = track != null && lovedTracks.any { it.artist == track.artist && it.title == track.title }
                Triple(source, isFav, isLoved)
            }
                // Fold in shuffle/repeat/speed so the layout also refreshes when
                // any changes on its own — e.g. toggled from a controller other
                // than the one the layout is being drawn for (baw.13 MINOR-3: the
                // layout previously only updated on channel/track/source changes,
                // so non-tapping controllers could show a stale shuffle/repeat icon).
                .combine(
                    combine(
                        musicPlaybackCoordinator.shuffleEnabledFlow,
                        musicPlaybackCoordinator.repeatModeFlow,
                        audiobookPlaybackCoordinator.speedFlow,
                    ) { shuffle, repeat, speed -> Triple(shuffle, repeat, speed) }
                ) { sourceState, modeState -> sourceState to modeState }
                .distinctUntilChanged()
                .collect { (sourceState, modeState) ->
                    val (source, isFav, isLoved) = sourceState
                    val (shuffleEnabled, repeatMode, speed) = modeState
                    val buttons = when (source) {
                        is PlaybackSource.Audiobook -> buildAudiobookCustomButtons(speed)
                        is PlaybackSource.Library -> buildLibraryCustomButtons(shuffleEnabled, repeatMode)
                        else -> buildCustomButtons(isFav, isLoved)
                    }
                    mediaLibrarySession?.connectedControllers?.forEach { controller ->
                        mediaLibrarySession?.setCustomLayout(controller, buttons)
                    }
                }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildCustomButtons(isFavorite: Boolean, isLoved: Boolean): List<CommandButton> {
        return listOf(
            CommandButton.Builder(if (isFavorite) CommandButton.ICON_STAR_FILLED else CommandButton.ICON_STAR_UNFILLED)
                .setDisplayName("Favorite")
                .setSessionCommand(TOGGLE_FAVORITE_COMMAND)
                .build(),
            CommandButton.Builder(if (isLoved) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName("Love Track")
                .setSessionCommand(TOGGLE_LOVED_TRACK_COMMAND)
                .build(),
        )
    }

    /**
     * Custom button row for library (Navidrome) playback (baw.7.2) — shuffle
     * toggle + repeat-mode cycle, replacing the radio-only favorite/love row
     * (those act on [vlcPlayerWrapper.currentChannel], which is null for
     * on-demand tracks). Auto also natively exposes shuffle/repeat toggles via
     * the standard `COMMAND_SET_SHUFFLE_MODE`/`COMMAND_SET_REPEAT_MODE` player
     * commands (see [VLCSessionPlayer.availableCommands]); this row is a visible
     * shortcut to the same state, refreshed by [onCustomCommand] on tap.
     */
    @OptIn(UnstableApi::class)
    private fun buildLibraryCustomButtons(shuffleEnabled: Boolean, repeatMode: RepeatMode): List<CommandButton> {
        val repeatIcon = when (repeatMode) {
            RepeatMode.Off -> CommandButton.ICON_REPEAT_OFF
            RepeatMode.All -> CommandButton.ICON_REPEAT_ALL
            RepeatMode.One -> CommandButton.ICON_REPEAT_ONE
        }
        return listOf(
            CommandButton.Builder(if (shuffleEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName("Shuffle")
                .setSessionCommand(TOGGLE_SHUFFLE_COMMAND)
                .build(),
            CommandButton.Builder(repeatIcon)
                .setDisplayName("Repeat")
                .setSessionCommand(CYCLE_REPEAT_COMMAND)
                .build(),
        )
    }

    /**
     * Custom button row for audiobook playback (beads_adagio-59p.1.5): a
     * single speed-cycle button showing the active rate. Next/prev transport
     * buttons already exist natively and route to chapter skip via
     * [VLCSessionPlayer.handleSeek].
     */
    @OptIn(UnstableApi::class)
    private fun buildAudiobookCustomButtons(speed: Float): List<CommandButton> {
        val label = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
        return listOf(
            CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
                .setDisplayName("Speed $label")
                .setSessionCommand(CYCLE_SPEED_COMMAND)
                .build(),
        )
    }

    /**
     * Rebuilds and re-pushes the library custom layout for [controller] right
     * after a shuffle/repeat tap (baw.7.2) so the icon reflects the new state
     * immediately, without waiting for the next reactive [customButtonJob] tick.
     */
    @OptIn(UnstableApi::class)
    private fun refreshLibraryCustomLayout(controller: MediaSession.ControllerInfo) {
        val buttons = buildLibraryCustomButtons(musicPlaybackCoordinator.shuffleEnabled, musicPlaybackCoordinator.repeatMode)
        mediaLibrarySession?.setCustomLayout(controller, buttons)
    }

    @OptIn(UnstableApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.log("onStartCommand() - intent=${intent?.action}, flags=$flags, startId=$startId", AUTO)
        super.onStartCommand(intent, flags, startId)

        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AdagioStream")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        // Attach the MediaSession token so the system Media Controls tile + lock-screen
        // surface can bind to us immediately, before Media3 posts its full notification.
        mediaLibrarySession?.let { session ->
            notificationBuilder.setStyle(MediaStyleNotificationHelper.MediaStyle(session))
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notificationBuilder.build(),
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

    /**
     * Root browse child count, computed the same way [onGetChildren]'s ROOT_ID
     * branch builds the list: Favorites (always shown) + non-empty custom
     * playlists + IPTV groups + the Music node when a Subsonic account is
     * configured (baw.13 MINOR-5). Shared by every `notifyChildrenChanged(ROOT_ID, ...)`
     * call site so the reported count is never a stale/placeholder guess.
     */
    private fun currentRootChildCount(): Int {
        val hasSubsonic = accountManager.accounts.value.any { it.isEnabled && it.type is AccountType.Subsonic }
        val nonEmptyPlaylistCount = customPlaylistManager.playlists.value
            .count { playlist -> playlist.groups.sumOf { it.entries.size } > 0 }
        val groupCount = accountManager.groups.value.size
        return 1 + nonEmptyPlaylistCount + groupCount + (if (hasSubsonic) 1 else 0)
    }

    private fun channelListForContext(channel: Channel): List<Channel> {
        val channels = accountManager.channels.value
        val result = when {
            lastBrowsedParentId == FAVORITES_ID -> channels.filter { it.isFavorite }
            lastBrowsedParentId == null -> channels.filter { it.group == channel.group }
            lastBrowsedParentId!!.startsWith(CUSTOM_PLAYLIST_PREFIX) -> {
                val playlistId = lastBrowsedParentId!!.removePrefix(CUSTOM_PLAYLIST_PREFIX)
                customPlaylistManager.playlists.value
                    .find { it.id == playlistId }
                    ?.groups?.flatMap { g -> g.entries.map { it.asChannel() } }
                    ?: listOf(channel)
            }
            lastBrowsedParentId!!.startsWith(CUSTOM_GROUP_PREFIX) -> {
                val groupId = lastBrowsedParentId!!.removePrefix(CUSTOM_GROUP_PREFIX)
                customPlaylistManager.playlists.value
                    .flatMap { it.groups }
                    .find { it.id == groupId }
                    ?.entries?.map { it.asChannel() }
                    ?: listOf(channel)
            }
            else -> {
                val browseGroup = accountManager.groups.value
                    .find { it.name == lastBrowsedParentId }
                // Only use the browse group if the channel is actually in it;
                // AA pre-fetches multiple groups in parallel, overwriting
                // lastBrowsedParentId even when the user tapped from favorites.
                if (browseGroup != null && browseGroup.channels.any { it.id == channel.id }) {
                    browseGroup.channels
                } else if (channel.isFavorite) {
                    channels.filter { it.isFavorite }
                } else {
                    // Check if channel is from a custom playlist
                    val customPlaylist = customPlaylistManager.playlists.value.find { playlist ->
                        playlist.groups.any { g -> g.entries.any { it.id == channel.id } }
                    }
                    if (customPlaylist != null) {
                        customPlaylist.groups.flatMap { g -> g.entries.map { it.asChannel() } }
                    } else {
                        channels.filter { it.group == channel.group }
                    }
                }
            }
        }
        DebugLogger.log("channelListForContext() - lastBrowsedParentId=$lastBrowsedParentId, channel=${channel.name}, resultSize=${result.size}", AUTO)
        return result
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
                .add(TOGGLE_SHUFFLE_COMMAND)
                .add(CYCLE_REPEAT_COMMAND)
                .add(CYCLE_SPEED_COMMAND)
                .build()
            // Custom layout branches by playback source (baw.7.2): library
            // playback gets shuffle/repeat, audiobooks get speed, radio keeps
            // favorite/love.
            val customButtons = if (vlcPlayerWrapper.playbackSource.value is PlaybackSource.Audiobook) {
                buildAudiobookCustomButtons(audiobookPlaybackCoordinator.speed)
            } else if (vlcPlayerWrapper.playbackSource.value is PlaybackSource.Library) {
                buildLibraryCustomButtons(musicPlaybackCoordinator.shuffleEnabled, musicPlaybackCoordinator.repeatMode)
            } else {
                val channel = vlcPlayerWrapper.currentChannel.value
                val isFav = channel != null && accountManager.channels.value.find { it.id == channel.id }?.isFavorite == true
                val track = channel?.let { accountManager.trackMetadata.value[it.name] }
                val isLoved = track != null && accountManager.lovedTracks.value.any { it.artist == track.artist && it.title == track.title }
                buildCustomButtons(isFav, isLoved)
            }
            DebugLogger.log("onConnect() - accepting connection with custom commands: TOGGLE_FAVORITE, SEEK_TO_LIVE, TOGGLE_LOVED_TRACK, TOGGLE_SHUFFLE, CYCLE_REPEAT", AUTO)
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
            // Library-mode custom buttons (baw.7.2) — no-op outside library playback,
            // mirroring the currentChannel == null guards above for the radio buttons.
            if (customCommand.customAction == TOGGLE_SHUFFLE_COMMAND.customAction) {
                if (vlcPlayerWrapper.playbackSource.value is PlaybackSource.Library) {
                    musicPlaybackCoordinator.setShuffle(!musicPlaybackCoordinator.shuffleEnabled)
                    refreshLibraryCustomLayout(controller)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CYCLE_REPEAT_COMMAND.customAction) {
                if (vlcPlayerWrapper.playbackSource.value is PlaybackSource.Library) {
                    musicPlaybackCoordinator.setRepeatMode(musicPlaybackCoordinator.repeatMode.next)
                    refreshLibraryCustomLayout(controller)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            // Audiobook speed cycle (beads_adagio-59p.1.5) — no-op outside
            // audiobook playback, mirroring the library-button guards above.
            if (customCommand.customAction == CYCLE_SPEED_COMMAND.customAction) {
                if (vlcPlayerWrapper.playbackSource.value is PlaybackSource.Audiobook) {
                    audiobookPlaybackCoordinator.cycleSpeed()
                    mediaLibrarySession?.setCustomLayout(
                        controller,
                        buildAudiobookCustomButtons(audiobookPlaybackCoordinator.speed),
                    )
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
            mediaItems.forEach { DebugLogger.log("  mediaId=${it.mediaId}, title=${it.mediaMetadata.title}, uri=${it.localConfiguration?.uri}", AUTO) }

            // Music library track tap (baw.7.1) — always a single tapped item;
            // routed separately from the IPTV channel-resolution path below since
            // the queue comes from musicPlaybackCoordinator, not vlcPlayerWrapper directly.
            val tappedItem = mediaItems.singleOrNull()
            if (tappedItem != null && tappedItem.mediaId.startsWith(MusicAutoMediaMapper.MUSIC_TRACK_PREFIX)) {
                val future = SettableFuture.create<List<MediaItem>>()
                serviceScope.launch {
                    future.set(listOf(handleMusicTrackTap(tappedItem)))
                }
                return future
            }

            val future = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                // Wait for channels to load before resolving media items
                accountManager.awaitInitialLoad()
                val allChannels = accountManager.channels.value
                DebugLogger.log("  Channel pool: ${allChannels.size} channels loaded", AUTO)
                val customChannels = customPlaylistManager.playlists.value
                    .flatMap { it.groups }
                    .flatMap { it.entries }
                    .map { it.asChannel() }
                val allSearchable = allChannels + customChannels
                val resolved = mediaItems.map { item ->
                    val byId = allSearchable.find { it.id == item.mediaId }
                    val byName = if (byId == null) {
                        item.mediaMetadata.title?.let { title ->
                            allSearchable.find { it.name == title.toString() }
                        }
                    } else null
                    val channel = byId ?: byName
                    if (byId != null) {
                        DebugLogger.log("  Matched by ID: ${channel!!.name} (id=${channel.id})", AUTO)
                    } else if (byName != null) {
                        DebugLogger.log("  Matched by NAME fallback: ${channel!!.name} (id=${channel.id}, requestedId=${item.mediaId})", AUTO)
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

        @Deprecated("Deprecated in Media3", ReplaceWith("onPlaybackResumption(mediaSession, controller)"))
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            DebugLogger.log("onPlaybackResumption() - from ${controller.packageName}", AUTO)
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                // baw.14: an unset SettableFuture hangs the requesting controller
                // (lock screen / BT / Auto) forever — ANY throw in here must
                // still resolve the future, degrading to "nothing to resume".
                try {
                    val lastPlayed = persistenceService.loadLastPlayed()
                    DebugLogger.log("onPlaybackResumption() - lastPlayed=$lastPlayed", AUTO)
                    // baw.10: route on WHAT was last playing, not channels only — a
                    // paused library track must resume itself, not the last radio
                    // station. resolveSubsonicApiNow() (not the reactive `subsonicApi`
                    // field) so a cold process resuming before that flow's first
                    // emission still resolves the account.
                    val api = resolveSubsonicApiNow()
                    val plan = ResumptionPolicy.resolve(
                        lastPlayed = lastPlayed,
                        channels = accountManager.channels.value,
                        hasSubsonicApi = api != null,
                    )
                    val items = when (plan) {
                        is ResumptionPolicy.Plan.ResumeChannel -> {
                            DebugLogger.log("onPlaybackResumption() - resuming channel: ${plan.channel.name}", AUTO)
                            vlcPlayerWrapper.setChannelList(channelListForContext(plan.channel))
                            vlcPlayerWrapper.play(plan.channel)
                            listOf(buildPlayableItem(plan.channel))
                        }
                        is ResumptionPolicy.Plan.ResumeLibraryTrack -> {
                            val track = plan.queue[plan.index]
                            DebugLogger.log("onPlaybackResumption() - resuming library track: ${track.title} at ${plan.positionMs}ms", AUTO)
                            musicPlaybackCoordinator.playAlbum(
                                tracks = plan.queue,
                                startIndex = plan.index,
                                api = api!!, // hasSubsonicApi=true guarantees this in the policy
                                albumTitle = plan.albumTitle,
                                startPositionMs = plan.positionMs,
                            )
                            listOf(buildLibraryPlayableItem(track, plan.albumTitle))
                        }
                        ResumptionPolicy.Plan.NothingToResume -> {
                            DebugLogger.log("onPlaybackResumption() - nothing to resume", AUTO)
                            emptyList()
                        }
                    }
                    val startPositionMs = (plan as? ResumptionPolicy.Plan.ResumeLibraryTrack)?.positionMs ?: 0L
                    future.set(MediaSession.MediaItemsWithStartPosition(items, 0, startPositionMs))
                } catch (e: Exception) {
                    DebugLogger.log("onPlaybackResumption() - FAILED: ${e.message}", AUTO)
                    future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                }
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
                        // Root sectioning (baw.7.1, iOS parity): "Channels" (streaming)
                        // and "Music" are built as separate sections, then ordered by
                        // the user's AutoSourceOrder setting. Re-check the Subsonic
                        // account snapshot here (not the subsonicApi field) so the root
                        // list is correct even if the reactive watcher hasn't fired yet.
                        val hasSubsonic = accountManager.accounts.value.any { acct ->
                            acct.isEnabled && acct.type is AccountType.Subsonic
                        }
                        val musicItems = if (hasSubsonic) {
                            listOf(MusicAutoMediaMapper.musicRootItem())
                        } else {
                            emptyList()
                        }

                        val channelItems = mutableListOf<MediaItem>()
                        channelItems.add(
                            buildBrowsableItem(
                                FAVORITES_ID,
                                "Favorites",
                                if (favorites.isNotEmpty()) "${favorites.size} channels" else "No favorites yet",
                            )
                        )
                        // Loved Songs intentionally excluded from AA browse tree —
                        // it's a reference list, not playable channels.
                        // Custom playlists
                        val playlists = customPlaylistManager.playlists.value
                        playlists.forEach { playlist ->
                            val entryCount = playlist.groups.sumOf { it.entries.size }
                            if (entryCount > 0) {
                                channelItems.add(
                                    buildBrowsableItem(
                                        "$CUSTOM_PLAYLIST_PREFIX${playlist.id}",
                                        playlist.name,
                                        "$entryCount streams",
                                    )
                                )
                            }
                        }
                        channelItems.addAll(groups.map {
                            buildBrowsableItem(
                                it.name,
                                it.name,
                                "${it.channels.size} channels",
                            )
                        })

                        val order = if (hasSubsonic) {
                            persistenceService.loadSettings().autoSourceOrder
                        } else {
                            AutoSourceOrder.STREAMING_FIRST
                        }
                        if (order == AutoSourceOrder.MUSIC_FIRST) musicItems + channelItems else channelItems + musicItems
                    }
                    FAVORITES_ID -> {
                        favorites.forEach { DebugLogger.log("  Favorite: id=${it.id}, name=${it.name}", AUTO) }
                        favorites.map { buildPlayableItem(it) }
                    }
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
                    else -> when {
                        // ---- Music browse tree (baw.7.1) — all music_* IDs are handled
                        // here so they never fall through to the IPTV group-name lookup.
                        MusicAutoMediaMapper.isMusicId(parentId) ->
                            handleMusicChildren(parentId)

                        parentId.startsWith(CUSTOM_PLAYLIST_PREFIX) -> {
                            val playlistId = parentId.removePrefix(CUSTOM_PLAYLIST_PREFIX)
                            val playlist = customPlaylistManager.playlists.value.find { it.id == playlistId }
                            if (playlist != null && playlist.groups.size == 1) {
                                // Single group: show entries directly
                                playlist.groups.first().entries.map { buildPlayableItem(it.asChannel()) }
                            } else {
                                playlist?.groups?.filter { it.entries.isNotEmpty() }?.map { group ->
                                    buildBrowsableItem(
                                        "$CUSTOM_GROUP_PREFIX${group.id}",
                                        group.name,
                                        "${group.entries.size} streams",
                                    )
                                } ?: emptyList()
                            }
                        }
                        parentId.startsWith(CUSTOM_GROUP_PREFIX) -> {
                            val groupId = parentId.removePrefix(CUSTOM_GROUP_PREFIX)
                            customPlaylistManager.playlists.value
                                .flatMap { it.groups }
                                .find { it.id == groupId }
                                ?.entries
                                ?.map { buildPlayableItem(it.asChannel()) }
                                ?: emptyList()
                        }
                        else -> {
                            groups.find { it.name == parentId }
                                ?.channels
                                ?.map { buildPlayableItem(it) }
                                ?: emptyList()
                        }
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
            val customChannels = customPlaylistManager.playlists.value
                .flatMap { it.groups }
                .flatMap { it.entries }
                .map { it.asChannel() }
            val results = (accountManager.channels.value + customChannels)
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
            val customChannels = customPlaylistManager.playlists.value
                .flatMap { it.groups }
                .flatMap { it.entries }
                .map { it.asChannel() }
            val results = (accountManager.channels.value + customChannels)
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { buildPlayableItem(it) }

            DebugLogger.log("onGetSearchResult() - returning ${results.size} results", AUTO)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
            )
        }
    }

    // =========================================================================
    // Music browse helpers (baw.7.1)
    // =========================================================================

    /**
     * Routes a music_* [parentId] to the appropriate cache-backed children list.
     *
     * **No blocking network calls here.** All data comes from the Room cache
     * ([musicLibraryRepository]) or the in-memory playlist cache ([cachedMusicPlaylists]).
     * If the cache is empty, a valid empty list is returned — Auto will show an
     * empty section rather than an ANR or crash. When background fetches complete
     * they call [MediaLibrarySession.notifyChildrenChanged] to trigger a refresh.
     */
    private suspend fun handleMusicChildren(parentId: String): List<MediaItem> {
        DebugLogger.log("handleMusicChildren() parentId=$parentId", AUTO)
        return when {
            parentId == MusicAutoMediaMapper.MUSIC_ROOT_ID ->
                MusicAutoMediaMapper.musicRootChildren()

            parentId == MusicAutoMediaMapper.MUSIC_ARTISTS_ID -> {
                val artists = musicLibraryRepository.cachedArtists()
                DebugLogger.log("handleMusicChildren() ARTISTS: ${artists.size} cached", AUTO)
                MusicAutoMediaMapper.artistsItems(artists, ::coverArtUri)
            }

            parentId == MusicAutoMediaMapper.MUSIC_ALBUMS_ID -> {
                DebugLogger.log("handleMusicChildren() ALBUMS: ${cachedMusicAlbums.size} cached", AUTO)
                MusicAutoMediaMapper.albumsItems(cachedMusicAlbums.toList(), ::coverArtUri)
            }

            parentId == MusicAutoMediaMapper.MUSIC_SONGS_ID -> {
                DebugLogger.log("handleMusicChildren() SONGS: ${cachedRandomSongs.size} cached", AUTO)
                MusicAutoMediaMapper.tracksItems(cachedRandomSongs.toList(), ::coverArtUri)
            }

            parentId == MusicAutoMediaMapper.MUSIC_PLAYLISTS_ID -> {
                DebugLogger.log("handleMusicChildren() PLAYLISTS: ${cachedMusicPlaylists.size} cached", AUTO)
                MusicAutoMediaMapper.playlistsItems(cachedMusicPlaylists.toList(), ::coverArtUri)
            }

            parentId.startsWith(MusicAutoMediaMapper.MUSIC_ARTIST_PREFIX) -> {
                val artistId = MusicAutoMediaMapper.artistIdFrom(parentId)
                val albums = musicLibraryRepository.cachedAlbumsForArtist(artistId)
                DebugLogger.log("handleMusicChildren() ARTIST=$artistId: ${albums.size} albums cached", AUTO)
                MusicAutoMediaMapper.albumsItems(albums, ::coverArtUri)
            }

            parentId.startsWith(MusicAutoMediaMapper.MUSIC_ALBUM_PREFIX) -> {
                val albumId = MusicAutoMediaMapper.albumIdFrom(parentId)
                val tracks = musicLibraryRepository.cachedTracksForAlbum(albumId)
                DebugLogger.log("handleMusicChildren() ALBUM=$albumId: ${tracks.size} tracks cached", AUTO)
                MusicAutoMediaMapper.tracksItems(tracks, ::coverArtUri)
            }

            parentId.startsWith(MusicAutoMediaMapper.MUSIC_PLAYLIST_PREFIX) -> {
                val playlistId = MusicAutoMediaMapper.playlistIdFrom(parentId)
                val cached = cachedMusicPlaylistTracks[playlistId]
                DebugLogger.log("handleMusicChildren() PLAYLIST=$playlistId: ${cached?.size ?: "uncached"}", AUTO)
                if (cached != null) {
                    MusicAutoMediaMapper.tracksItems(cached, ::coverArtUri)
                } else {
                    // Cache miss: fire background fetch and return empty now
                    val api = subsonicApi
                    if (api != null) {
                        serviceScope.launch {
                            try {
                                val (_, tracks) = api.getPlaylist(playlistId)
                                cachedMusicPlaylistTracks[playlistId] = tracks
                                val controllers = mediaLibrarySession?.connectedControllers ?: emptyList()
                                controllers.forEach { controller ->
                                    mediaLibrarySession?.notifyChildrenChanged(
                                        controller, parentId, tracks.size, null,
                                    )
                                }
                            } catch (e: Exception) {
                                DebugLogger.log("Music playlist track fetch failed [$playlistId]: ${e.message}", AUTO)
                            }
                        }
                    }
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    /**
     * Resolves and plays a tapped `music_track_*` [item] (baw.7.1 — "tapping a
     * track sets the queue from its context and plays", iOS parity). The queue
     * is built from [lastBrowsedParentId] — the browse node the user was inside
     * when they tapped (album / playlist / the Songs root) — mirroring
     * [channelListForContext] for the IPTV path. Falls back to a single-track
     * queue when the context can't be resolved (e.g. cold navigation before the
     * relevant cache is populated).
     */
    private suspend fun handleMusicTrackTap(item: MediaItem): MediaItem {
        val trackId = MusicAutoMediaMapper.trackIdFrom(item.mediaId)
        DebugLogger.log("handleMusicTrackTap() trackId=$trackId, lastBrowsedParentId=$lastBrowsedParentId", AUTO)

        val (contextTracks, albumTitle) = when {
            lastBrowsedParentId?.startsWith(MusicAutoMediaMapper.MUSIC_ALBUM_PREFIX) == true -> {
                val albumId = MusicAutoMediaMapper.albumIdFrom(lastBrowsedParentId!!)
                musicLibraryRepository.cachedTracksForAlbum(albumId) to
                    musicLibraryRepository.cachedAlbum(albumId)?.title
            }
            lastBrowsedParentId?.startsWith(MusicAutoMediaMapper.MUSIC_PLAYLIST_PREFIX) == true -> {
                val playlistId = MusicAutoMediaMapper.playlistIdFrom(lastBrowsedParentId!!)
                (cachedMusicPlaylistTracks[playlistId] ?: emptyList()) to
                    cachedMusicPlaylists.find { it.id == playlistId }?.name
            }
            lastBrowsedParentId == MusicAutoMediaMapper.MUSIC_SONGS_ID ->
                cachedRandomSongs.toList() to "Songs"
            else -> emptyList<Track>() to null
        }

        // Only trust contextTracks when the tapped track is actually a member of
        // it — Auto pre-fetches browse nodes in parallel, so lastBrowsedParentId
        // (and the context resolved from it above) can be stale/racy by the time
        // the tap resolves (baw.13 MAJOR-1). Falling back to index 0 of the wrong
        // context would silently play the wrong track; fall through to a
        // single-track queue instead, same as when the context is empty.
        val contextIndex = MusicAutoMediaMapper.trackIndexInContext(contextTracks, trackId)
        val (queueTracks, startIndex, resolvedAlbumTitle) = if (contextIndex != null) {
            Triple(contextTracks, contextIndex, albumTitle)
        } else {
            val fallback = musicLibraryRepository.cachedTrack(trackId)?.let { listOf(it) } ?: emptyList()
            Triple(fallback, 0, null as String?)
        }
        val api = resolveSubsonicApiNow()
        if (api == null || queueTracks.isEmpty()) {
            DebugLogger.log("handleMusicTrackTap() WARN: no Subsonic api or empty queue for trackId=$trackId", AUTO)
            return item
        }
        musicPlaybackCoordinator.playAlbum(queueTracks, startIndex, api, resolvedAlbumTitle)

        val track = queueTracks.getOrNull(startIndex)
        return item.buildUpon()
            .setUri(api.streamUrl(trackId)?.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track?.title)
                    .setArtist(track?.artist)
                    .setAlbumTitle(resolvedAlbumTitle)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsPlayable(true)
                    .apply { track?.coverArt?.let { covId -> coverArtUri(covId)?.let { setArtworkUri(it) } } }
                    .build()
            )
            .build()
    }

    /**
     * Fires a one-shot background fetch into [cache], swaps it in on success, and
     * fires [MediaLibrarySession.notifyChildrenChanged] for [mediaId] so Auto
     * refreshes the browse node once real data is available. Failures are logged
     * and otherwise swallowed — the node just stays empty (baw.7.1).
     */
    private fun <T> CoroutineScope.prefetchMusicList(
        mediaId: String,
        cache: MutableList<T>,
        label: String,
        fetch: suspend () -> List<T>,
    ) {
        launch {
            try {
                val items = fetch()
                cache.clear()
                cache.addAll(items)
                DebugLogger.log("Music $label cache updated: ${items.size} items", AUTO)
                val controllers = mediaLibrarySession?.connectedControllers ?: emptyList()
                controllers.forEach { controller ->
                    mediaLibrarySession?.notifyChildrenChanged(controller, mediaId, items.size, null)
                }
            } catch (e: Exception) {
                DebugLogger.log("Music $label pre-fetch failed: ${e.message}", AUTO)
            }
        }
    }

    /**
     * Persists [source]'s queue/index/current position as the last-played
     * library entry (baw.10), so [LibraryCallback.onPlaybackResumption] can
     * rebuild this exact track after process death.
     */
    private suspend fun saveLibraryLastPlayed(source: PlaybackSource.Library) {
        persistenceService.saveLastPlayed(
            LastPlayed.LibraryTrack(
                queue = source.queue,
                index = source.index,
                positionMs = vlcPlayerWrapper.currentPositionMs(),
                albumTitle = musicPlaybackCoordinator.nowPlayingAlbumTitle.value,
            )
        )
    }

    /**
     * Resolves a Subsonic cover-art ID to an authenticated artwork [Uri] for the
     * currently-configured account, or null when no Subsonic account is active
     * (baw.7.1 — cover art thumbnails on browse items). The Subsonic auth token
     * is embedded in the URL's query string, so no special HTTP headers are
     * needed for Auto's image loader to fetch it.
     */
    private fun coverArtUri(coverArtId: String): Uri? =
        subsonicApi?.getCoverArtUrl(coverArtId)?.let { Uri.parse(it.toString()) }

    /**
     * Resolves a [NavidromeApi] for the currently-configured Subsonic account
     * by reading [AccountManager.accounts] synchronously. Returns null when no
     * Subsonic account is configured.
     *
     * Used in [onAddMediaItems] to play a music track without relying on the
     * [subsonicApi] field which may lag behind the first accounts emission.
     */
    private fun resolveSubsonicApiNow(): NavidromeApi? {
        val subsonic = accountManager.accounts.value
            .filter { it.isEnabled }
            .firstNotNullOfOrNull { it.type as? AccountType.Subsonic }
        return subsonic?.let { s ->
            subsonicApi ?: navidromeApiFactory.create(s.host, s.username, s.password).also { subsonicApi = it }
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

    /**
     * Builds the resumed [MediaItem] for a library track (baw.10), mirroring
     * [buildPlayableItem]'s channel shape and [handleMusicTrackTap]'s metadata
     * fields for a music item.
     */
    private fun buildLibraryPlayableItem(track: Track, albumTitle: String?): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(albumTitle)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

        track.coverArt?.let { coverArtId ->
            coverArtUri(coverArtId)?.let { metadataBuilder.setArtworkUri(it) }
        }

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(subsonicApi?.streamUrl(track.id)?.toString())
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}
