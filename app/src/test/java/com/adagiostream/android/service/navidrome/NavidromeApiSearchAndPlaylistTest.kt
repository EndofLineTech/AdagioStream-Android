package com.adagiostream.android.service.navidrome

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * NavidromeApi search3 + playlist CRUD endpoint tests (baw.4.1, baw.4.2, baw.4.3).
 *
 * Covers:
 *  - search3: decodes artists/albums/songs; handles missing arrays gracefully
 *  - getPlaylists: decodes playlist list
 *  - getPlaylist: decodes playlist detail with tracks from "entry" key
 *  - createPlaylist: status-only ok response doesn't throw
 *  - updatePlaylist: repeated songIdToAdd params sent correctly
 *  - deletePlaylist: status-only ok response doesn't throw
 */
class NavidromeApiSearchAndPlaylistTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NavidromeApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        api = NavidromeApi(
            client = client,
            host = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "sesame",
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun mockOk(body: String): MockResponse =
        MockResponse.Builder().code(200).body(body).build()

    // -------------------------------------------------------------------------
    // search3 (baw.4.1)
    // -------------------------------------------------------------------------

    @Test
    fun `search3 decodes artists albums and songs from server response`() = runTest {
        server.enqueue(mockOk(SEARCH3_FULL_FIXTURE))

        val result = api.search3("radiohead")

        assertEquals(1, result.artists.size)
        assertEquals("Radiohead", result.artists.first().name)
        assertEquals(2, result.albums.size)
        assertEquals(3, result.tracks.size)
    }

    @Test
    fun `search3 returns empty lists when artists array is absent`() = runTest {
        server.enqueue(mockOk(SEARCH3_MISSING_ARTISTS_FIXTURE))

        val result = api.search3("rock")

        // Must not throw — missing artists → empty list
        assertTrue("artists should be empty", result.artists.isEmpty())
        assertEquals(1, result.albums.size)
        assertEquals(1, result.tracks.size)
    }

    @Test
    fun `search3 returns empty lists when all arrays are absent`() = runTest {
        server.enqueue(mockOk(SEARCH3_EMPTY_FIXTURE))

        val result = api.search3("zzz")

        assertTrue(result.artists.isEmpty())
        assertTrue(result.albums.isEmpty())
        assertTrue(result.tracks.isEmpty())
        assertTrue(result.isEmpty)
    }

    @Test
    fun `search3 song artist field is human readable name not artistId`() = runTest {
        server.enqueue(mockOk(SEARCH3_FULL_FIXTURE))

        val result = api.search3("radiohead")

        // The "artist" field in the JSON is the display name "Radiohead", not "ar1"
        val track = result.tracks.first()
        assertEquals("Radiohead", track.artist)
        assertFalse(
            "artist on track must never be the artistId",
            track.artist == track.artistId,
        )
    }

    @Test
    fun `search3 passes query and count params`() = runTest {
        server.enqueue(mockOk(SEARCH3_EMPTY_FIXTURE))

        api.search3(query = "blur", artistCount = 5, albumCount = 10, songCount = 20)

        val request = server.takeRequest()
        val url = request.url
        assertEquals("blur", url.queryParameter("query"))
        assertEquals("5", url.queryParameter("artistCount"))
        assertEquals("10", url.queryParameter("albumCount"))
        assertEquals("20", url.queryParameter("songCount"))
    }

    // -------------------------------------------------------------------------
    // getPlaylists (baw.4.2)
    // -------------------------------------------------------------------------

    @Test
    fun `getPlaylists decodes list of playlists`() = runTest {
        server.enqueue(mockOk(GET_PLAYLISTS_FIXTURE))

        val playlists = api.getPlaylists()

        assertEquals(2, playlists.size)
        assertEquals("My Mix", playlists[0].name)
        assertEquals("Workout Jams", playlists[1].name)
        assertEquals(10, playlists[0].songCount)
    }

    @Test
    fun `getPlaylists returns empty list when playlists is absent`() = runTest {
        server.enqueue(mockOk(GET_PLAYLISTS_EMPTY_FIXTURE))

        val playlists = api.getPlaylists()

        assertTrue(playlists.isEmpty())
    }

    // -------------------------------------------------------------------------
    // getPlaylist (baw.4.2)
    // -------------------------------------------------------------------------

    @Test
    fun `getPlaylist decodes playlist detail with tracks from entry key`() = runTest {
        server.enqueue(mockOk(GET_PLAYLIST_DETAIL_FIXTURE))

        val (playlist, tracks) = api.getPlaylist("p1")

        assertEquals("p1", playlist.id)
        assertEquals("My Mix", playlist.name)
        assertEquals(2, tracks.size)
        assertEquals("Song One", tracks[0].title)
        assertEquals("Song Two", tracks[1].title)
    }

    @Test
    fun `getPlaylist track artist field is human readable name`() = runTest {
        server.enqueue(mockOk(GET_PLAYLIST_DETAIL_FIXTURE))

        val (_, tracks) = api.getPlaylist("p1")

        // "artist" in each entry is the display name, not artistId
        tracks.forEach { track ->
            assertEquals("Pink Floyd", track.artist)
        }
    }

    @Test
    fun `getPlaylist returns empty tracks when entry array is absent`() = runTest {
        server.enqueue(mockOk(GET_PLAYLIST_EMPTY_TRACKS_FIXTURE))

        val (playlist, tracks) = api.getPlaylist("p2")

        assertEquals("Empty Playlist", playlist.name)
        assertTrue(tracks.isEmpty())
    }

    // -------------------------------------------------------------------------
    // createPlaylist (baw.4.3)
    // -------------------------------------------------------------------------

    @Test
    fun `createPlaylist does not throw on status-only ok response`() = runTest {
        // Server returns bare status-ok with no playlist body
        server.enqueue(mockOk(STATUS_OK_FIXTURE))

        // Must not throw
        api.createPlaylist("New Playlist")
        // Success means no exception — test passes
    }

    @Test
    fun `createPlaylist passes name parameter`() = runTest {
        server.enqueue(mockOk(STATUS_OK_FIXTURE))

        api.createPlaylist("Rock Classics")

        val request = server.takeRequest()
        val url = request.url
        assertEquals("Rock Classics", url.queryParameter("name"))
    }

    // -------------------------------------------------------------------------
    // updatePlaylist (baw.4.3)
    // -------------------------------------------------------------------------

    @Test
    fun `updatePlaylist sends repeated songIdToAdd params for multiple song ids`() = runTest {
        server.enqueue(mockOk(STATUS_OK_FIXTURE))

        api.updatePlaylist(id = "p1", songIdsToAdd = listOf("s1", "s2", "s3"))

        val request = server.takeRequest()
        val url = request.url
        // Must use repeated params, not a single joined value
        val addedIds = url.queryParameterValues("songIdToAdd")
        assertEquals(listOf("s1", "s2", "s3"), addedIds)
    }

    @Test
    fun `updatePlaylist sends repeated songIndexToRemove params`() = runTest {
        server.enqueue(mockOk(STATUS_OK_FIXTURE))

        api.updatePlaylist(id = "p1", indicesToRemove = listOf(0, 2))

        val request = server.takeRequest()
        val url = request.url
        val removedIndices = url.queryParameterValues("songIndexToRemove")
        assertEquals(listOf("0", "2"), removedIndices)
    }

    @Test
    fun `updatePlaylist sends name when provided`() = runTest {
        server.enqueue(mockOk(STATUS_OK_FIXTURE))

        api.updatePlaylist(id = "p1", name = "Renamed Mix")

        val request = server.takeRequest()
        val url = request.url
        assertEquals("p1", url.queryParameter("playlistId"))
        assertEquals("Renamed Mix", url.queryParameter("name"))
    }

    @Test
    fun `updatePlaylist does not include name param when null`() = runTest {
        server.enqueue(mockOk(STATUS_OK_FIXTURE))

        api.updatePlaylist(id = "p1", name = null)

        val request = server.takeRequest()
        val url = request.url
        // "name" param should not appear when null
        assertTrue(
            "name param should be absent when null",
            url.queryParameter("name") == null,
        )
    }

    // -------------------------------------------------------------------------
    // deletePlaylist (baw.4.3)
    // -------------------------------------------------------------------------

    @Test
    fun `deletePlaylist does not throw on status-ok response`() = runTest {
        server.enqueue(mockOk(STATUS_OK_FIXTURE))

        // Must not throw
        api.deletePlaylist("p1")
    }

    @Test
    fun `deletePlaylist passes id parameter`() = runTest {
        server.enqueue(mockOk(STATUS_OK_FIXTURE))

        api.deletePlaylist("p42")

        val request = server.takeRequest()
        val url = request.url
        assertEquals("p42", url.queryParameter("id"))
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    companion object {

        private const val STATUS_OK_FIXTURE =
            """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""

        private const val SEARCH3_FULL_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "searchResult3": {
                  "artist": [
                    {"id":"ar1","name":"Radiohead","albumCount":9}
                  ],
                  "album": [
                    {"id":"al1","artistId":"ar1","artist":"Radiohead","name":"OK Computer","year":1997},
                    {"id":"al2","artistId":"ar1","artist":"Radiohead","name":"Kid A","year":2000}
                  ],
                  "song": [
                    {"id":"s1","albumId":"al1","artistId":"ar1","title":"Airbag","artist":"Radiohead","track":1,"duration":229},
                    {"id":"s2","albumId":"al1","artistId":"ar1","title":"Paranoid Android","artist":"Radiohead","track":2,"duration":387},
                    {"id":"s3","albumId":"al1","artistId":"ar1","title":"Karma Police","artist":"Radiohead","track":3,"duration":261}
                  ]
                }
              }
            }
        """

        /**
         * Missing artists array — should default to empty, not throw.
         */
        private const val SEARCH3_MISSING_ARTISTS_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "searchResult3": {
                  "album": [
                    {"id":"al1","artistId":"ar1","name":"Dark Side of the Moon","year":1973}
                  ],
                  "song": [
                    {"id":"s1","albumId":"al1","artistId":"ar1","title":"Money","artist":"Pink Floyd","duration":382}
                  ]
                }
              }
            }
        """

        /**
         * Empty searchResult3 — all arrays absent.
         */
        private const val SEARCH3_EMPTY_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "searchResult3": {}
              }
            }
        """

        private const val GET_PLAYLISTS_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "playlists": {
                  "playlist": [
                    {"id":"p1","name":"My Mix","songCount":10,"coverArt":"pl-p1"},
                    {"id":"p2","name":"Workout Jams","songCount":25,"coverArt":"pl-p2"}
                  ]
                }
              }
            }
        """

        private const val GET_PLAYLISTS_EMPTY_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "playlists": {}
              }
            }
        """

        private const val GET_PLAYLIST_DETAIL_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "playlist": {
                  "id": "p1",
                  "name": "My Mix",
                  "songCount": 2,
                  "entry": [
                    {"id":"s1","albumId":"al1","artistId":"ar1","title":"Song One","artist":"Pink Floyd","track":1,"duration":240},
                    {"id":"s2","albumId":"al1","artistId":"ar1","title":"Song Two","artist":"Pink Floyd","track":2,"duration":300}
                  ]
                }
              }
            }
        """

        private const val GET_PLAYLIST_EMPTY_TRACKS_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "playlist": {
                  "id": "p2",
                  "name": "Empty Playlist",
                  "songCount": 0
                }
              }
            }
        """
    }
}
