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
        updatedAt = updatedAt,
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
