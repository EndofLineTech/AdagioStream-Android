package com.adagiostream.android.service.player

import androidx.annotation.VisibleForTesting
import com.adagiostream.android.service.navidrome.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * The canonical playback-queue authority for Navidrome library playback (baw.3.1).
 *
 * Holds an ordered, immutable [Track] queue plus a cursor, a shuffle flag and a
 * [RepeatMode], and exposes the pure navigation API the player calls to advance
 * (`next` / `previous`). It does NOT build stream URLs or talk to libVLC — that
 * wiring is baw.3.2. It only decides *which track is next*.
 *
 * **Order model** (mirrors iOS AudioPlayerService):
 * the canonical [queue] is never mutated. When shuffle is on, [shuffleOrder]
 * holds a permutation of indices into the queue and [shufflePosition] is the
 * cursor into that permutation; [currentIndex] is always the canonical index of
 * the active track. When shuffle is off, navigation uses [currentIndex] directly.
 *
 * **Repeat + manual navigation:** [RepeatMode.One] governs only auto-advance; it
 * never traps manual [next] / [previous] (matching iOS / platform music apps),
 * so this manager treats `One` like `Off` for user-initiated navigation.
 */
@Singleton
class MusicQueueManager @Inject constructor() {

    /** The canonical, immutable track order. */
    var queue: List<Track> = emptyList()
        private set

    /** Canonical index of the active track; -1 when the queue is empty. */
    var currentIndex: Int = -1
        private set

    /** Repeat behaviour for auto-advance and navigation wrap. Freely settable. */
    var repeatMode: RepeatMode = RepeatMode.Off

    /** Whether navigation follows a shuffled order. Toggle via [setShuffle]. */
    var shuffleEnabled: Boolean = false
        private set

    // Permutation of canonical indices; element at [shufflePosition] == currentIndex.
    // Empty when shuffle is off.
    private var shuffleOrder: List<Int> = emptyList()
    private var shufflePosition: Int = 0

    /** Overridable RNG so shuffle behaviour is deterministic under test. */
    @VisibleForTesting
    internal var random: Random = Random.Default

    val size: Int get() = queue.size
    val isEmpty: Boolean get() = queue.isEmpty()

    /** The currently-active track, or null when nothing is queued. */
    fun current(): Track? = queue.getOrNull(currentIndex)

    /**
     * Replaces the queue and sets the active track to [startIndex] (clamped into
     * range). Rebuilds the shuffle order — pinning the start track first — when
     * shuffle is enabled.
     */
    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        queue = tracks
        if (tracks.isEmpty()) {
            currentIndex = -1
            clearShuffle()
            return
        }
        currentIndex = startIndex.coerceIn(0, tracks.lastIndex)
        if (shuffleEnabled) {
            shuffleOrder = buildShuffleOrder(tracks.size, pinnedIndex = currentIndex)
            shufflePosition = 0
        } else {
            clearShuffle()
        }
    }

    /**
     * Advances to the next track (user-initiated) and returns it, or null when at
     * the end with no wrap (repeat Off/One) — in which case the cursor does not move.
     */
    fun next(): Track? {
        if (queue.isEmpty()) return null

        if (shuffleEnabled) {
            val nextPos = shufflePosition + 1
            if (nextPos >= shuffleOrder.size) {
                if (repeatMode == RepeatMode.All) {
                    shuffleOrder = buildShuffleOrderFull(queue.size)
                    shufflePosition = 0
                } else {
                    return null // stop at end of shuffle order
                }
            } else {
                shufflePosition = nextPos
            }
            currentIndex = shuffleOrder[shufflePosition]
            return current()
        }

        if (currentIndex + 1 >= queue.size) {
            if (repeatMode == RepeatMode.All) {
                currentIndex = 0
            } else {
                return null // stop at end
            }
        } else {
            currentIndex += 1
        }
        return current()
    }

    /**
     * Moves to the previous track (user-initiated) and returns it.
     *
     * At the start of the queue: wraps to the last track when repeat is All,
     * otherwise restarts the current track (standard music-app behaviour).
     */
    fun previous(): Track? {
        if (queue.isEmpty()) return null

        if (shuffleEnabled) {
            when {
                shufflePosition > 0 -> shufflePosition -= 1
                repeatMode == RepeatMode.All -> shufflePosition = shuffleOrder.lastIndex
                // else: stay put → restart current
            }
            currentIndex = shuffleOrder[shufflePosition]
            return current()
        }

        when {
            currentIndex > 0 -> currentIndex -= 1
            repeatMode == RepeatMode.All -> currentIndex = queue.lastIndex
            // else: stay at 0 → restart current
        }
        return current()
    }

    /**
     * Enables or disables shuffle. Enabling keeps the current track playing and
     * pins it at the head of a fresh shuffle order; disabling clears the order
     * and resumes navigation from the current track's natural index.
     */
    fun setShuffle(enabled: Boolean) {
        if (enabled == shuffleEnabled) return
        shuffleEnabled = enabled
        if (queue.isEmpty()) {
            clearShuffle()
            return
        }
        if (enabled) {
            shuffleOrder = buildShuffleOrder(queue.size, pinnedIndex = currentIndex)
            shufflePosition = 0
        } else {
            clearShuffle()
        }
    }

    /** Toggles shuffle on/off. */
    fun toggleShuffle() = setShuffle(!shuffleEnabled)

    /** Cycles the repeat mode: Off → All → One → Off. */
    fun cycleRepeatMode() {
        repeatMode = repeatMode.next
    }

    /**
     * The order tracks will play in, as canonical indices: the shuffle order when
     * shuffle is on, otherwise the natural `0..size-1` sequence.
     */
    @VisibleForTesting
    internal fun playOrder(): List<Int> =
        if (shuffleEnabled) shuffleOrder else queue.indices.toList()

    private fun clearShuffle() {
        shuffleOrder = emptyList()
        shufflePosition = 0
    }

    /**
     * Builds a shuffle permutation with [pinnedIndex] fixed at position 0 (so the
     * current track plays first). The remaining indices are shuffled; if they
     * happen to land in ascending order (yielding the canonical sequence), they
     * are nudged so the overall order is observably changed.
     */
    private fun buildShuffleOrder(count: Int, pinnedIndex: Int): List<Int> {
        if (count <= 1) return if (count == 1) listOf(0) else emptyList()
        val rest = (0 until count).filter { it != pinnedIndex }.shuffled(random).toMutableList()
        // Guarantee "order changed": only at risk when pinnedIndex == 0 and the
        // rest came out sorted (so order == 0,1,2,...). Swap two rest elements,
        // preserving the pin at position 0. (Impossible to change for count == 2.)
        if (pinnedIndex == 0 && rest.size >= 2 && rest == rest.sorted()) {
            rest[0] = rest[1].also { rest[1] = rest[0] }
        }
        return listOf(pinnedIndex) + rest
    }

    /** Builds a full shuffle permutation with no pin (used on repeat-all wrap). */
    private fun buildShuffleOrderFull(count: Int): List<Int> {
        if (count <= 1) return if (count == 1) listOf(0) else emptyList()
        val order = (0 until count).shuffled(random).toMutableList()
        if (order == order.indices.toList()) {
            order[0] = order[1].also { order[1] = order[0] }
        }
        return order
    }
}
