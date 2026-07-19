package com.adagiostream.android.service.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import androidx.core.content.ContextCompat
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.util.DebugLogger
import com.adagiostream.android.util.UrlSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class VLCPlayerWrapper(
    private val context: Context,
    initialBufferSeconds: Int = 10,
    val castManager: CastManager,
) : LibraryTrackPlayer, AudiobookFilePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playbackState = kotlinx.coroutines.flow.MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: kotlinx.coroutines.flow.StateFlow<PlaybackState> = _playbackState

    private val _currentChannel = kotlinx.coroutines.flow.MutableStateFlow<Channel?>(null)
    val currentChannel: kotlinx.coroutines.flow.StateFlow<Channel?> = _currentChannel

    // PlaybackSource seam (baw.3.7). Today only Radio is ever assigned — it
    // mirrors _currentChannel during live playback. baw.3.2 will assign Library
    // when a track is played. getState() and the EndReached handler branch on
    // this via PlaybackContract; a null source is treated as live (conservative).
    private val _playbackSource = kotlinx.coroutines.flow.MutableStateFlow<PlaybackSource?>(null)
    val playbackSource: kotlinx.coroutines.flow.StateFlow<PlaybackSource?> = _playbackSource

    /**
     * Invoked when a [PlaybackSource.Library] track ends naturally (libVLC
     * EndReached + AutoAdvance policy). Wired by baw.3.2 to advance the queue.
     * Null / unused while only live radio plays.
     */
    var onLibraryTrackEnded: (() -> Unit)? = null

    /**
     * Invoked when a [PlaybackSource.Audiobook] FILE ends naturally
     * (beads_adagio-59p.1.5). The AudiobookPlaybackCoordinator chains the next
     * file on the book's timeline (or finishes the book).
     */
    var onAudiobookFileEnded: (() -> Unit)? = null

    private val _bitrateKbps = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val bitrateKbps: kotlinx.coroutines.flow.StateFlow<Float> = _bitrateKbps

    private val _streamStartedAt = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val streamStartedAt: kotlinx.coroutines.flow.StateFlow<Long?> = _streamStartedAt

    var channelList: List<Channel> = emptyList()
        private set

    var bufferDurationSeconds: Int = initialBufferSeconds
        private set

    private var libVLC: LibVLC = buildLibVLC()
    private var mediaPlayer: MediaPlayer = MediaPlayer(libVLC)
    private var bitrateJob: Job? = null
    private var peakBitrateKbps: Float = 0f
    private var isSwitchingChannels: Boolean = false

    // Time-shift tracking
    private val _timeShiftMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val timeShiftMs: kotlinx.coroutines.flow.StateFlow<Long> = _timeShiftMs
    private var pausedAtSystemTime: Long = 0L

    // Listening timer — tracks actual playback time (excludes pause/buffer)
    private val _listeningTimeMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val listeningTimeMs: kotlinx.coroutines.flow.StateFlow<Long> = _listeningTimeMs
    private var listeningSegmentStartTime: Long = 0L

    // Time-shift buffer
    private val timeShiftBuffer = TimeShiftBuffer(context.cacheDir, OkHttpClient())
    private var isPlayingBufferedFile = false
    private var timeShiftCaptureJob: Job? = null
    private var liveChannelForCatchUp: Channel? = null

    // Channel debouncing
    private var lastTeardownTime: Long = 0L
    private var pendingPlayJob: Job? = null

    // Connection retry with backoff
    private var retryCount: Int = 0
    private var hasReceivedPlayingEvent: Boolean = false
    private var retryJob: Job? = null
    private var stablePlaybackJob: Job? = null
    private companion object {
        const val MAX_RETRIES = 3
        const val DEBOUNCE_WINDOW_MS = 10_000L
        const val DEBOUNCE_DELAY_MS = 1_500L
        // Reset retry budget after this much continuous playback so a long
        // listening session that hits one network swap gets a full retry budget.
        const val STABLE_PLAYBACK_RESET_MS = 30_000L
    }

    // Network change monitoring — triggers immediate reconnect on wifi↔mobile swap
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { handleNetworkAvailable() }
        }
    }

    // Audio focus
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var wasPlayingBeforeFocusLoss = false
    private var isDucking = false
    private val audioFocusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener { focusChange ->
            scope.launch { handleAudioFocusChange(focusChange) }
        }
        .build()

    init {
        attachEventListener()
        setupCastCallbacks()
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            DebugLogger.log("Failed to register network callback — ${e.message}", DebugLogger.Category.PLAYER)
        }
    }

    private fun handleNetworkAvailable() {
        val channel = _currentChannel.value ?: return
        if (isSwitchingChannels) return
        if (castManager.isCasting.value) return
        // Only auto-reconnect from a terminal Error. Other states either resolve
        // on their own (Buffering/Playing) or are user-driven (Idle/Paused).
        if (_playbackState.value !is PlaybackState.Error) return
        DebugLogger.log("Network available — auto-reconnecting ${channel.name}", DebugLogger.Category.PLAYER)
        retryCount = 0
        retryJob?.cancel()
        doPlay(channel)
    }

    private fun setupCastCallbacks() {
        castManager.onCastSessionStarted = {
            try {
                // Transfer current local playback to Cast
                val channel = _currentChannel.value
                if (channel != null && _playbackState.value !is PlaybackState.Idle) {
                    DebugLogger.log("Cast connected — transferring ${channel.name} to Cast", DebugLogger.Category.PLAYER)
                    // Stop local VLC playback but keep current channel
                    isSwitchingChannels = true
                    mediaPlayer.stop()
                    stopBitratePolling()
                    accumulateListeningSegment()
                    audioManager.abandonAudioFocusRequest(audioFocusRequest)
                    wasPlayingBeforeFocusLoss = false
                    isDucking = false
                    // Send to Cast
                    castManager.loadMedia(channel)
                }
            } catch (e: Exception) {
                DebugLogger.log("Cast session transfer error — ${e.message}", DebugLogger.Category.PLAYER)
            }
        }
        castManager.onCastSessionEnded = {
            try {
                // Resume local playback if we had a channel
                val channel = _currentChannel.value
                if (channel != null) {
                    DebugLogger.log("Cast disconnected — resuming ${channel.name} locally", DebugLogger.Category.PLAYER)
                    doPlay(channel)
                }
            } catch (e: Exception) {
                DebugLogger.log("Cast session resume error — ${e.message}", DebugLogger.Category.PLAYER)
            }
        }

        // Observe remote playback state to keep _playbackState in sync
        scope.launch {
            castManager.remotePlaybackState.collect { remoteState ->
                if (castManager.isCasting.value) {
                    _playbackState.value = remoteState
                    when (remoteState) {
                        is PlaybackState.Playing -> {
                            if (listeningSegmentStartTime == 0L) {
                                listeningSegmentStartTime = System.currentTimeMillis()
                            }
                        }
                        is PlaybackState.Paused, is PlaybackState.Idle, is PlaybackState.Error -> {
                            accumulateListeningSegment()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun buildLibVLC(): LibVLC {
        val cachingMs = (bufferDurationSeconds * 1000).toString()
        DebugLogger.log("Building LibVLC with caching=${cachingMs}ms (${bufferDurationSeconds}s)", DebugLogger.Category.VLC)
        val options = arrayListOf(
            "--aout=aaudio",
            "--no-video",
            "--clock-synchro=0",
            "--no-audio-time-stretch",
        )
        return LibVLC(context, options)
    }

    private fun attachEventListener() {
        mediaPlayer.setEventListener { event ->
            scope.launch {
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                isSwitchingChannels = false
                DebugLogger.log("Opening stream for ${_currentChannel.value?.name}", DebugLogger.Category.VLC)
                _playbackState.value = PlaybackState.Buffering
            }
            MediaPlayer.Event.Buffering -> {
                val pct = event.buffering
                DebugLogger.log("Buffering ${pct}%", DebugLogger.Category.VLC)
                // Only show Buffering UI before the first Playing event.
                // Once playing, ignore sub-100% micro-buffer fluctuations.
                if (pct < 100f && _playbackState.value !is PlaybackState.Playing) {
                    _playbackState.value = PlaybackState.Buffering
                }
            }
            MediaPlayer.Event.Playing -> {
                DebugLogger.log("Playing ${_currentChannel.value?.name}", DebugLogger.Category.VLC)
                hasReceivedPlayingEvent = true
                retryJob?.cancel()
                _playbackState.value = if (isPlayingBufferedFile) PlaybackState.CatchingUp else PlaybackState.Playing
                startBitratePolling()
                listeningSegmentStartTime = System.currentTimeMillis()
                // Reset retry budget only after sustained playback — protects against
                // a flaky stream that briefly connects between failures.
                stablePlaybackJob?.cancel()
                stablePlaybackJob = scope.launch {
                    delay(STABLE_PLAYBACK_RESET_MS)
                    val s = _playbackState.value
                    if (s is PlaybackState.Playing || s is PlaybackState.CatchingUp) {
                        retryCount = 0
                    }
                }
            }
            MediaPlayer.Event.Paused -> {
                DebugLogger.log("Paused", DebugLogger.Category.VLC)
                _playbackState.value = PlaybackState.Paused
                stopBitratePolling()
                accumulateListeningSegment()
            }
            MediaPlayer.Event.Stopped -> {
                DebugLogger.log("Stopped", DebugLogger.Category.VLC)
                stopBitratePolling()
                accumulateListeningSegment()
            }
            MediaPlayer.Event.EndReached -> when (PlaybackContract.endReachedPolicy(_playbackSource.value)) {
                // On-demand content finished — library tracks auto-advance the
                // queue (baw.3.2); audiobook files chain the book's next file
                // via the coordinator (beads_adagio-59p.1.5).
                EndReachedPolicy.AutoAdvance -> {
                    if (!isSwitchingChannels) {
                        DebugLogger.log("EndReached — on-demand item ended, auto-advancing", DebugLogger.Category.VLC)
                        when (_playbackSource.value) {
                            is PlaybackSource.Audiobook -> onAudiobookFileEnded?.invoke()
                            else -> onLibraryTrackEnded?.invoke()
                        }
                    }
                }
                // Live radio (or no source): unchanged behaviour — a live stream
                // should never legitimately end, so treat EndReached as transient.
                EndReachedPolicy.RetryAsError -> {
                    // Ignore stale events from channel switches or user-initiated stop
                    if (!isSwitchingChannels && _currentChannel.value != null) {
                        // When buffer playback ends, go back to live
                        if (isPlayingBufferedFile) {
                            DebugLogger.log("TimeShift: buffer playback ended, returning to live", DebugLogger.Category.TIMESHIFT)
                            isPlayingBufferedFile = false
                            val liveChannel = liveChannelForCatchUp
                            liveChannelForCatchUp = null
                            if (liveChannel != null) {
                                _timeShiftMs.value = 0L
                                doPlay(liveChannel)
                            }
                        } else if (retryCount < MAX_RETRIES) {
                            // Live streams shouldn't legitimately end — treat as a transient
                            // failure (typical cause: network swap, e.g. wifi → mobile).
                            retryConnection("Stream ended")
                        } else {
                            DebugLogger.log("EndReached — stream ended for ${_currentChannel.value?.name} (retries exhausted)", DebugLogger.Category.VLC)
                            _playbackState.value = PlaybackState.Error("Stream ended")
                            stopBitratePolling()
                        }
                    }
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                if (!isSwitchingChannels && _currentChannel.value != null) {
                    if (retryCount < MAX_RETRIES) {
                        retryConnection("Playback error")
                    } else {
                        DebugLogger.log("EncounteredError for ${_currentChannel.value?.name} (retries exhausted)", DebugLogger.Category.VLC)
                        _playbackState.value = PlaybackState.Error("Playback error")
                        stopBitratePolling()
                    }
                }
            }
        }
    }

    private fun startBitratePolling() {
        bitrateJob?.cancel()
        bitrateJob = scope.launch {
            var lastReadBytes: Long = -1L
            var lastTimestamp: Long = 0L
            while (true) {
                delay(5_000L)
                val media = mediaPlayer.media ?: continue
                val stats = media.stats ?: continue
                val now = System.currentTimeMillis()
                val readBytes = stats.readBytes
                if (lastReadBytes >= 0 && lastTimestamp > 0) {
                    val elapsedMs = now - lastTimestamp
                    if (elapsedMs > 0) {
                        val bytesPerSec = (readBytes - lastReadBytes) * 1000f / elapsedMs
                        val currentKbps = bytesPerSec * 8f / 1000f
                        if (currentKbps > peakBitrateKbps) {
                            peakBitrateKbps = currentKbps
                        }
                        _bitrateKbps.value = peakBitrateKbps
                    }
                }
                lastReadBytes = readBytes
                lastTimestamp = now
            }
        }
    }

    private fun stopBitratePolling() {
        bitrateJob?.cancel()
        bitrateJob = null
    }

    fun updateBufferDuration(seconds: Int) {
        val clamped = seconds.coerceIn(5, 15)
        if (clamped == bufferDurationSeconds) return
        bufferDurationSeconds = clamped

        // Rebuild LibVLC + MediaPlayer with new caching values
        val wasPlaying = _currentChannel.value != null && _playbackState.value is PlaybackState.Playing
        val currentCh = _currentChannel.value

        stopBitratePolling()
        mediaPlayer.stop()
        mediaPlayer.release()
        libVLC.release()

        libVLC = buildLibVLC()
        mediaPlayer = MediaPlayer(libVLC)
        attachEventListener()

        // Resume playback if we were playing
        if (wasPlaying && currentCh != null) {
            play(currentCh)
        }
    }

    fun setChannelList(channels: List<Channel>) {
        channelList = channels
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                DebugLogger.log("Audio focus gained", DebugLogger.Category.AUDIO)
                if (isDucking) {
                    mediaPlayer.setVolume(100)
                    isDucking = false
                } else if (wasPlayingBeforeFocusLoss) {
                    val bufferFile = timeShiftBuffer.stopCapture()
                    timeShiftCaptureJob?.cancel()
                    if (bufferFile != null && bufferFile.length() > 0) {
                        liveChannelForCatchUp = _currentChannel.value
                        playBufferedFile(bufferFile)
                    } else {
                        resume()
                    }
                }
                wasPlayingBeforeFocusLoss = false
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                DebugLogger.log("Audio focus lost permanently", DebugLogger.Category.AUDIO)
                wasPlayingBeforeFocusLoss = false
                timeShiftCaptureJob?.cancel()
                timeShiftBuffer.stopCapture()
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                DebugLogger.log("Audio focus lost transiently", DebugLogger.Category.AUDIO)
                if (mediaPlayer.isPlaying) {
                    wasPlayingBeforeFocusLoss = true
                    val channel = _currentChannel.value
                    if (channel != null) {
                        timeShiftCaptureJob = scope.launch {
                            timeShiftBuffer.startCapture(channel)
                        }
                    }
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                DebugLogger.log("Audio focus ducking", DebugLogger.Category.AUDIO)
                if (mediaPlayer.isPlaying) {
                    isDucking = true
                    mediaPlayer.setVolume(40)
                }
            }
        }
    }

    private fun playBufferedFile(file: java.io.File) {
        DebugLogger.log("TimeShift: playing buffered file ${file.name}", DebugLogger.Category.TIMESHIFT)
        isPlayingBufferedFile = true
        _playbackState.value = PlaybackState.CatchingUp

        isSwitchingChannels = true
        mediaPlayer.stop()

        val media = Media(libVLC, Uri.parse(file.absolutePath))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":no-video")
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    fun play(channel: Channel) {
        DebugLogger.log("play(): channel=${channel.name}, buffer=${bufferDurationSeconds}s, url=${UrlSanitizer.redact(channel.streamURL)}", DebugLogger.Category.PLAYER)

        // Cancel any pending debounced play
        pendingPlayJob?.cancel()

        // Reset retry state for new channel
        retryCount = 0
        hasReceivedPlayingEvent = false
        retryJob?.cancel()

        // Debounce rapid channel switches (e.g. repeated next/prev presses)
        val isAlreadyPlaying = _currentChannel.value != null
        val timeSinceTeardown = System.currentTimeMillis() - lastTeardownTime
        val shouldDebounce = isAlreadyPlaying || (lastTeardownTime > 0 && timeSinceTeardown < DEBOUNCE_WINDOW_MS)
        if (shouldDebounce) {
            DebugLogger.log("Debouncing play for ${channel.name} (alreadyPlaying=$isAlreadyPlaying, timeSinceTeardown=${timeSinceTeardown}ms)", DebugLogger.Category.PLAYER)
            // Guard against stale VLC events during the debounce window —
            // doPlay() will handle the actual stop when the delay expires.
            isSwitchingChannels = true
            _currentChannel.value = channel
            _playbackState.value = PlaybackState.Buffering
            pendingPlayJob = scope.launch {
                delay(DEBOUNCE_DELAY_MS)
                doPlay(channel)
            }
        } else {
            doPlay(channel)
        }
    }

    private fun doPlay(channel: Channel) {
        // Guard against stale VLC events FIRST, before updating channel/state
        isSwitchingChannels = true
        mediaPlayer.stop()
        stopBitratePolling()

        _currentChannel.value = channel
        _playbackSource.value = PlaybackSource.Radio(channel)
        _playbackState.value = PlaybackState.Buffering
        _bitrateKbps.value = 0f
        peakBitrateKbps = 0f
        _streamStartedAt.value = System.currentTimeMillis()
        _timeShiftMs.value = 0L
        pausedAtSystemTime = 0L
        _listeningTimeMs.value = 0L
        listeningSegmentStartTime = 0L

        ContextCompat.startForegroundService(
            context, Intent(context, AudioPlaybackService::class.java)
        )

        if (castManager.isCasting.value) {
            DebugLogger.log("doPlay(): casting ${channel.name} to Cast device", DebugLogger.Category.PLAYER)
            castManager.loadMedia(channel)
            return
        }

        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            DebugLogger.log("Play blocked — audio focus denied (another app has focus)", DebugLogger.Category.AUDIO)
            _playbackState.value = PlaybackState.Idle
            return
        }

        val cachingMs = (bufferDurationSeconds * 1000).toString()
        val media = Media(libVLC, Uri.parse(channel.streamURL))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=$cachingMs")
        media.addOption(":live-caching=$cachingMs")
        media.addOption(":no-video")

        mediaPlayer.media = media
        media.release() // MediaPlayer holds its own reference
        mediaPlayer.play()
    }

    /**
     * Plays a Navidrome library track from [streamUrl] (baw.3.2).
     *
     * Mirrors [doPlay] but for on-demand content: it switches the player out of
     * radio mode (clears [currentChannel], so the live auto-reconnect / retry
     * paths stay dormant) and sets [playbackSource] to [source]. From that point
     * [PlaybackContract] routes EndReached to auto-advance and reports the track as
     * seekable with a real duration. The canonical queue/index live in
     * [MusicQueueManager]; this only renders the audio for the active track.
     *
     * [startPositionMs] (baw.10 resume-at-position) is applied via a VLC
     * `:start-time=` media option BEFORE `mediaPlayer.play()` — the demuxer
     * seeks during Opening, so no audio ever renders from position 0 and
     * baw.11's iOS-style mute-during-seek workaround has no window to hide.
     * This assumes the stream is seekable at open (local file or
     * byte-range-servable HTTP — the same assumption [seekToPositionMs] makes
     * for the scrubber); behavior against a forced-transcode Subsonic stream
     * is unverified (tracked as baw.15).
     */
    override fun playLibraryTrack(streamUrl: String, source: PlaybackSource.Library, startPositionMs: Long) {
        DebugLogger.log(
            "playLibraryTrack(): track=${source.currentTrack.title}, index=${source.index}/${source.queue.size}, startPositionMs=$startPositionMs",
            DebugLogger.Category.PLAYER,
        )
        pendingPlayJob?.cancel()
        retryJob?.cancel()
        retryCount = 0
        hasReceivedPlayingEvent = false

        // Guard against stale VLC events FIRST, before updating state.
        isSwitchingChannels = true
        mediaPlayer.stop()
        stopBitratePolling()

        // Library, not radio: clear the channel so live-only logic (network
        // reconnect, EndReached-as-error, XM polling) stays dormant.
        _currentChannel.value = null
        _playbackSource.value = source
        _playbackState.value = PlaybackState.Buffering
        _bitrateKbps.value = 0f
        peakBitrateKbps = 0f
        _streamStartedAt.value = System.currentTimeMillis()
        _timeShiftMs.value = 0L
        pausedAtSystemTime = 0L
        _listeningTimeMs.value = 0L
        listeningSegmentStartTime = 0L

        ContextCompat.startForegroundService(
            context, Intent(context, AudioPlaybackService::class.java)
        )

        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            DebugLogger.log("Library play blocked — audio focus denied", DebugLogger.Category.AUDIO)
            _playbackState.value = PlaybackState.Idle
            return
        }

        val media = Media(libVLC, Uri.parse(streamUrl))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":no-video")
        if (startPositionMs > 0) {
            media.addOption(":start-time=${startPositionMs / 1000.0}")
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    /**
     * Refreshes the active library snapshot without restarting playback (baw.9.3).
     * Used after a queue reorder so Up Next / Android Auto pick up the new order.
     * No-op when the player is not currently in a library session (radio, or
     * nothing playing) — a stale/late refresh must never override a live source.
     */
    override fun updateLibrarySource(source: PlaybackSource.Library) {
        if (_playbackSource.value !is PlaybackSource.Library) return
        _playbackSource.value = source
    }

    /**
     * Plays ONE audiobook file (beads_adagio-59p.1.5) — mirrors
     * [playLibraryTrack]: clears radio state, sets the source to [source] (so
     * [PlaybackContract] reports seekable/whole-book duration and routes
     * EndReached to [onAudiobookFileEnded]), and opens the file at
     * [startPositionMs] via `:start-time`. [rate] is re-applied after play —
     * libVLC resets the playback rate on every media change.
     */
    override fun playAudiobookFile(
        streamUrl: String,
        source: PlaybackSource.Audiobook,
        startPositionMs: Long,
        rate: Float,
    ) {
        DebugLogger.log(
            "playAudiobookFile(): book=${source.bookTitle}, startPositionMs=$startPositionMs, rate=$rate",
            DebugLogger.Category.PLAYER,
        )
        pendingPlayJob?.cancel()
        retryJob?.cancel()
        retryCount = 0
        hasReceivedPlayingEvent = false

        // Guard against stale VLC events FIRST, before updating state.
        isSwitchingChannels = true
        mediaPlayer.stop()
        stopBitratePolling()

        // On-demand, not radio: clear the channel so live-only logic stays dormant.
        _currentChannel.value = null
        _playbackSource.value = source
        _playbackState.value = PlaybackState.Buffering
        _bitrateKbps.value = 0f
        peakBitrateKbps = 0f
        _streamStartedAt.value = System.currentTimeMillis()
        _timeShiftMs.value = 0L
        pausedAtSystemTime = 0L
        _listeningTimeMs.value = 0L
        listeningSegmentStartTime = 0L

        ContextCompat.startForegroundService(
            context, Intent(context, AudioPlaybackService::class.java)
        )

        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            DebugLogger.log("Audiobook play blocked — audio focus denied", DebugLogger.Category.AUDIO)
            _playbackState.value = PlaybackState.Idle
            return
        }

        val media = Media(libVLC, Uri.parse(streamUrl))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":no-video")
        if (startPositionMs > 0) {
            media.addOption(":start-time=${startPositionMs / 1000.0}")
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        if (rate != 1.0f) {
            mediaPlayer.rate = rate
        }
    }

    /** Refreshes audiobook metadata (chapter change) without restarting playback. */
    override fun updateAudiobookSource(source: PlaybackSource.Audiobook) {
        if (_playbackSource.value !is PlaybackSource.Audiobook) return
        _playbackSource.value = source
    }

    /** Applies a playback rate to the active media (audiobook variable speed). */
    override fun setRate(rate: Float) {
        DebugLogger.log("setRate($rate)", DebugLogger.Category.PLAYER)
        mediaPlayer.rate = rate
    }

    /** Current playback position in milliseconds (library scrubber; baw.3.6). */
    override fun currentPositionMs(): Long = mediaPlayer.time.coerceAtLeast(0L)

    /**
     * Seeks the active library track to [positionMs], clamped into the track's
     * known duration (baw.3.6). Live radio never calls this — it is non-seekable
     * per [PlaybackContract].
     */
    override fun seekToPositionMs(positionMs: Long) {
        val durationMs = mediaPlayer.length // -1 when unknown
        val target = clampSeekMs(positionMs, durationMs)
        DebugLogger.log("seekToPositionMs(): requested=$positionMs, clamped=$target, len=$durationMs", DebugLogger.Category.PLAYER)
        mediaPlayer.time = target
    }

    private fun retryConnection(reason: String) {
        retryCount++
        val delayMs = (retryCount * 1500L)
        DebugLogger.log("Retry $retryCount/$MAX_RETRIES ($reason) in ${delayMs}ms", DebugLogger.Category.PLAYER)
        _playbackState.value = PlaybackState.Buffering
        retryJob = scope.launch {
            delay(delayMs)
            val channel = _currentChannel.value ?: return@launch
            doPlay(channel)
        }
    }

    fun pause() {
        if (castManager.isCasting.value) {
            accumulateListeningSegment()
            castManager.pause()
            return
        }
        if (mediaPlayer.isPlaying) {
            pausedAtSystemTime = System.currentTimeMillis()
            accumulateListeningSegment()
            mediaPlayer.pause()
        }
    }

    fun resume() {
        if (castManager.isCasting.value) {
            listeningSegmentStartTime = System.currentTimeMillis()
            castManager.play()
            return
        }
        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            DebugLogger.log("Resume blocked — audio focus denied (another app has focus)", DebugLogger.Category.AUDIO)
            return
        }
        listeningSegmentStartTime = System.currentTimeMillis()
        if (pausedAtSystemTime > 0L) {
            val pauseDuration = System.currentTimeMillis() - pausedAtSystemTime
            _timeShiftMs.value += pauseDuration
            pausedAtSystemTime = 0L
            DebugLogger.log("Resuming with time-shift: ${_timeShiftMs.value}ms behind live", DebugLogger.Category.TIMESHIFT)
        }
        mediaPlayer.play()
    }

    fun seekToLive() {
        val channel = _currentChannel.value ?: return
        DebugLogger.log("seekToLive(): restarting stream at live edge", DebugLogger.Category.TIMESHIFT)
        _timeShiftMs.value = 0L
        pausedAtSystemTime = 0L
        play(channel)
    }

    fun togglePlayPause() {
        when (_playbackState.value) {
            is PlaybackState.Playing -> pause()
            is PlaybackState.Paused -> resume()
            else -> _currentChannel.value?.let { play(it) }
        }
    }

    override fun stop() {
        DebugLogger.log("stop(): channel=${_currentChannel.value?.name}", DebugLogger.Category.PLAYER)
        pendingPlayJob?.cancel()
        retryJob?.cancel()
        stablePlaybackJob?.cancel()
        timeShiftCaptureJob?.cancel()
        timeShiftBuffer.cleanup()
        isPlayingBufferedFile = false
        liveChannelForCatchUp = null
        retryCount = 0
        hasReceivedPlayingEvent = false
        lastTeardownTime = System.currentTimeMillis()
        _currentChannel.value = null  // Clear before stop so async events are ignored
        _playbackSource.value = null
        if (castManager.isCasting.value) {
            castManager.stop()
        }
        mediaPlayer.stop()
        stopBitratePolling()
        _playbackState.value = PlaybackState.Idle
        _bitrateKbps.value = 0f
        _streamStartedAt.value = null
        _timeShiftMs.value = 0L
        pausedAtSystemTime = 0L
        _listeningTimeMs.value = 0L
        listeningSegmentStartTime = 0L
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        wasPlayingBeforeFocusLoss = false
        isDucking = false
    }

    fun playNext() {
        val current = _currentChannel.value ?: return
        val index = channelList.indexOfFirst { it.id == current.id }
        DebugLogger.log("playNext(): current=${current.name}, index=$index, listSize=${channelList.size}", DebugLogger.Category.PLAYER)
        if (index >= 0 && index < channelList.size - 1) {
            play(channelList[index + 1])
        }
    }

    fun playPrevious() {
        val current = _currentChannel.value ?: return
        val index = channelList.indexOfFirst { it.id == current.id }
        DebugLogger.log("playPrevious(): current=${current.name}, index=$index, listSize=${channelList.size}", DebugLogger.Category.PLAYER)
        if (index > 0) {
            play(channelList[index - 1])
        }
    }

    fun prepareFromMediaId(channel: Channel) {
        setChannelList(listOf(channel))
        play(channel)
    }

    /** Whether the VLC player is currently playing audio. */
    val isPlaying: Boolean get() = mediaPlayer.isPlaying

    private fun accumulateListeningSegment() {
        if (listeningSegmentStartTime > 0L) {
            _listeningTimeMs.value += System.currentTimeMillis() - listeningSegmentStartTime
            listeningSegmentStartTime = 0L
        }
    }

    fun release() {
        stopBitratePolling()
        _listeningTimeMs.value = 0L
        listeningSegmentStartTime = 0L
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        scope.cancel()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        mediaPlayer.release()
        libVLC.release()
        _playbackState.value = PlaybackState.Idle
        _bitrateKbps.value = 0f
    }
}
