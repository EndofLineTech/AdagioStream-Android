package com.adagiostream.android.service.audiobookshelf

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Audiobookshelf DTOs — port of iOS `AudiobookshelfModels.swift` +
 * `AudiobookshelfAuth.swift` response types. All decoding uses
 * `Json { ignoreUnknownKeys = true }`; every field the server may omit is
 * nullable with a default so minified and expanded shapes decode from the
 * same types.
 */

// -----------------------------------------------------------------------------
// Login / refresh (POST /login, POST /auth/refresh)
// -----------------------------------------------------------------------------

/**
 * `POST /login` response. `refreshToken` is top-level when `x-return-tokens`
 * is set; some server versions also echo it under `user`. `accessToken` lives
 * under `user`.
 */
@Serializable
data class AbsLoginResponse(
    val user: AbsUser? = null,
    val refreshToken: String? = null,
)

/**
 * `POST /auth/refresh` response — shape mirrors login (token fields may sit
 * top-level or under `user` depending on server version).
 */
@Serializable
data class AbsRefreshResponse(
    val user: AbsUser? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
)

/** The `user` object from login/refresh/`GET /api/me`. */
@Serializable
data class AbsUser(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val permissions: AbsPermissions? = null,
    val mediaProgress: List<AbsMediaProgress> = emptyList(),
)

/** `user.permissions` — only `download` is captured for later branches. */
@Serializable
data class AbsPermissions(
    val download: Boolean? = null,
)

// -----------------------------------------------------------------------------
// Server discovery (GET /status)
// -----------------------------------------------------------------------------

/**
 * `GET /status` payload — only the auth-related fields are read. Drives whether
 * the add-account form shows username/password and/or an SSO button.
 */
@Serializable
data class AbsServerStatus(
    val isInit: Boolean? = null,
    val authMethods: List<String>? = null,
    val authFormData: AbsAuthFormData? = null,
) {
    /**
     * Whether local username/password auth is available. Defaults to `true`
     * when the server didn't send `authMethods`, so a sparse/old response
     * never hides the password fields.
     */
    val supportsLocal: Boolean
        get() = authMethods.isNullOrEmpty() || "local" in authMethods

    /** Whether OpenID/SSO auth is available. */
    val supportsOpenId: Boolean
        get() = authMethods?.contains("openid") == true

    /** Server-configured SSO button label, or the ABS default. */
    val openIdButtonText: String
        get() = authFormData?.authOpenIDButtonText?.takeIf { it.isNotEmpty() } ?: "Login with OpenID"
}

/** `status.authFormData` — SSO button configuration. */
@Serializable
data class AbsAuthFormData(
    val authOpenIDButtonText: String? = null,
    val authOpenIDAutoLaunch: Boolean? = null,
)

// -----------------------------------------------------------------------------
// Libraries + items
// -----------------------------------------------------------------------------

/** A library from `GET /api/libraries`. Filter by [mediaType] client-side. */
@Serializable
data class AbsLibrary(
    val id: String,
    val name: String,
    val mediaType: String? = null,
) {
    val isBook: Boolean get() = mediaType == "book"
    val isPodcast: Boolean get() = mediaType == "podcast"
}

/** `GET /api/libraries` envelope. */
@Serializable
data class AbsLibrariesResponse(
    val libraries: List<AbsLibrary> = emptyList(),
)

/** `GET /api/libraries/{id}/items` envelope. `results` holds the items. */
@Serializable
data class AbsLibraryItemsResponse(
    val results: List<AbsLibraryItem> = emptyList(),
)

/** `GET /api/me/items-in-progress` envelope (Continue Listening). */
@Serializable
data class AbsItemsInProgressResponse(
    val libraryItems: List<AbsLibraryItem> = emptyList(),
)

/**
 * A single ABS library item (`GET /api/items/{id}` or an entry in a list).
 *
 * Book fields live under `media.metadata`; chapters under `media.chapters`;
 * this user's progress under `userMediaProgress` (present with
 * `?include=progress`). [recentEpisode] is a TOP-LEVEL field sent only by
 * `GET /api/me/items-in-progress` for an in-progress podcast — its presence
 * is the exact discriminator that an in-progress item is a podcast episode
 * (a book item never carries it).
 */
@Serializable
data class AbsLibraryItem(
    val id: String,
    val libraryId: String? = null,
    val media: AbsMedia? = null,
    val userMediaProgress: AbsMediaProgress? = null,
    val recentEpisode: AbsEpisode? = null,
) {
    /** Server-relative cover path for `GET /api/items/{id}/cover`. */
    val coverPath: String get() = "/api/items/$id/cover"
}

/**
 * The `media` object of a library item. Books and podcast shows share this
 * type: books carry [chapters]/[audioFiles] directly; podcast shows carry
 * [episodes] instead (expanded/detail shape only).
 */
@Serializable
data class AbsMedia(
    val duration: Double? = null,
    val metadata: AbsMetadata? = null,
    val chapters: List<AbsChapter>? = null,
    val audioFiles: List<AbsAudioFile>? = null,
    val episodes: List<AbsEpisode>? = null,
)

/**
 * `media.metadata` — title and author display fields. Books expose a flattened
 * `authorName` string; podcast shows expose `author` (same shape, different
 * key — ABS convention). [displayAuthor] picks whichever is present.
 */
@Serializable
data class AbsMetadata(
    val title: String? = null,
    val authorName: String? = null,
    val author: String? = null,
) {
    val displayAuthor: String? get() = authorName ?: author
}

/** A `media.chapters[]` entry. [start]/[end] are book-global seconds. */
@Serializable
data class AbsChapter(
    val id: Int = 0,
    val title: String = "",
    val start: Double = 0.0,
    val end: Double = 0.0,
)

/**
 * A `media.audioFiles[]` entry from the expanded item detail. [ino] is the
 * server file inode used by `GET /api/items/{id}/file/{ino}/download`; [index]
 * matches the playback session's `audioTracks[].index`.
 *
 * `ino` is a string on ABS, but some server versions emit it as a JSON number
 * — [FlexibleStringSerializer] accepts both.
 */
@Serializable
data class AbsAudioFile(
    val index: Int = 0,
    @Serializable(with = FlexibleStringSerializer::class)
    val ino: String = "",
    val duration: Double? = null,
)

/** One `media.episodes[]` entry on a podcast show's library item. */
@Serializable
data class AbsEpisode(
    val id: String = "",
    val title: String? = null,
    val duration: Double? = null,
    val pubDate: String? = null,
    val audioFile: AbsAudioFile? = null,
)

/** Per-user media progress (from the login payload, `?include=progress`, or
 *  `GET /api/me/progress/{libraryItemId}/{episodeId}`). */
@Serializable
data class AbsMediaProgress(
    val libraryItemId: String? = null,
    val episodeId: String? = null,
    val currentTime: Double? = null,
    val progress: Double? = null,
    val isFinished: Boolean? = null,
    val lastUpdate: Long? = null,
)

// -----------------------------------------------------------------------------
// Playback session (POST /api/items/{id}/play)
// -----------------------------------------------------------------------------

/**
 * `POST /api/items/{id}/play` response — the playback session. [currentTime]
 * is the book-global RESUME position (seconds); [audioTracks] are the
 * direct-play files, each with a `startOffset` on the book's global timeline.
 */
@Serializable
data class AbsPlaybackSession(
    val id: String = "",
    /** 0 = direct play, 1 = transcode. A wide mime list is sent to force 0. */
    val playMethod: Int? = null,
    val currentTime: Double? = null,
    val duration: Double? = null,
    val chapters: List<AbsChapter>? = null,
    val audioTracks: List<AbsAudioTrack>? = null,
    /** Server-computed display title for the session's item (book title). */
    val displayTitle: String? = null,
    /** The item's `media.metadata` echoed on the session (title/author). */
    val mediaMetadata: AbsMetadata? = null,
)

/**
 * One direct-play file within a playback session. [startOffset] is where this
 * file begins on the book's global timeline; [contentUrl] is SERVER-RELATIVE
 * — resolve via [AudiobookshelfApi.resolveContentUrl].
 */
@Serializable
data class AbsAudioTrack(
    val index: Int = 0,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val title: String? = null,
    val contentUrl: String = "",
)

// -----------------------------------------------------------------------------
// Batched progress upload (PATCH /api/me/progress/batch/update)
// -----------------------------------------------------------------------------

/**
 * One item's progress for `PATCH /api/me/progress/batch/update`. The request
 * body is a BARE JSON array of these.
 *
 * [episodeId] is null for books and MUST be omitted from the serialized JSON
 * entirely (not sent as `"episodeId": null`) — the default-null + default
 * `encodeDefaults = false` encoding guarantees that.
 */
@Serializable
data class AbsProgressUpdate(
    val libraryItemId: String,
    val episodeId: String? = null,
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
    /** Client wall-clock Unix millis — the server uses it for last-writer-wins. */
    val lastUpdate: Long,
) {
    companion object {
        /** Builds an update deriving `progress` from time/duration, clamped to 0..1. */
        fun of(
            libraryItemId: String,
            episodeId: String? = null,
            currentTime: Double,
            duration: Double,
            isFinished: Boolean = false,
            lastUpdate: Long = System.currentTimeMillis(),
        ): AbsProgressUpdate = AbsProgressUpdate(
            libraryItemId = libraryItemId,
            episodeId = episodeId,
            currentTime = currentTime,
            duration = duration,
            progress = if (duration > 0) (currentTime / duration).coerceIn(0.0, 1.0) else 0.0,
            isFinished = isFinished,
            lastUpdate = lastUpdate,
        )
    }
}

// -----------------------------------------------------------------------------
// Serializer helpers
// -----------------------------------------------------------------------------

/**
 * Decodes a JSON value that may arrive as either a string or a number into a
 * [String] (e.g. `ino`, which some ABS versions emit as a number).
 */
object FlexibleStringSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && !element.isString) JsonPrimitive(element.content) else element
}
