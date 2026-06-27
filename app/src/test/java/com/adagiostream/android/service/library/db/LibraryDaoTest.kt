package com.adagiostream.android.service.library.db

import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * LibraryDao coverage: cache writes, offline browse ordering, FK CASCADE, and the
 * downloaded-tracks join (baw.6.3 TDD).
 */
@RunWith(RobolectricTestRunner::class)
class LibraryDaoTest {

    private lateinit var db: NavidromeCacheDatabase
    private lateinit var lib: LibraryDao
    private lateinit var downloads: DownloadDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            NavidromeCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        // CASCADE depends on foreign_keys = ON.
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        lib = db.libraryDao()
        downloads = db.downloadDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedArtistAlbumTracks() {
        lib.upsertArtist(ArtistEntity(id = "ar1", name = "Beatles"))
        lib.upsertAlbum(AlbumEntity(id = "al1", artistId = "ar1", title = "Abbey Road", year = 1969))
        lib.upsertTracks(
            listOf(
                TrackEntity(id = "t2", albumId = "al1", artistId = "ar1", title = "Something", trackNumber = 2),
                TrackEntity(id = "t1", albumId = "al1", artistId = "ar1", title = "Come Together", trackNumber = 1),
            ),
        )
    }

    @Test
    fun `getTracksForAlbum returns tracks ordered by disc then track number`() = runTest {
        seedArtistAlbumTracks()
        val tracks = lib.getTracksForAlbum("al1").map { it.id }
        assertEquals(listOf("t1", "t2"), tracks)
    }

    @Test
    fun `getArtists sorts case-insensitively`() = runTest {
        lib.upsertArtist(ArtistEntity(id = "b", name = "abba"))
        lib.upsertArtist(ArtistEntity(id = "a", name = "ZZ Top"))
        lib.upsertArtist(ArtistEntity(id = "c", name = "Beatles"))
        val names = lib.getArtists().map { it.name }
        assertEquals(listOf("abba", "Beatles", "ZZ Top"), names)
    }

    @Test
    fun `deleting an artist cascades to albums and tracks`() = runTest {
        seedArtistAlbumTracks()
        db.openHelper.writableDatabase.execSQL("DELETE FROM artists WHERE id = 'ar1'")
        assertNull(lib.getAlbum("al1"))
        assertTrue(lib.getTracksForAlbum("al1").isEmpty())
    }

    @Test
    fun `getDownloadedTracks returns only tracks with a completed download`() = runTest {
        seedArtistAlbumTracks()
        downloads.upsert(
            DownloadEntity(id = "t1", status = DownloadStatus.COMPLETED, localPath = "/m/t1.mp3", createdAt = 1, updatedAt = 5),
        )
        downloads.upsert(
            DownloadEntity(id = "t2", status = DownloadStatus.QUEUED, createdAt = 1, updatedAt = 2),
        )
        val downloaded = lib.getDownloadedTracks().map { it.id }
        assertEquals(listOf("t1"), downloaded)
    }

    @Test
    fun `deleteAll tables empties the cache`() = runTest {
        seedArtistAlbumTracks()
        lib.deleteAllTracks()
        lib.deleteAllAlbums()
        lib.deleteAllArtists()
        assertTrue(lib.getArtists().isEmpty())
        assertTrue(lib.getAlbums().isEmpty())
    }
}
