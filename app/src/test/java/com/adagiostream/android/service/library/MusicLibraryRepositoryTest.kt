package com.adagiostream.android.service.library

import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import com.adagiostream.android.service.library.db.DownloadEntity
import com.adagiostream.android.service.library.db.DownloadStatus
import com.adagiostream.android.service.library.db.NavidromeCacheDatabase
import com.adagiostream.android.service.navidrome.Album
import com.adagiostream.android.service.navidrome.Artist
import com.adagiostream.android.service.navidrome.NavidromeApiException
import com.adagiostream.android.testutil.TestFixtures
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Offline-fallback browse + caching for the library repository (baw.6.3 TDD).
 */
@RunWith(RobolectricTestRunner::class)
class MusicLibraryRepositoryTest {

    private lateinit var db: NavidromeCacheDatabase
    private lateinit var repo: MusicLibraryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            NavidromeCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = MusicLibraryRepository(db.libraryDao(), db.downloadDao())
    }

    @After
    fun tearDown() = db.close()

    private fun artist(id: String, name: String) = Artist(id = id, name = name, updatedAt = 0)

    @Test
    fun `artists network success caches and reports fromCache false`() = runTest {
        val fresh = listOf(artist("a1", "Beatles"), artist("a2", "Bowie"))
        val result = repo.artists { fresh }

        assertFalse(result.fromCache)
        assertEquals(fresh, result.data)
        // Cached for next time.
        assertEquals(setOf("a1", "a2"), repo.cachedArtists().map { it.id }.toSet())
    }

    @Test
    fun `artists falls back to cache when server is unreachable`() = runTest {
        repo.cacheArtists(listOf(artist("a1", "Beatles")))

        val result = repo.artists {
            throw NavidromeApiException.Unreachable(java.io.IOException("no network"))
        }

        assertTrue(result.fromCache)
        assertEquals(listOf("a1"), result.data.map { it.id })
    }

    @Test
    fun `artists falls back to cache on timeout`() = runTest {
        repo.cacheArtists(listOf(artist("a1", "Beatles")))
        val result = repo.artists { throw NavidromeApiException.TimedOut }
        assertTrue(result.fromCache)
        assertEquals(1, result.data.size)
    }

    @Test
    fun `non-connectivity errors propagate rather than masking as offline`() = runTest {
        repo.cacheArtists(listOf(artist("a1", "Beatles")))
        assertThrows(NavidromeApiException.AuthFailed::class.java) {
            kotlinx.coroutines.runBlocking {
                repo.artists { throw NavidromeApiException.AuthFailed }
            }
        }
    }

    @Test
    fun `tracksForAlbum offline fallback lists cached album tracks`() = runTest {
        val album = Album(id = "al1", artistId = "ar1", title = "Abbey Road", updatedAt = 0)
        val tracks = listOf(
            TestFixtures.makeTrack(id = "t1", albumId = "al1", artistId = "ar1", trackNumber = 1),
            TestFixtures.makeTrack(id = "t2", albumId = "al1", artistId = "ar1", trackNumber = 2),
        )
        // Prime the cache via a successful fetch.
        repo.tracksForAlbum(album, "Beatles") { tracks }

        // Now go offline.
        val result = repo.tracksForAlbum(album, "Beatles") {
            throw NavidromeApiException.Unreachable(java.io.IOException("down"))
        }
        assertTrue(result.fromCache)
        assertEquals(listOf("t1", "t2"), result.data.map { it.id })
    }

    @Test
    fun `downloadedTracks lists only completed downloads`() = runTest {
        val album = Album(id = "al1", artistId = "ar1", title = "A", updatedAt = 0)
        val tracks = listOf(
            TestFixtures.makeTrack(id = "t1", albumId = "al1", artistId = "ar1"),
            TestFixtures.makeTrack(id = "t2", albumId = "al1", artistId = "ar1"),
        )
        repo.cacheAlbum(album, "Beatles", tracks)
        db.downloadDao().upsert(
            DownloadEntity(id = "t1", status = DownloadStatus.COMPLETED, localPath = "/m/t1", createdAt = 1, updatedAt = 9),
        )

        val downloaded = repo.downloadedTracks().map { it.id }
        assertEquals(listOf("t1"), downloaded)
    }

    @Test
    fun `cacheTrackForDownload synthesises artist and album so the track persists`() = runTest {
        val track = TestFixtures.makeTrack(id = "t9", albumId = "alX", artistId = "arX", artist = "Solo")
        repo.cacheTrackForDownload(track)
        db.downloadDao().upsert(
            DownloadEntity(id = "t9", status = DownloadStatus.COMPLETED, localPath = "/m/t9", createdAt = 1, updatedAt = 1),
        )
        assertEquals(listOf("t9"), repo.downloadedTracks().map { it.id })
    }
}
