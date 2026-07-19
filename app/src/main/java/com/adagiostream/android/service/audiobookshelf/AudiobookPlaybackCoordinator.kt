package com.adagiostream.android.service.audiobookshelf

import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.service.persistence.PersistenceService
import com.adagiostream.android.service.player.AudiobookFilePlayer
import com.adagiostream.android.service.player.PlaybackSource
import com.adagiostream.android.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Audiobookshelf playback engine (beads_adagio-59p.1.5) — the audiobook
 * counterpart of [com.adagiostream.android.service.player.MusicPlaybackCoordinator].
 *
 * Owns every non-trivial audiobook decision on top of the single-file
 * [AudiobookFilePlayer] port:
 *  - opens the server playback session (`POST /api/items/{id}/play`) and
 *    builds the [AudiobookTimeline] from its `audioTracks` + `chapters`;
 *  - loads ONE file at a time; on natural end-of-file chains the next file
 *    seamlessly; global position = `currentFile.startOffset + player position`;
 *  - seeks across file boundaries via [AudiobookTimeline.locate];
 *  - chapter skip with the ">3s into chapter restarts it" previous rule;
 *  - variable speed ([SPEED_STEPS], persisted in AppSettings, re-applied on
 *    every file load because libVLC resets rate per media);
 *  - resume precedence: explicit override > pending offline queue position >
 *    session `currentTime` > cached last-known;
 *  - live sync every ~[SYNC_INTERVAL_MS] of playback plus forced on
 *    pause/seek/background/file-boundary; `SessionNotFound` reopens the
 *    session at the current position; close is best-effort on stop;
 *  - offline fallback: failed syncs land in [ABSProgressSyncQueue], flushed
 *    on app background and before opening a new session.
 *
 * Testability seams: [clock] (no wall-clock reads in logic), [onTick] (the
 * production ticker just calls it every second), [scope] (tests pass a
 * TestScope), [playerState] (tests drive pause/idle transitions directly).
 */
@Singleton
class AudiobookPlaybackCoordinator(
    private val player: AudiobookFilePlayer,
    private val queue: ABSProgressSyncQueue,
    private val apiFactory: AudiobookshelfApiFactory,
    /** Null in plain-JVM tests; production persists speed + device id here. */
    private val persistenceService: PersistenceService? = null,
    /** The wrapper's playback state — drives forced-sync-on-pause and finalize-on-stop. */
    private val playerState: StateFlow<PlaybackState>? = null,
    /** The wrapper's active source — finalizes the book when music/radio takes the player over. */
    private val playbackSource: StateFlow<PlaybackSource?>? = null,
    /** Persists rotated JWTs back to the encrypted account store (production). */
    private val onTokensRotated: (Account, AudiobookshelfAuth.Tokens?) -> Unit = { _, _ -> },
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    internal val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /** Speed menu steps (Apple Podcasts style, iOS parity). */
        val SPEED_STEPS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)

        /** Hard clamp for any requested rate (iOS parity). */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f

        /** Throttle between periodic session syncs while playing. */
        const val SYNC_INTERVAL_MS = 20_000L

        /** Progress fraction at/above which a book counts as finished (iOS: 1% epsilon). */
        const val FINISHED_EPSILON = 0.01

        private const val TICK_MS = 1_000L

        /**
         * Resume precedence (bead .1.5 contract): explicit override >
         * unflushed offline queue position > session `/play` currentTime >
         * cached last-known. First non-null wins; callers clamp to the
         * timeline afterwards.
         *
         * Divergence from iOS noted: iOS takes `max(pendingQueue, sessionTime)`;
         * the bead pins strict precedence, which is equivalent in the
         * only-reachable case (a pending entry exists exactly when the server
         * hasn't seen a newer position).
         */
        internal fun resumePosition(
            override: Double?,
            pendingQueue: Double?,
            sessionTime: Double?,
            cached: Double?,
        ): Double = override ?: pendingQueue ?: sessionTime ?: cached ?: 0.0
    }

    // ---- Active-session state ------------------------------------------------

    private var api: AudiobookshelfApi? = null
    private var sessionId: String? = null
    private var timeline: AudiobookTimeline? = null
    private var currentFile: AbsAudioTrack? = null
    private var libraryItemId: String? = null
    private var bookTitle: String = ""
    private var author: String? = null
    private var coverUrl: String? = null
    private var totalDuration: Double = 0.0
    private var lastChapterTitle: String? = null

    /** Whether an audiobook session is active (drives every guard below). */
    @Volatile
    private var active = false

    /** Global position at the last successful/attempted sync — timeListened accounting. */
    private var lastSyncedGlobal = 0.0

    /** [clock] time of the last sync — the ~20s throttle. */
    private var lastSyncAtMs = 0L

    /**
     * Last global position observed on a tick/seek/pause. Used by finalize
     * paths that run AFTER the player stopped (position no longer readable).
     */
    private var lastObservedGlobal = 0.0

    /** In-memory last-known positions — resume precedence level 4. */
    private val lastKnownPositions = mutableMapOf<String, Double>()

    private var tickerJob: Job? = null

    /** Lazy fallback device id when no [persistenceService] is wired (tests). */
    private val fallbackDeviceId: String by lazy { UUID.randomUUID().toString() }

    private val _speed = MutableStateFlow(
        persistenceService?.loadSettingsSync()?.audiobookSpeed ?: 1.0f,
    )

    /** Current playback speed — persisted, surfaced through the media session. */
    val speedFlow: StateFlow<Float> = _speed.asStateFlow()
    val speed: Float get() = _speed.value

    private val _nowPlaying = MutableStateFlow<PlaybackSource.Audiobook?>(null)

    /** The active book snapshot (null when no audiobook is playing) — for UI surfaces. */
    val nowPlaying: StateFlow<PlaybackSource.Audiobook?> = _nowPlaying.asStateFlow()

    init {
        // Forced sync on pause + finalize on stop, regardless of which surface
        // (UI, notification, Auto, audio-focus loss) drove the transition —
        // they all funnel through the wrapper's playback state.
        playerState?.let { flow ->
            scope.launch {
                var prev: PlaybackState? = null
                flow.collect { state ->
                    if (active) {
                        if (state is PlaybackState.Paused && prev is PlaybackState.Playing) {
                            scope.launch { syncNow() }
                        }
                        if (state is PlaybackState.Idle && prev != null && prev !is PlaybackState.Idle) {
                            finalizeSession()
                        }
                    }
                    prev = state
                }
            }
        }
        // Cross-source takeover (review B1): starting music/IPTV goes straight
        // to Buffering — the player never emits Idle on a direct source swap —
        // so without this the book session would stay active and sync the
        // MUSIC track's position onto the book every ~20s. Finalize (one last
        // sync at the last observed audiobook position + best-effort close)
        // the moment a non-audiobook source takes the player. A stale
        // Library/Radio emission racing a brand-new playAudiobook would need
        // the user to start a book within the flow's collect latency of
        // starting music — accepted.
        playbackSource?.let { flow ->
            scope.launch {
                flow.collect { source ->
                    if (active && (source is PlaybackSource.Radio || source is PlaybackSource.Library)) {
                        finalizeSession()
                    }
                }
            }
        }
    }

    // ---- Public entry --------------------------------------------------------

    /**
     * Opens (or reopens) playback of a book: flushes any queued offline
     * progress, opens the server session, builds the timeline, and starts the
     * file containing the resolved resume position. [resumeOverride] (global
     * seconds) wins over every other resume source — pass it for "start from
     * the beginning"/"play from here" UI actions.
     *
     * This is the integration point the library-UI branch's
     * AudiobookPlaybackLauncher calls.
     */
    suspend fun playAudiobook(account: Account, libraryItemId: String, resumeOverride: Double? = null) {
        val abs = account.type as? AccountType.Audiobookshelf ?: return
        finalizeNow() // switching books: sync + close the previous session first

        val tokens = abs.accessToken?.let { at ->
            abs.refreshToken?.let { rt -> AudiobookshelfAuth.Tokens(at, rt) }
        }
        val api = apiFactory.create(abs.host, abs.username, null, tokens) { newTokens ->
            onTokensRotated(account, newTokens)
        }

        // Offline-first (iOS ymf.6): push queued progress BEFORE asking the
        // server for a resume position, so reconnecting never rewinds.
        runCatching { queue.flush(api) }
        val pendingPosition = queue.pendingPosition(libraryItemId)

        val session = try {
            api.openPlaybackSession(libraryItemId, deviceId = deviceId())
        } catch (e: Exception) {
            DebugLogger.log("ABS playAudiobook: open session failed — ${e::class.simpleName}", DebugLogger.Category.PLAYER)
            return
        }
        val timeline = AudiobookTimeline(session.audioTracks.orEmpty(), session.chapters.orEmpty())
        if (timeline.files.isEmpty()) {
            DebugLogger.log("ABS playAudiobook: session has no audio tracks", DebugLogger.Category.PLAYER)
            return
        }

        this.api = api
        this.timeline = timeline
        this.libraryItemId = libraryItemId
        this.sessionId = session.id.takeIf { it.isNotEmpty() }
        this.bookTitle = session.displayTitle ?: session.mediaMetadata?.title ?: "Audiobook"
        this.author = session.mediaMetadata?.displayAuthor
        this.coverUrl = api.coverUrl(libraryItemId)?.toString()
        this.totalDuration = timeline.totalDuration

        val resume = resumePosition(
            override = resumeOverride,
            pendingQueue = pendingPosition,
            sessionTime = session.currentTime,
            cached = lastKnownPositions[libraryItemId],
        ).coerceIn(0.0, timeline.totalDuration)

        lastSyncedGlobal = resume
        lastObservedGlobal = resume
        lastSyncAtMs = clock()
        lastChapterTitle = timeline.chapterAt(resume)?.title
        active = true
        loadFileAt(resume)
        startTicker()
    }

    /** Global position in seconds: active file's startOffset + player position. */
    fun globalPositionSeconds(): Double {
        val file = currentFile ?: return lastObservedGlobal
        return (file.startOffset + player.currentPositionMs() / 1000.0)
            .coerceIn(0.0, totalDuration)
    }

    /** Global position in milliseconds — for Media3 session state. */
    fun globalPositionMs(): Long = (globalPositionSeconds() * 1000).toLong()

    /** Whole-book duration in seconds (0 when idle). */
    val totalDurationSeconds: Double get() = totalDuration

    /** The chapter at the current global position, or null. */
    fun currentChapter(): AbsChapter? = timeline?.chapterAt(globalPositionSeconds())

    /**
     * Seeks to a book-global position, crossing file boundaries when needed,
     * then force-syncs (resetting the 20s throttle).
     */
    fun seekTo(globalSeconds: Double) {
        if (!active) return
        val tl = timeline ?: return
        // Review B2: timeListened accrues only up to the PRE-seek position —
        // the jumped span must never count as listening (a 10s→3600s scrub is
        // 10s listened, not 3590s). Deliberate divergence from iOS, which
        // syncs at the target and counts a forward scrub as listened.
        val preSeek = globalPositionSeconds()
        val listened = (preSeek - lastSyncedGlobal).coerceAtLeast(0.0)
        val target = globalSeconds.coerceIn(0.0, tl.totalDuration)
        val location = tl.locate(target) ?: return
        lastObservedGlobal = target
        lastChapterTitle = tl.chapterAt(target)?.title
        if (location.file == currentFile) {
            player.seekToPositionMs((location.fileOffset * 1000).toLong())
            publishSource(target)
        } else {
            loadFileAt(target)
        }
        scope.launch { syncAt(target, listened) }
    }

    /** Skips to the next chapter start; no-op in/past the last chapter. */
    fun nextChapter() {
        val tl = timeline ?: return
        tl.nextChapterStart(globalPositionSeconds())?.let { seekTo(it) }
    }

    /**
     * Previous-chapter press: >3s into the current chapter restarts it,
     * otherwise jumps to the preceding chapter (iOS rule).
     */
    fun previousChapter() {
        val tl = timeline ?: return
        tl.previousChapterStart(globalPositionSeconds())?.let { seekTo(it) }
    }

    /** Sets playback speed (clamped to [MIN_SPEED]..[MAX_SPEED]), applies + persists it. */
    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(MIN_SPEED, MAX_SPEED)
        _speed.value = clamped
        player.setRate(clamped)
        persistSpeed(clamped)
    }

    /** Advances to the next entry in [SPEED_STEPS] (wrapping) — the one-button UX. */
    fun cycleSpeed() {
        val index = SPEED_STEPS.indexOfFirst { abs(it - speed) < 0.01f }
        setSpeed(SPEED_STEPS[(index + 1).mod(SPEED_STEPS.size)])
    }

    /**
     * Natural end of the active file (libVLC EndReached routed here by the
     * wrapper): force-sync at the boundary, then chain the next file or finish
     * the book.
     */
    fun onFileEnded() {
        if (!active) return
        val tl = timeline ?: return
        val file = currentFile ?: return
        val endGlobal = (file.startOffset + file.duration).coerceAtMost(tl.totalDuration)
        lastObservedGlobal = endGlobal
        scope.launch { syncNow(endGlobal) }
        val next = tl.fileAfter(file)
        if (next != null) {
            loadFileAt(next.startOffset)
        } else {
            stop() // book finished — finalize syncs at totalDuration
        }
    }

    /** Stops audiobook playback: final sync + best-effort session close. */
    fun stop() {
        if (!active) return
        finalizeSession()
        player.stop()
    }

    /**
     * App moved to background: force a sync of the live session and flush the
     * offline queue (bead .1.5 flush triggers). Safe to call when idle.
     */
    fun onAppBackground() {
        val api = this.api
        if (active) scope.launch { syncNow() }
        if (api != null) scope.launch { runCatching { queue.flush(api) } }
    }

    // ---- Tick + sync ---------------------------------------------------------

    /**
     * One playback tick (production: every second). Publishes chapter-change
     * metadata and fires the throttled session sync after [SYNC_INTERVAL_MS]
     * of playback. Internal so tests drive it directly with a fake [clock].
     */
    internal fun onTick() {
        if (!active) return
        val global = globalPositionSeconds()
        lastObservedGlobal = global
        val chapterTitle = timeline?.chapterAt(global)?.title
        if (chapterTitle != lastChapterTitle) {
            lastChapterTitle = chapterTitle
            publishSource(global)
        }
        val playing = playerState?.value is PlaybackState.Playing || playerState == null
        if (playing && clock() - lastSyncAtMs >= SYNC_INTERVAL_MS) {
            scope.launch { syncNow(global) }
        }
    }

    /**
     * Syncs [global] to the live session with `timeListened` = the (never
     * negative) MEDIA-position delta since the last sync — exact iOS parity
     * with `AudioPlayerService.timeListened(sinceLastSynced:currentGlobal:)`:
     * iOS reports media seconds, not wall clock, so at 2x speed 20s of real
     * time reports 40s listened. Seeks bypass this via [syncAt] with the
     * pre-seek delta (review B2).
     */
    internal suspend fun syncNow(global: Double = globalPositionSeconds()) {
        syncAt(global, (global - lastSyncedGlobal).coerceAtLeast(0.0))
    }

    /**
     * Reports position [global] + [timeListened] to the live session. On
     * `SessionNotFound` (expired/foreign session) reopens via `/play` at the
     * current position and retries once; on any other failure the update goes
     * to the offline queue instead of being dropped. Always resets the
     * throttle timer, so a forced sync (pause/seek/boundary) delays the next
     * periodic one.
     */
    // ponytail: no lock — a tick sync racing a forced sync can interleave
    // lastSyncedGlobal and double-count/drop a few seconds of timeListened;
    // position is last-write and self-corrects on the next sync. Add a Mutex
    // only if server-side listening stats start mattering.
    private suspend fun syncAt(global: Double, timeListened: Double) {
        if (!active) return
        val api = api ?: return
        val itemId = libraryItemId ?: return
        lastSyncedGlobal = global
        lastSyncAtMs = clock()
        lastKnownPositions[itemId] = global
        val sid = sessionId
        if (sid == null) {
            enqueueProgress(itemId, global)
            return
        }
        try {
            api.syncSession(sid, currentTime = global, timeListened = timeListened, duration = totalDuration)
        } catch (e: AudiobookshelfApiException.SessionNotFound) {
            reopenSession(api, itemId, global, timeListened)
        } catch (e: Exception) {
            enqueueProgress(itemId, global)
        }
    }

    private suspend fun reopenSession(api: AudiobookshelfApi, itemId: String, global: Double, timeListened: Double) {
        try {
            val session = api.openPlaybackSession(itemId, deviceId = deviceId())
            sessionId = session.id.takeIf { it.isNotEmpty() }
            sessionId?.let {
                api.syncSession(it, currentTime = global, timeListened = timeListened, duration = totalDuration)
            }
            DebugLogger.log("ABS sync: session reopened at ${global}s", DebugLogger.Category.PLAYER)
        } catch (_: Exception) {
            enqueueProgress(itemId, global)
        }
    }

    private fun enqueueProgress(itemId: String, global: Double) {
        queue.enqueue(
            AbsProgressUpdate.of(
                libraryItemId = itemId,
                currentTime = global,
                duration = totalDuration,
                isFinished = totalDuration > 0 && global / totalDuration >= 1.0 - FINISHED_EPSILON,
                lastUpdate = clock(),
            ),
        )
    }

    // ---- Internals -----------------------------------------------------------

    private fun loadFileAt(globalSeconds: Double) {
        val tl = timeline ?: return
        val location = tl.locate(globalSeconds) ?: return
        val url = api?.resolveContentUrl(location.file.contentUrl)?.toString()
        if (url == null) {
            DebugLogger.log("ABS loadFileAt: unresolvable content URL (no token?)", DebugLogger.Category.PLAYER)
            stop()
            return
        }
        currentFile = location.file
        lastObservedGlobal = globalSeconds.coerceIn(0.0, tl.totalDuration)
        val source = buildSource(lastObservedGlobal)
        _nowPlaying.value = source
        // Rate is passed per load — libVLC resets it on every media change.
        player.playAudiobookFile(url, source, (location.fileOffset * 1000).toLong(), speed)
    }

    private fun buildSource(globalSeconds: Double): PlaybackSource.Audiobook =
        PlaybackSource.Audiobook(
            libraryItemId = libraryItemId.orEmpty(),
            bookTitle = bookTitle,
            author = author,
            chapterTitle = timeline?.chapterAt(globalSeconds)?.title?.takeIf { it.isNotEmpty() },
            totalDurationSeconds = totalDuration,
            coverUrl = coverUrl,
        )

    private fun publishSource(globalSeconds: Double) {
        val source = buildSource(globalSeconds)
        _nowPlaying.value = source
        player.updateAudiobookSource(source)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                onTick()
            }
        }
    }

    private fun deviceId(): String = persistenceService?.deviceId() ?: fallbackDeviceId

    private fun persistSpeed(value: Float) {
        val ps = persistenceService ?: return
        scope.launch {
            val current = ps.loadSettings()
            ps.saveSettings(current.copy(audiobookSpeed = value))
        }
    }

    /** Captured end-of-session facts so finalize can run after state is cleared. */
    private data class FinalState(
        val api: AudiobookshelfApi,
        val sessionId: String?,
        val libraryItemId: String,
        val global: Double,
        val timeListened: Double,
        val duration: Double,
    )

    private fun captureFinalState(): FinalState? {
        if (!active) return null
        active = false
        tickerJob?.cancel()
        val api = this.api
        val itemId = libraryItemId
        val final = if (api != null && itemId != null) {
            FinalState(
                api = api,
                sessionId = sessionId,
                libraryItemId = itemId,
                global = lastObservedGlobal,
                timeListened = (lastObservedGlobal - lastSyncedGlobal).coerceAtLeast(0.0),
                duration = totalDuration,
            )
        } else {
            null
        }
        itemId?.let { lastKnownPositions[it] = lastObservedGlobal }
        sessionId = null
        timeline = null
        currentFile = null
        libraryItemId = null
        _nowPlaying.value = null
        this.api = null
        return final
    }

    private suspend fun runFinalSync(f: FinalState) {
        lastKnownPositions[f.libraryItemId] = f.global
        try {
            val sid = f.sessionId
            if (sid != null) {
                f.api.syncSession(sid, currentTime = f.global, timeListened = f.timeListened, duration = f.duration)
            } else {
                enqueueFinal(f)
            }
        } catch (_: Exception) {
            enqueueFinal(f) // incl. SessionNotFound — no point reopening a session we're closing
        }
        f.sessionId?.let { f.api.closeSession(it) } // best-effort by contract
    }

    private fun enqueueFinal(f: FinalState) {
        queue.enqueue(
            AbsProgressUpdate.of(
                libraryItemId = f.libraryItemId,
                currentTime = f.global,
                duration = f.duration,
                isFinished = f.duration > 0 && f.global / f.duration >= 1.0 - FINISHED_EPSILON,
                lastUpdate = clock(),
            ),
        )
    }

    /** Fire-and-forget finalize — the stop/Idle path. Idempotent via [active]. */
    private fun finalizeSession() {
        captureFinalState()?.let { scope.launch { runFinalSync(it) } }
    }

    /** Inline finalize — awaited when switching books inside [playAudiobook]. */
    private suspend fun finalizeNow() {
        captureFinalState()?.let { runFinalSync(it) }
    }
}
