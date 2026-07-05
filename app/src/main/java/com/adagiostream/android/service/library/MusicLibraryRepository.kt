package com.adagiostream.android.service.library

import com.adagiostream.android.service.library.db.AlbumEntity
import com.adagiostream.android.service.library.db.ArtistEntity
import com.adagiostream.android.service.library.db.DownloadDao
import com.adagiostream.android.service.library.db.DownloadEntity
import com.adagiostream.android.service.library.db.LibraryDao
import com.adagiostream.android.service.library.db.toEntity
import com.adagiostream.android.service.library.db.toRecord
import com.adagiostream.android.service.navidrome.Album
import com.adagiostream.android.service.navidrome.Artist
import com.adagiostream.android.service.navidrome.NavidromeApiException
import com.adagiostream.android.service.navidrome.Track
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of a network-first browse: the data plus whether it came from the
 * offline cache (so the UI can show an "offline" indicator).
 */
data class LibraryResult<T>(val data: T, val fromCache: Boolean)

/**
 * The seam between the Navidrome network API and the Room offline cache (E6).
 *
 * Two jobs:
 *  1. **Cache** browsed/downloaded content so it can be listed without the server.
 *  2. **Offline fallback** — `network-first, cache-on-failure`: when the server is
 *     unreachable (or times out), browse returns the locally-cached library
 *     instead of crashing (baw.6.3).
 *
 * The repository is deliberately framework-light (DAOs only) so it is unit
 * tested against in-memory Room with fetch lambdas that throw.
 */
@Singleton
class MusicLibraryRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val downloadDao: DownloadDao,
) {

    // -------------------------------------------------------------------------
    // Caching
    // -------------------------------------------------------------------------

    suspend fun cacheArtists(artists: List<Artist>) {
        artists.forEach { libraryDao.upsertArtist(it.toEntity()) }
    }

    /**
     * Caches an album with its tracks for offline browse. A minimal artist row is
     * synthesised first so the album's FK is satisfied even when the artist was
     * never browsed (the common download-from-album-screen path).
     */
    suspend fun cacheAlbum(album: Album, artistName: String?, tracks: List<Track>) {
        ensureArtist(album.artistId, artistName ?: tracks.firstOrNull()?.artist)
        libraryDao.upsertAlbum(album.toEntity())
        libraryDao.upsertTracks(tracks.map { it.toEntity() })
    }

    /**
     * Minimal cache for a single track being downloaded (e.g. from a playlist):
     * synthesises artist + album rows so the FK chain holds and the track shows up
     * in the offline library.
     */
    suspend fun cacheTrackForDownload(track: Track) {
        ensureArtist(track.artistId, track.artist)
        if (libraryDao.getAlbum(track.albumId) == null) {
            libraryDao.upsertAlbum(
                AlbumEntity(
                    id = track.albumId,
                    artistId = track.artistId,
                    title = track.artist ?: "",
                ),
            )
        }
        libraryDao.upsertTrack(track.toEntity())
    }

    private suspend fun ensureArtist(artistId: String, name: String?) {
        libraryDao.upsertArtist(
            ArtistEntity(id = artistId, name = name ?: "Unknown Artist"),
        )
    }

    // -------------------------------------------------------------------------
    // Offline-fallback browse (network-first, cache-on-failure)
    // -------------------------------------------------------------------------

    suspend fun artists(fetch: suspend () -> List<Artist>): LibraryResult<List<Artist>> =
        networkFirst(
            fetch = fetch,
            cache = { cacheArtists(it) },
            fallback = { cachedArtists() },
        )

    suspend fun albumsForArtist(
        artistId: String,
        artistName: String?,
        fetch: suspend () -> List<Album>,
    ): LibraryResult<List<Album>> =
        networkFirst(
            fetch = fetch,
            cache = { albums ->
                ensureArtist(artistId, artistName)
                albums.forEach { libraryDao.upsertAlbum(it.toEntity()) }
            },
            fallback = { libraryDao.getAlbumsForArtist(artistId).map { it.toRecord() } },
        )

    suspend fun tracksForAlbum(
        album: Album,
        artistName: String?,
        fetch: suspend () -> List<Track>,
    ): LibraryResult<List<Track>> =
        networkFirst(
            fetch = fetch,
            cache = { cacheAlbum(album, artistName, it) },
            fallback = { libraryDao.getTracksForAlbum(album.id).map { it.toRecord() } },
        )

    /**
     * Runs [fetch]; on a connectivity failure ([NavidromeApiException.Unreachable]
     * or [NavidromeApiException.TimedOut]) returns the [fallback] cache marked
     * `fromCache = true`. Any other error (auth, decode, server) propagates —
     * those are not "offline" and should surface as real errors.
     */
    private suspend fun <T> networkFirst(
        fetch: suspend () -> T,
        cache: suspend (T) -> Unit,
        fallback: suspend () -> T,
    ): LibraryResult<T> = try {
        val fresh = fetch()
        cache(fresh)
        LibraryResult(fresh, fromCache = false)
    } catch (e: NavidromeApiException) {
        if (e.isOffline()) {
            LibraryResult(fallback(), fromCache = true)
        } else {
            throw e
        }
    }

    private fun NavidromeApiException.isOffline(): Boolean =
        this is NavidromeApiException.Unreachable || this is NavidromeApiException.TimedOut

    // -------------------------------------------------------------------------
    // Cache reads
    // -------------------------------------------------------------------------

    suspend fun cachedArtists(): List<Artist> = libraryDao.getArtists().map { it.toRecord() }

    /** All cached albums, sorted by title (baw.7.1 Auto browse). */
    suspend fun cachedAlbums(): List<Album> = libraryDao.getAlbums().map { it.toRecord() }

    /** Cached albums for a given artist (baw.7.1 Auto browse: music_artist_* → album list). */
    suspend fun cachedAlbumsForArtist(artistId: String): List<Album> =
        libraryDao.getAlbumsForArtist(artistId).map { it.toRecord() }

    /** Cached tracks for a given album, ordered by disc+track number (baw.7.1 Auto browse). */
    suspend fun cachedTracksForAlbum(albumId: String): List<Track> =
        libraryDao.getTracksForAlbum(albumId).map { it.toRecord() }

    /**
     * Looks up a single cached track by ID, or null when not in cache (baw.7.1
     * Auto onAddMediaItems: resolves the tapped track before queueing the album).
     */
    suspend fun cachedTrack(trackId: String): Track? = libraryDao.getTrack(trackId)?.toRecord()

    /**
     * Looks up a single cached album by ID, or null when not in cache (baw.7.1
     * Auto onAddMediaItems: resolves the album title for the now-playing metadata
     * when a track is tapped from an album's track list).
     */
    suspend fun cachedAlbum(albumId: String): Album? = libraryDao.getAlbum(albumId)?.toRecord()

    /** The offline library: tracks whose download is completed. */
    suspend fun downloadedTracks(): List<Track> =
        libraryDao.getDownloadedTracks().map { it.toRecord() }

    // -------------------------------------------------------------------------
    // Download index observation
    // -------------------------------------------------------------------------

    fun observeAllDownloads(): Flow<List<DownloadEntity>> = downloadDao.observeAll()

    fun observeCompletedDownloads(): Flow<List<DownloadEntity>> = downloadDao.observeCompleted()

    suspend fun completedDownloads(): List<DownloadEntity> = downloadDao.getCompleted()

    suspend fun downloadFor(trackId: String): DownloadEntity? = downloadDao.getById(trackId)

    // -------------------------------------------------------------------------
    // Delete-all (wired into the app-wide "delete all data" path)
    // -------------------------------------------------------------------------

    /**
     * Clears the cached library tables (artists/albums/tracks). Does NOT touch the
     * `downloads` table or files — [com.adagiostream.android.service.download.DownloadManager.deleteAll]
     * owns those so files are removed in the correct order.
     */
    suspend fun clearLibraryCache() {
        libraryDao.deleteAllTracks()
        libraryDao.deleteAllAlbums()
        libraryDao.deleteAllArtists()
    }
}
