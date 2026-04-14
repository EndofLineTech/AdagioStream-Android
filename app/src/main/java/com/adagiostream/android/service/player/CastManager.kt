package com.adagiostream.android.service.player

import android.content.Context
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.model.TrackMetadata
import com.adagiostream.android.util.DebugLogger
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CastManager(private val context: Context) {

    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _castDeviceName = MutableStateFlow<String?>(null)
    val castDeviceName: StateFlow<String?> = _castDeviceName.asStateFlow()

    private val _remotePlaybackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val remotePlaybackState: StateFlow<PlaybackState> = _remotePlaybackState.asStateFlow()

    private var castContext: CastContext? = null
    private var sessionManager: SessionManager? = null
    private var remoteMediaClient: RemoteMediaClient? = null

    var onCastSessionStarted: (() -> Unit)? = null
    var onCastSessionEnded: (() -> Unit)? = null

    private val remoteMediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = remoteMediaClient ?: return
            val playerState = client.playerState
            val newState = when (playerState) {
                MediaStatus.PLAYER_STATE_BUFFERING -> PlaybackState.Buffering
                MediaStatus.PLAYER_STATE_PLAYING -> PlaybackState.Playing
                MediaStatus.PLAYER_STATE_PAUSED -> PlaybackState.Paused
                MediaStatus.PLAYER_STATE_IDLE -> {
                    val idleReason = client.idleReason
                    if (idleReason == MediaStatus.IDLE_REASON_ERROR) {
                        PlaybackState.Error("Cast playback error")
                    } else {
                        PlaybackState.Idle
                    }
                }
                else -> PlaybackState.Idle
            }
            DebugLogger.log("Cast remote state: $newState", DebugLogger.Category.PLAYER)
            _remotePlaybackState.value = newState
        }
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            DebugLogger.log("Cast session start failed: $error", DebugLogger.Category.PLAYER)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            DebugLogger.log("Cast session started: ${session.castDevice?.friendlyName}", DebugLogger.Category.PLAYER)
            remoteMediaClient = session.remoteMediaClient?.also {
                it.registerCallback(remoteMediaClientCallback)
            }
            _isCasting.value = true
            _castDeviceName.value = session.castDevice?.friendlyName
            onCastSessionStarted?.invoke()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            DebugLogger.log("Cast session resumed: ${session.castDevice?.friendlyName}", DebugLogger.Category.PLAYER)
            remoteMediaClient = session.remoteMediaClient?.also {
                it.registerCallback(remoteMediaClientCallback)
            }
            _isCasting.value = true
            _castDeviceName.value = session.castDevice?.friendlyName
            onCastSessionStarted?.invoke()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            DebugLogger.log("Cast session ended", DebugLogger.Category.PLAYER)
            remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
            remoteMediaClient = null
            _isCasting.value = false
            _castDeviceName.value = null
            _remotePlaybackState.value = PlaybackState.Idle
            onCastSessionEnded?.invoke()
        }
    }

    fun initialize() {
        try {
            castContext = CastContext.getSharedInstance(context)
            sessionManager = castContext?.sessionManager?.also {
                it.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
            }
            DebugLogger.log("CastManager initialized", DebugLogger.Category.PLAYER)
        } catch (e: Exception) {
            DebugLogger.log("Cast unavailable: ${e.message}", DebugLogger.Category.PLAYER)
        }
    }

    fun loadMedia(channel: Channel, trackMetadata: TrackMetadata? = null) {
        val client = remoteMediaClient ?: return

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, trackMetadata?.title ?: channel.name)
            putString(MediaMetadata.KEY_ARTIST, trackMetadata?.artist ?: channel.group)
            val artworkUrl = trackMetadata?.albumArtURL ?: channel.logoURL
            if (artworkUrl != null) {
                addImage(WebImage(android.net.Uri.parse(artworkUrl)))
            }
        }

        val mediaInfo = MediaInfo.Builder(channel.streamURL)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("audio/*")
            .setMetadata(metadata)
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        client.load(loadRequest)
        DebugLogger.log("Cast: loading ${channel.name}", DebugLogger.Category.PLAYER)
    }

    fun play() {
        remoteMediaClient?.play()
    }

    fun pause() {
        remoteMediaClient?.pause()
    }

    fun stop() {
        remoteMediaClient?.stop()
        _remotePlaybackState.value = PlaybackState.Idle
    }

    fun updateMetadata(channel: Channel, trackMetadata: TrackMetadata? = null) {
        val client = remoteMediaClient ?: return
        if (client.mediaInfo == null) return

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, trackMetadata?.title ?: channel.name)
            putString(MediaMetadata.KEY_ARTIST, trackMetadata?.artist ?: channel.group)
            val artworkUrl = trackMetadata?.albumArtURL ?: channel.logoURL
            if (artworkUrl != null) {
                addImage(WebImage(android.net.Uri.parse(artworkUrl)))
            }
        }

        val updatedMediaInfo = MediaInfo.Builder(channel.streamURL)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("audio/*")
            .setMetadata(metadata)
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(updatedMediaInfo)
            .setAutoplay(true)
            .build()

        // Reload with updated metadata to refresh the Cast receiver display
        client.load(loadRequest)
    }
}
