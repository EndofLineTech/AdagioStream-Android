package com.adagiostream.android.service.audiobookshelf

/**
 * Pure global↔file time mapping for a multi-file audiobook — port of the iOS
 * `AudiobookTimeline` type (beads_adagio-59p.1.5).
 *
 * A book's playback session exposes N direct-play files ([AbsAudioTrack]),
 * each positioned on the book's single global timeline by `startOffset`, and
 * chapters ([AbsChapter]) whose start/end are book-global seconds. The player
 * loads ONE file at a time; everything user-facing (scrubber, resume, sync,
 * chapter skip) speaks global seconds and maps through this type.
 *
 * All inputs are re-sorted defensively; all lookups clamp into
 * `[0, totalDuration]` so out-of-range positions can never produce an
 * unplayable location.
 */
class AudiobookTimeline(
    files: List<AbsAudioTrack>,
    chapters: List<AbsChapter> = emptyList(),
) {

    /** The book's files, sorted by their position on the global timeline. */
    val files: List<AbsAudioTrack> = files.sortedBy { it.startOffset }

    /** The book's chapters, sorted by global start time. */
    val chapters: List<AbsChapter> = chapters.sortedBy { it.start }

    /** Total book length in seconds — the furthest end offset of any file. */
    val totalDuration: Double =
        this.files.maxOfOrNull { it.startOffset + it.duration } ?: 0.0

    /** A global position resolved to a concrete file + offset within it. */
    data class Location(val file: AbsAudioTrack, val fileOffset: Double)

    companion object {
        /**
         * Previous-chapter behaviour (iOS Music/Podcasts convention): more than
         * this many seconds into a chapter, "previous" restarts the current
         * chapter; within it, "previous" jumps to the preceding chapter.
         */
        const val RESTART_THRESHOLD_SECONDS = 3.0

        /**
         * Next-chapter jitter guard (iOS parity): the next chapter is the first
         * one starting strictly after `t + 0.5s`, so a position sitting exactly
         * on a chapter boundary doesn't "skip" zero seconds.
         */
        const val BOUNDARY_TOLERANCE_SECONDS = 0.5
    }

    /**
     * Resolves a global position (seconds) to the file containing it and the
     * offset within that file. The input is clamped to `[0, totalDuration]`;
     * at/past the book end this returns the last file at its end. Null only
     * when the timeline has no files.
     */
    fun locate(globalSeconds: Double): Location? {
        if (files.isEmpty()) return null
        val t = globalSeconds.coerceIn(0.0, totalDuration)
        val file = files.lastOrNull { it.startOffset <= t } ?: files.first()
        val offset = (t - file.startOffset).coerceIn(0.0, file.duration)
        return Location(file, offset)
    }

    /** Inverse of [locate]: `file.startOffset + fileOffset` (offset clamped ≥ 0). */
    fun globalTime(file: AbsAudioTrack, fileOffset: Double): Double =
        file.startOffset + fileOffset.coerceAtLeast(0.0)

    /** The file following [file] on the timeline, or null at the last file. */
    fun fileAfter(file: AbsAudioTrack): AbsAudioTrack? {
        val i = files.indexOfFirst { it.index == file.index && it.startOffset == file.startOffset }
        return if (i in 0 until files.lastIndex) files[i + 1] else null
    }

    /**
     * The chapter containing the global position (`start <= t < end`), falling
     * back to the last chapter whose start is ≤ t (covers a position sitting
     * exactly at the final chapter's end). Null before the first chapter or
     * when the book has no chapters.
     */
    fun chapterAt(globalSeconds: Double): AbsChapter? =
        chapters.firstOrNull { globalSeconds >= it.start && globalSeconds < it.end }
            ?: chapters.lastOrNull { globalSeconds >= it.start }

    /**
     * Global start of the first chapter beginning after [after] (+ the 0.5s
     * boundary tolerance), or null when already in/past the last chapter.
     */
    fun nextChapterStart(after: Double): Double? =
        chapters.firstOrNull { it.start > after + BOUNDARY_TOLERANCE_SECONDS }?.start

    /**
     * Where a "previous chapter" press from global position [from] should land:
     * more than [RESTART_THRESHOLD_SECONDS] into the current chapter restarts
     * it; otherwise the preceding chapter's start (or the current chapter's
     * start when there is no preceding one). Null when the book has no chapters.
     */
    fun previousChapterStart(from: Double): Double? {
        if (chapters.isEmpty()) return null
        val current = chapterAt(from)
        if (current != null && from - current.start > RESTART_THRESHOLD_SECONDS) return current.start
        val previous = chapters.lastOrNull { it.start < (current?.start ?: from) }
        return previous?.start ?: current?.start
    }
}
