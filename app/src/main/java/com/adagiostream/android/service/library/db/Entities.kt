package com.adagiostream.android.service.library.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ============================================================================
// navidrome_cache.db schema — v1 (E6, baw.1.1 DBA-recorded shape).
//
// This database is SEPARATE from the kotlinx.serialization JSON store
// (PersistenceService) which keeps accounts/settings/IPTV. Two stores coexist
// by design (mirrors iOS GRDB-alongside-JSON).
//
// Room annotation limitations vs the DBA's target schema (documented deviations):
//  - Partial indexes (WHERE-clause) cannot be declared via @Index → the
//    `downloads` active-status index is a plain index on `status`.
//  - SQL CHECK constraints cannot be declared via @Entity → status validity is
//    enforced in code via [DownloadStatus].
//  - Index-level COLLATE NOCASE cannot be declared via @Index → case-insensitive
//    ordering is applied at query level (ORDER BY ... COLLATE NOCASE) in the DAOs.
// ============================================================================

/**
 * Cached artist row — enables offline browse of downloaded content (baw.6.3).
 *
 * `starred`/`updatedAt` carried up-front per the DBA's "avoid ALTER later" rule.
 */
@Entity(
    tableName = "artists",
    indices = [Index(value = ["name"], name = "index_artists_name")],
)
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortName: String? = null,
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val starred: Boolean = false,
    val updatedAt: Int = 0,
)

/**
 * Cached album row. FK CASCADE to [ArtistEntity] — deleting an artist removes
 * its albums (and, transitively, their tracks).
 */
@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["artistId"], name = "index_albums_artistId"),
        Index(value = ["title"], name = "index_albums_title"),
    ],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val artistId: String,
    val title: String,
    val sortTitle: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val trackCount: Int = 0,
    val coverArt: String? = null,
    val starred: Boolean = false,
    val playCount: Int = 0,
    val updatedAt: Int = 0,
)

/**
 * Cached track row. FK CASCADE to [AlbumEntity]. `artistId` is indexed but NOT a
 * foreign key (a track can outlive a missing artist row during partial caching).
 */
@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["albumId"], name = "index_tracks_albumId"),
        Index(value = ["artistId"], name = "index_tracks_artistId"),
        Index(value = ["title"], name = "index_tracks_title"),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val albumId: String,
    val artistId: String,
    val title: String,
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
    val starred: Boolean = false,
    val playCount: Int? = null,
    val updatedAt: Int = 0,
)

/**
 * Download index row (baw.6.1).
 *
 * Keyed by the **Navidrome track id** — NOT the authenticated download URL — so
 * the row survives auth-token rotation. There is intentionally NO foreign key to
 * [TrackEntity]: a download record must survive a library-cache wipe so the file
 * on disk is never orphaned without a row to find and delete it.
 *
 * Storage accounting reads the actual file size via `File.length()`; [bytesTotal]
 * is only the server-advertised size (Content-Length / Content-Range total) used
 * for progress UI, never as the source of truth for disk usage.
 */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["status"], name = "index_downloads_status")],
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val status: String,
    val localPath: String? = null,
    val resumeOffset: Long = 0,
    val bytesTotal: Long = 0,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
