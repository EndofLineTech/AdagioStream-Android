package com.adagiostream.android.service.library.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO for the cached music-library tables (artists/albums/tracks) that back
 * offline browse (baw.6.3). COLLATE NOCASE is applied at query level because
 * Room's `@Index` cannot declare index collation.
 */
@Dao
interface LibraryDao {

    // ---- writes ----------------------------------------------------------

    // @Upsert, NEVER @Insert(onConflict = REPLACE): REPLACE is DELETE+INSERT at
    // the SQLite level, which fires the ON DELETE CASCADE on albums/tracks —
    // every artist/album re-cache silently wiped the cached tracks beneath it,
    // orphaning download rows (beads_adagio-6q3).
    @Upsert
    suspend fun upsertArtist(artist: ArtistEntity)

    @Upsert
    suspend fun upsertAlbum(album: AlbumEntity)

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Upsert
    suspend fun upsertTrack(track: TrackEntity)

    // ---- reads -----------------------------------------------------------

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    suspend fun getArtists(): List<ArtistEntity>

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year ASC, title COLLATE NOCASE ASC")
    suspend fun getAlbumsForArtist(artistId: String): List<AlbumEntity>

    @Query("SELECT * FROM albums ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAlbums(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :albumId")
    suspend fun getAlbum(albumId: String): AlbumEntity?

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNumber ASC, trackNumber ASC")
    suspend fun getTracksForAlbum(albumId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun getTrack(trackId: String): TrackEntity?

    /**
     * Tracks that have a completed download, newest first — the offline library
     * listing. Joins `tracks` to `downloads` so only on-disk content is returned.
     */
    @Query(
        """
        SELECT tracks.* FROM tracks
        INNER JOIN downloads ON downloads.id = tracks.id
        WHERE downloads.status = 'completed'
        ORDER BY downloads.updatedAt DESC
        """,
    )
    suspend fun getDownloadedTracks(): List<TrackEntity>

    // ---- delete-all (wired into the "delete all data" path) --------------

    @Query("DELETE FROM tracks")
    suspend fun deleteAllTracks()

    @Query("DELETE FROM albums")
    suspend fun deleteAllAlbums()

    @Query("DELETE FROM artists")
    suspend fun deleteAllArtists()
}
