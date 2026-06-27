package com.adagiostream.android.service.navidrome

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// ============================================================================
// Subsonic domain models — port of NavidromeModels.swift
//
// Mapping strategy: DTO layer.
//
// Subsonic JSON field names differ from our domain model properties in several
// places (album.name vs album.title, song.track vs trackNumber, etc.). Rather
// than burdening the record types with conditional CodingKeys or computed
// properties, lightweight DTO data classes own the Subsonic-specific decoding
// and expose a toRecord() method that creates the corresponding domain record.
//
// "starred" in Subsonic is presence-based: the server emits a date-string when
// the item is starred and omits the key entirely when it is not. We map
// presence → true, absence → false using a nullable decode.
//
// updatedAt is a local sync timestamp (Unix epoch seconds) set at call time —
// never sourced from the server — so it is a parameter on every toRecord().
// ============================================================================

// ----------------------------------------------------------------------------
// Status envelope
// ----------------------------------------------------------------------------

/**
 * Minimal Subsonic status envelope decoded from the outer `"subsonic-response"` key.
 *
 * The key contains a hyphen which cannot be a Kotlin identifier; `@SerialName`
 * is required for kotlinx.serialization.
 *
 * Used by [NavidromeApi] to check `status` and surface [error] before
 * attempting to decode endpoint-specific payload types.
 */
@Serializable
data class SubsonicStatusEnvelope(
    @SerialName("subsonic-response")
    val response: SubsonicResponseBody,
) {
    val status: String get() = response.status
    val version: String get() = response.version
    val error: SubsonicErrorBody? get() = response.error
}

@Serializable
data class SubsonicResponseBody(
    val status: String,
    val version: String,
    val error: SubsonicErrorBody? = null,
)

@Serializable
data class SubsonicErrorBody(
    val code: Int,
    val message: String,
)

// ----------------------------------------------------------------------------
// Domain record types (immutable, no GRDB dependency — pure Kotlin foundation)
// ----------------------------------------------------------------------------

/**
 * A music artist record.
 *
 * Mirrors the iOS `Artist` GRDB record but without GRDB conformances —
 * the Android Room layer (E2 slice) will add the Room annotations.
 */
@Serializable
data class Artist(
    val id: String,
    val name: String,
    val sortName: String? = null,
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val updatedAt: Int,
)

/**
 * A music album record.
 *
 * `title` is always resolved from either the `"name"` or `"title"` Subsonic
 * field (see [SubsonicAlbumDto]).
 */
@Serializable
data class Album(
    val id: String,
    val artistId: String,
    val title: String,
    val sortTitle: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val trackCount: Int = 0,
    val coverArt: String? = null,
    val updatedAt: Int,
)

/** A music track record. */
@Serializable
data class Track(
    val id: String,
    val albumId: String,
    val artistId: String,
    val title: String,
    /** Human-readable artist display name from the Subsonic `"artist"` field. */
    val artist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int = 1,
    val duration: Int? = null,
    val genre: String? = null,
    val bitRate: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val coverArt: String? = null,
    val path: String? = null,
    /**
     * True when the track is starred/favourited in Navidrome (baw.5.2).
     * Null when the starred state is unknown (e.g. not yet fetched).
     * Architecturally separate from IPTV favourite IDs.
     */
    val starred: Boolean? = null,
    /** Subsonic play count for this track (baw.5.3). */
    val playCount: Int? = null,
    val updatedAt: Int,
)

// ----------------------------------------------------------------------------
// Genre — lightweight, no Room table in E1
// ----------------------------------------------------------------------------

/**
 * A music genre decoded from the Subsonic `getGenres` response.
 *
 * The `name` property corresponds to the JSON key `"value"` (Subsonic quirk).
 */
@Serializable
data class SubsonicGenre(
    @SerialName("value") val name: String,
    val songCount: Int,
    val albumCount: Int,
)

// Helper wrapper for test convenience when decoding a full genres envelope.
@Serializable
data class GenresWrapper(
    val genres: GenresBody,
) {
    @Serializable
    data class GenresBody(
        val genre: List<SubsonicGenre> = emptyList(),
    )
}

// ----------------------------------------------------------------------------
// Browse endpoint payload types — decoded from the full subsonic-response body
// after status-check passes.  Each type captures only the fields needed for
// its endpoint; ignoreUnknownKeys = true in the NavidromeApi Json instance
// means extra server fields are safely discarded.
// ----------------------------------------------------------------------------

/** Album list ordering type for getAlbumList2. */
enum class AlbumListType(val apiValue: String) {
    NEWEST("newest"),
    RECENT("recent"),
    FREQUENT("frequent"),
    RANDOM("random"),
    ALPHABETICAL_BY_NAME("alphabeticalByName"),
    ALPHABETICAL_BY_ARTIST("alphabeticalByArtist"),
    STARRED("starred"),
}

/**
 * Full Subsonic envelope for getArtists.
 *
 * Structure:
 * ```
 * {"subsonic-response": {"status": "ok", ..., "artists": {"index": [{"name": "A", "artist": [...]}]}}}
 * ```
 */
@Serializable
data class GetArtistsPayload(
    @SerialName("subsonic-response") val response: Body,
) {
    @Serializable
    data class Body(
        val status: String = "",
        val error: SubsonicErrorBody? = null,
        val artists: ArtistsIndex? = null,
    )

    @Serializable
    data class ArtistsIndex(
        val index: List<ArtistIndexBucket> = emptyList(),
    )

    @Serializable
    data class ArtistIndexBucket(
        val name: String = "",
        @SerialName("artist") val artists: List<SubsonicArtistDto> = emptyList(),
    )
}

/**
 * Full Subsonic envelope for getArtist.
 *
 * Structure:
 * ```
 * {"subsonic-response": {"status": "ok", ..., "artist": {"id":…,"name":…,"album":[…]}}}
 * ```
 */
@Serializable
data class GetArtistPayload(
    @SerialName("subsonic-response") val response: Body,
) {
    @Serializable
    data class Body(
        val status: String = "",
        val error: SubsonicErrorBody? = null,
        val artist: ArtistWithAlbums? = null,
    )

    @Serializable
    data class ArtistWithAlbums(
        val id: String,
        val name: String,
        val albumCount: Int? = null,
        val coverArt: String? = null,
        @SerialName("starred")
        @Serializable(with = StarredPresenceSerializer::class)
        val starred: Boolean = false,
        @SerialName("album") val albums: List<SubsonicAlbumDto> = emptyList(),
    ) {
        fun toArtistRecord(updatedAt: Int): Artist = Artist(
            id = id,
            name = name,
            albumCount = albumCount ?: 0,
            coverArt = coverArt,
            updatedAt = updatedAt,
        )
    }
}

/**
 * Full Subsonic envelope for getAlbum.
 *
 * Structure:
 * ```
 * {"subsonic-response": {"status": "ok", ..., "album": {"id":…,"title":…,"artist":…,"song":[…]}}}
 * ```
 *
 * [AlbumWithTracks.artistName] carries the human-readable artist display name
 * from the Subsonic `"artist"` string field (distinct from `"artistId"`).  The
 * iOS bug that showed `artistId` in the now-playing subtitle stemmed from not
 * threading this field through — never use `artistId` for display.
 */
@Serializable
data class GetAlbumPayload(
    @SerialName("subsonic-response") val response: Body,
) {
    @Serializable
    data class Body(
        val status: String = "",
        val error: SubsonicErrorBody? = null,
        val album: AlbumWithTracks? = null,
    )

    @Serializable
    data class AlbumWithTracks(
        val id: String,
        val artistId: String,
        /** Human-readable artist name from the `"artist"` field — use this for display, not artistId. */
        @SerialName("artist") val artistName: String? = null,
        @SerialName("name") val nameField: String? = null,
        @SerialName("title") val titleField: String? = null,
        val year: Int? = null,
        val genre: String? = null,
        val songCount: Int? = null,
        val coverArt: String? = null,
        @SerialName("starred")
        @Serializable(with = StarredPresenceSerializer::class)
        val starred: Boolean = false,
        @SerialName("song") val songs: List<SubsonicTrackDto> = emptyList(),
    ) {
        val resolvedTitle: String get() = titleField ?: nameField ?: ""

        fun toAlbumRecord(updatedAt: Int): Album = Album(
            id = id,
            artistId = artistId,
            title = resolvedTitle,
            year = year,
            genre = genre,
            trackCount = songCount ?: songs.size,
            coverArt = coverArt,
            updatedAt = updatedAt,
        )
    }
}

/**
 * Full Subsonic envelope for getAlbumList2.
 *
 * Structure:
 * ```
 * {"subsonic-response": {"status": "ok", ..., "albumList2": {"album": [...]}}}
 * ```
 */
@Serializable
data class GetAlbumListPayload(
    @SerialName("subsonic-response") val response: Body,
) {
    @Serializable
    data class Body(
        val status: String = "",
        val error: SubsonicErrorBody? = null,
        val albumList2: AlbumList? = null,
    )

    @Serializable
    data class AlbumList(
        @SerialName("album") val albums: List<SubsonicAlbumDto> = emptyList(),
    )
}

/**
 * Full Subsonic envelope for getGenres.
 *
 * Structure:
 * ```
 * {"subsonic-response": {"status": "ok", ..., "genres": {"genre": [...]}}}
 * ```
 */
@Serializable
data class GetGenresPayload(
    @SerialName("subsonic-response") val response: Body,
) {
    @Serializable
    data class Body(
        val status: String = "",
        val error: SubsonicErrorBody? = null,
        val genres: GenresList? = null,
    )

    @Serializable
    data class GenresList(
        @SerialName("genre") val genres: List<SubsonicGenre> = emptyList(),
    )
}

/**
 * Full Subsonic envelope for getSongsByGenre.
 *
 * Structure:
 * ```
 * {"subsonic-response": {"status": "ok", ..., "songsByGenre": {"song": [...]}}}
 * ```
 */
@Serializable
data class GetSongsByGenrePayload(
    @SerialName("subsonic-response") val response: Body,
) {
    @Serializable
    data class Body(
        val status: String = "",
        val error: SubsonicErrorBody? = null,
        val songsByGenre: SongsList? = null,
    )

    @Serializable
    data class SongsList(
        @SerialName("song") val songs: List<SubsonicTrackDto> = emptyList(),
    )
}

// ----------------------------------------------------------------------------
// DTO layer — handles Subsonic JSON quirks before producing domain records
// ----------------------------------------------------------------------------

/**
 * Decodes a single artist entry from Subsonic `getArtists` / `getIndexes`.
 *
 * `starred` is derived from the presence of the `"starred"` date-string field:
 * present (any non-null value) → true; absent → false.
 */
@Serializable
data class SubsonicArtistDto(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    /**
     * True when the Subsonic `"starred"` date-string field is present.
     * Use a custom serializer to map presence → true, absence → false.
     */
    @SerialName("starred")
    @Serializable(with = StarredPresenceSerializer::class)
    val starred: Boolean = false,
) {
    fun toRecord(updatedAt: Int): Artist = Artist(
        id = id,
        name = name,
        albumCount = albumCount ?: 0,
        coverArt = coverArt,
        updatedAt = updatedAt,
    )
}

/**
 * Decodes a single album entry from Subsonic `getArtist`, `getAlbumList2`,
 * or `getAlbum`.
 *
 * Title duality:
 *   - `getArtist` / `getAlbumList2` → `"name"` field
 *   - `getAlbum` detail             → `"title"` field
 * [resolvedTitle] picks `title` when present; falls back to `name`.
 */
@Serializable
data class SubsonicAlbumDto(
    val id: String,
    val artistId: String,
    /** Artist display name from the Subsonic `"artist"` string field. */
    @SerialName("artist") val artistName: String? = null,
    /** Album title as returned by list endpoints (getArtist / getAlbumList2). */
    @SerialName("name") val nameField: String? = null,
    /** Album title as returned by the detail endpoint (getAlbum). */
    @SerialName("title") val titleField: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val songCount: Int? = null,
    val coverArt: String? = null,
    @SerialName("starred")
    @Serializable(with = StarredPresenceSerializer::class)
    val starred: Boolean = false,
    val userRating: Int? = null,
    val playCount: Int? = null,
) {
    /** Resolved album title: `title` field wins over `name` field. */
    val resolvedTitle: String get() = titleField ?: nameField ?: ""

    fun toRecord(updatedAt: Int): Album = Album(
        id = id,
        artistId = artistId,
        title = resolvedTitle,
        year = year,
        genre = genre,
        trackCount = songCount ?: 0,
        coverArt = coverArt,
        updatedAt = updatedAt,
    )
}

/**
 * Decodes a single song/track entry from Subsonic `getAlbum` / `getSong`.
 *
 * Key mismatches handled:
 *   - `"track"` → [trackNumber]
 *   - `"artist"` (name string) → [artistName]
 */
@Serializable
data class SubsonicTrackDto(
    val id: String,
    val albumId: String,
    val artistId: String,
    val title: String,
    /** Human-readable artist display name from the Subsonic `"artist"` field. */
    @SerialName("artist") val artistName: String? = null,
    /** Track number from the Subsonic `"track"` field. */
    @SerialName("track") val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val bitRate: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val path: String? = null,
    @SerialName("starred")
    @Serializable(with = StarredPresenceSerializer::class)
    val starred: Boolean = false,
    val userRating: Int? = null,
    val playCount: Int? = null,
) {
    fun toRecord(updatedAt: Int): Track = Track(
        id = id,
        albumId = albumId,
        artistId = artistId,
        title = title,
        artist = artistName,
        trackNumber = trackNumber,
        discNumber = discNumber ?: 1,
        duration = duration,
        genre = genre,
        bitRate = bitRate,
        suffix = suffix,
        contentType = contentType,
        coverArt = coverArt,
        path = path,
        starred = starred,
        playCount = playCount,
        updatedAt = updatedAt,
    )
}

// ============================================================================
// Navidrome Playlist models (baw.4.2 / baw.4.3)
//
// IMPORTANT naming: NavidromePlaylist is intentionally distinct from the
// IPTV M3U CustomPlaylist system (service/m3u/). These two systems are
// entirely separate — Navidrome playlists are server-side Subsonic playlists,
// not local M3U-derived channel groups.
// ============================================================================

/**
 * A Navidrome / Subsonic server-side playlist.
 *
 * Name is [NavidromePlaylist] to avoid collisions with the IPTV M3U
 * [CustomPlaylist] model and the Android navigation [Playlist] route.
 */
@Serializable
data class NavidromePlaylist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val coverArt: String? = null,
    val public: Boolean = false,
    val owner: String? = null,
)

/**
 * Container for a search3 result.
 *
 * All three arrays default to empty if the server omits them — Subsonic sends
 * absent keys when the count for that type is 0.
 */
data class NavidromeSearchResult(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    /** True when all three result lists are empty. */
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

// ----------------------------------------------------------------------------
// Playlist DTO types — decode Subsonic JSON before producing domain records
// ----------------------------------------------------------------------------

/**
 * Decodes a single playlist entry from `getPlaylists` response.
 *
 * Songs are NOT included at the list level — call `getPlaylist` for tracks.
 */
@Serializable
data class SubsonicPlaylistDto(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val coverArt: String? = null,
    val public: Boolean = false,
    val owner: String? = null,
) {
    fun toRecord(): NavidromePlaylist = NavidromePlaylist(
        id = id,
        name = name,
        songCount = songCount,
        coverArt = coverArt,
        public = public,
        owner = owner,
    )
}

/**
 * Decodes a full playlist detail from `getPlaylist`.
 *
 * Tracks are under the `"entry"` key (not `"song"` as in album responses).
 */
@Serializable
data class SubsonicPlaylistDetailDto(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val coverArt: String? = null,
    val public: Boolean = false,
    val owner: String? = null,
    /** Subsonic uses "entry" for playlist song items (not "song"). */
    @SerialName("entry") val songs: List<SubsonicTrackDto> = emptyList(),
) {
    fun toRecord(): NavidromePlaylist = NavidromePlaylist(
        id = id,
        name = name,
        songCount = songCount,
        coverArt = coverArt,
        public = public,
        owner = owner,
    )
}

// ----------------------------------------------------------------------------
// Playlist payload types
// ----------------------------------------------------------------------------

/**
 * Full Subsonic envelope for getPlaylists.
 *
 * Structure:
 * ```
 * {"subsonic-response": {"status":"ok", ..., "playlists": {"playlist":[...]}}}
 * ```
 */
@Serializable
data class GetPlaylistsPayload(
    @SerialName("subsonic-response") val response: Body,
) {
    @Serializable
    data class Body(
        val status: String = "",
        val error: SubsonicErrorBody? = null,
        val playlists: PlaylistsBody? = null,
    )

    @Serializable
    data class PlaylistsBody(
        @SerialName("playlist") val items: List<SubsonicPlaylistDto> = emptyList(),
    )
}

/**
 * Full Subsonic envelope for getPlaylist.
 *
 * Structure:
 * ```
 * {"subsonic-response": {"status":"ok", ..., "playlist": {"id":…, "entry":[...]}}}
 * ```
 */
@Serializable
data class GetPlaylistPayload(
    @SerialName("subsonic-response") val response: Body,
) {
    @Serializable
    data class Body(
        val status: String = "",
        val error: SubsonicErrorBody? = null,
        val playlist: SubsonicPlaylistDetailDto? = null,
    )
}

// ----------------------------------------------------------------------------
// Search payload types (baw.4.1)
// ----------------------------------------------------------------------------

/**
 * Full Subsonic envelope for search3.
 *
 * Structure:
 * ```
 * {"subsonic-response": {"status":"ok", ..., "searchResult3": {"artist":[...],"album":[...],"song":[...]}}}
 * ```
 *
 * Any of the arrays may be absent (Subsonic omits them when count=0 was
 * requested); they all default to empty lists.
 */
@Serializable
data class SearchResult3Payload(
    @SerialName("subsonic-response") val response: Body,
) {
    @Serializable
    data class Body(
        val status: String = "",
        val error: SubsonicErrorBody? = null,
        val searchResult3: SearchResult3Body? = null,
    )

    @Serializable
    data class SearchResult3Body(
        @SerialName("artist") val artists: List<SubsonicArtistDto> = emptyList(),
        @SerialName("album") val albums: List<SubsonicAlbumDto> = emptyList(),
        @SerialName("song") val songs: List<SubsonicTrackDto> = emptyList(),
    )
}

// ----------------------------------------------------------------------------
// Custom serializer: starred presence → boolean
// ----------------------------------------------------------------------------

/**
 * Serializer that maps the Subsonic `starred` field to a [Boolean].
 *
 * The Subsonic protocol sends a date-string (e.g. `"2024-01-15T12:00:00"`)
 * when an item is starred and omits the key entirely when it is not.
 * Since the field uses `@Serializable(with = ...)`, kotlinx.serialization
 * calls this for the value. When the JSON key is absent the default `false`
 * from the data-class default is used instead.
 *
 * This serializer handles the presence case: any non-null string → true.
 */
object StarredPresenceSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StarredPresence", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Boolean) {
        // Not needed for read-only decode; write empty string for true
        encoder.encodeString(if (value) "starred" else "")
    }

    override fun deserialize(decoder: Decoder): Boolean {
        // Any non-null value means starred; the key being absent means false
        // (handled by the data-class default = false).
        val raw = decoder.decodeString()
        return raw.isNotEmpty()
    }
}
