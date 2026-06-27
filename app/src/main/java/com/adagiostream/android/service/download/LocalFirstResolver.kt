package com.adagiostream.android.service.download

import java.io.File

/**
 * Pure local-first playback URL resolution (baw.6.3).
 *
 * If a track is fully downloaded to disk, playback loads the local `file://` URI
 * instead of the authenticated `stream.view` URL. This keeps the decision out of
 * the (device-only) player and makes it unit-testable.
 */
object LocalFirstResolver {

    /**
     * Returns the URI the player should open.
     *
     * @param localPath  Absolute path to a fully-downloaded file, or null if the
     *                   track is not downloaded.
     * @param streamUrl  The authenticated stream URL fallback (may be null when
     *                   offline and no download exists).
     * @return a `file://` URI when [localPath] is present, otherwise [streamUrl],
     *         or null when neither is available.
     */
    fun resolve(localPath: String?, streamUrl: String?): String? =
        if (localPath != null) fileUri(localPath) else streamUrl

    /** Builds a `file://` URI from an absolute path (handles spaces/specials). */
    fun fileUri(path: String): String = File(path).toURI().toString()
}

/**
 * Synchronous lookup of a completed download's local path for a track id (baw.6.3).
 *
 * The [MusicPlaybackCoordinator]'s play path is synchronous, so it cannot suspend
 * on a DAO query; production keeps an in-memory snapshot of completed downloads
 * updated from a Room Flow. [None] is the default no-op used in tests and when no
 * downloads exist.
 */
fun interface DownloadLocator {
    /** Absolute local path for a fully-downloaded [trackId], or null. */
    fun localPathFor(trackId: String): String?

    companion object {
        /** No download awareness — always streams. */
        val None: DownloadLocator = DownloadLocator { null }
    }
}
