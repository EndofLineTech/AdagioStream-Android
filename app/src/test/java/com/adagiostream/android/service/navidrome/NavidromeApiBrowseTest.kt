package com.adagiostream.android.service.navidrome

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * NavidromeApi browse-endpoint tests using MockWebServer (baw.2.1).
 *
 * Each test:
 *  1. Enqueues an inline JSON fixture that mirrors the real Navidrome API shape.
 *  2. Calls the browse method.
 *  3. Asserts on the returned domain models.
 *
 * Covered endpoints: getArtists, getArtist, getAlbum, getAlbumList2,
 * getGenres, getSongsByGenre.
 */
class NavidromeApiBrowseTest {

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
    // getArtists
    // -------------------------------------------------------------------------

    @Test
    fun `getArtists flattens index buckets into flat list`() = runTest {
        server.enqueue(mockOk(GET_ARTISTS_FIXTURE))

        val artists = api.getArtists()

        assertEquals(3, artists.size)
        val names = artists.map { it.name }
        assertTrue(names.contains("Arcade Fire"))
        assertTrue(names.contains("Blur"))
        assertTrue(names.contains("Coldplay"))
    }

    @Test
    fun `getArtists maps coverArt correctly`() = runTest {
        server.enqueue(mockOk(GET_ARTISTS_FIXTURE))

        val artists = api.getArtists()

        val arcadeFire = artists.first { it.name == "Arcade Fire" }
        assertEquals("art-ar1", arcadeFire.coverArt)
    }

    @Test
    fun `getArtists albumCount is mapped from DTO`() = runTest {
        server.enqueue(mockOk(GET_ARTISTS_FIXTURE))

        val artists = api.getArtists()

        val blur = artists.first { it.name == "Blur" }
        assertEquals(7, blur.albumCount)
    }

    @Test
    fun `getArtists returns empty list when artists body absent`() = runTest {
        server.enqueue(mockOk("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))

        val artists = api.getArtists()

        assertTrue(artists.isEmpty())
    }

    @Test
    fun `getArtists request targets rest slash getArtists dot view`() = runTest {
        server.enqueue(mockOk(GET_ARTISTS_FIXTURE))

        api.getArtists()

        val request = server.takeRequest()
        assertEquals("/rest/getArtists.view", request.url.encodedPath)
    }

    @Test
    fun `getArtists throws AuthFailed on error code 40`() = runTest {
        server.enqueue(
            mockOk("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong credentials"}}}"""),
        )

        try {
            api.getArtists()
            org.junit.Assert.fail("Expected AuthFailed")
        } catch (e: NavidromeApiException.AuthFailed) {
            // expected
        }
    }

    // -------------------------------------------------------------------------
    // getArtist
    // -------------------------------------------------------------------------

    @Test
    fun `getArtist returns artist record with correct fields`() = runTest {
        server.enqueue(mockOk(GET_ARTIST_FIXTURE))

        val (artist, _) = api.getArtist("ar1")

        assertEquals("ar1", artist.id)
        assertEquals("Radiohead", artist.name)
        assertEquals("art-ar1", artist.coverArt)
        assertEquals(9, artist.albumCount)
    }

    @Test
    fun `getArtist returns albums with correct fields`() = runTest {
        server.enqueue(mockOk(GET_ARTIST_FIXTURE))

        val (_, albums) = api.getArtist("ar1")

        assertEquals(2, albums.size)
        val titles = albums.map { it.title }
        assertTrue(titles.contains("OK Computer"))
        assertTrue(titles.contains("Kid A"))
    }

    @Test
    fun `getArtist album coverArt is mapped`() = runTest {
        server.enqueue(mockOk(GET_ARTIST_FIXTURE))

        val (_, albums) = api.getArtist("ar1")

        val okComputer = albums.first { it.title == "OK Computer" }
        assertEquals("art-al1", okComputer.coverArt)
        assertEquals(1997, okComputer.year)
    }

    @Test
    fun `getArtist request includes id query param`() = runTest {
        server.enqueue(mockOk(GET_ARTIST_FIXTURE))

        api.getArtist("ar1")

        val request = server.takeRequest()
        assertEquals("/rest/getArtist.view", request.url.encodedPath)
        assertEquals("ar1", request.url.queryParameter("id"))
    }

    @Test
    fun `getArtist throws DecodingError when artist field absent`() = runTest {
        server.enqueue(mockOk("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))

        try {
            api.getArtist("ar1")
            org.junit.Assert.fail("Expected DecodingError")
        } catch (e: NavidromeApiException.DecodingError) {
            // expected
        }
    }

    // -------------------------------------------------------------------------
    // getAlbum
    // -------------------------------------------------------------------------

    @Test
    fun `getAlbum returns album record with correct fields`() = runTest {
        server.enqueue(mockOk(GET_ALBUM_FIXTURE))

        val (album, _, _) = api.getAlbum("al1")

        assertEquals("al1", album.id)
        assertEquals("ar1", album.artistId)
        assertEquals("OK Computer", album.title)
        assertEquals(1997, album.year)
        assertEquals("art-al1", album.coverArt)
    }

    @Test
    fun `getAlbum returns artist display name from artist field not artistId`() = runTest {
        server.enqueue(mockOk(GET_ALBUM_FIXTURE))

        val (_, artistName, _) = api.getAlbum("al1")

        // Must be the human name, not "ar1" (iOS bug was showing artistId here)
        assertEquals("Radiohead", artistName)
    }

    @Test
    fun `getAlbum returns tracks with correct fields`() = runTest {
        server.enqueue(mockOk(GET_ALBUM_FIXTURE))

        val (_, _, tracks) = api.getAlbum("al1")

        assertEquals(3, tracks.size)
        val titles = tracks.map { it.title }
        assertTrue(titles.contains("Airbag"))
        assertTrue(titles.contains("Paranoid Android"))
        assertTrue(titles.contains("Karma Police"))
    }

    @Test
    fun `getAlbum track fields are correctly mapped`() = runTest {
        server.enqueue(mockOk(GET_ALBUM_FIXTURE))

        val (_, _, tracks) = api.getAlbum("al1")

        val airbag = tracks.first { it.title == "Airbag" }
        assertEquals("t1", airbag.id)
        assertEquals(1, airbag.trackNumber)
        assertEquals(229, airbag.duration)
        assertEquals("art-al1", airbag.coverArt)
    }

    @Test
    fun `getAlbum request includes id query param`() = runTest {
        server.enqueue(mockOk(GET_ALBUM_FIXTURE))

        api.getAlbum("al1")

        val request = server.takeRequest()
        assertEquals("/rest/getAlbum.view", request.url.encodedPath)
        assertEquals("al1", request.url.queryParameter("id"))
    }

    @Test
    fun `getAlbum artistName is null when artist field absent`() = runTest {
        server.enqueue(
            mockOk(
                """{"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"al2","artistId":"ar1","title":"Kid A","song":[]}}}""",
            ),
        )

        val (_, artistName, _) = api.getAlbum("al2")

        assertNull("artistName must be null when server omits the 'artist' string field", artistName)
    }

    // -------------------------------------------------------------------------
    // getAlbumList2
    // -------------------------------------------------------------------------

    @Test
    fun `getAlbumList2 returns album list`() = runTest {
        server.enqueue(mockOk(GET_ALBUM_LIST2_FIXTURE))

        val albums = api.getAlbumList2()

        assertEquals(2, albums.size)
        val titles = albums.map { it.title }
        assertTrue(titles.contains("Hail to the Thief"))
        assertTrue(titles.contains("The Bends"))
    }

    @Test
    fun `getAlbumList2 request sends type size offset params`() = runTest {
        server.enqueue(mockOk(GET_ALBUM_LIST2_FIXTURE))

        api.getAlbumList2(type = AlbumListType.NEWEST, size = 25, offset = 50)

        val request = server.takeRequest()
        assertEquals("/rest/getAlbumList2.view", request.url.encodedPath)
        assertEquals("newest", request.url.queryParameter("type"))
        assertEquals("25", request.url.queryParameter("size"))
        assertEquals("50", request.url.queryParameter("offset"))
    }

    @Test
    fun `getAlbumList2 random type sends random in type param`() = runTest {
        server.enqueue(mockOk(GET_ALBUM_LIST2_FIXTURE))

        api.getAlbumList2(type = AlbumListType.RANDOM)

        val request = server.takeRequest()
        assertEquals("random", request.url.queryParameter("type"))
    }

    @Test
    fun `getAlbumList2 returns empty list when albumList2 absent`() = runTest {
        server.enqueue(mockOk("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))

        val albums = api.getAlbumList2()

        assertTrue(albums.isEmpty())
    }

    // -------------------------------------------------------------------------
    // getGenres
    // -------------------------------------------------------------------------

    @Test
    fun `getGenres returns genres list with correct fields`() = runTest {
        server.enqueue(mockOk(GET_GENRES_FIXTURE))

        val genres = api.getGenres()

        assertEquals(3, genres.size)
        val names = genres.map { it.name }
        assertTrue(names.contains("Rock"))
        assertTrue(names.contains("Jazz"))
        assertTrue(names.contains("Electronic"))
    }

    @Test
    fun `getGenres maps songCount and albumCount`() = runTest {
        server.enqueue(mockOk(GET_GENRES_FIXTURE))

        val genres = api.getGenres()

        val rock = genres.first { it.name == "Rock" }
        assertEquals(142, rock.songCount)
        assertEquals(18, rock.albumCount)
    }

    @Test
    fun `getGenres request targets getGenres dot view`() = runTest {
        server.enqueue(mockOk(GET_GENRES_FIXTURE))

        api.getGenres()

        val request = server.takeRequest()
        assertEquals("/rest/getGenres.view", request.url.encodedPath)
    }

    @Test
    fun `getGenres returns empty list when genres absent`() = runTest {
        server.enqueue(mockOk("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))

        val genres = api.getGenres()

        assertTrue(genres.isEmpty())
    }

    // -------------------------------------------------------------------------
    // getSongsByGenre
    // -------------------------------------------------------------------------

    @Test
    fun `getSongsByGenre returns tracks for genre`() = runTest {
        server.enqueue(mockOk(GET_SONGS_BY_GENRE_FIXTURE))

        val tracks = api.getSongsByGenre("Rock")

        assertEquals(2, tracks.size)
        val titles = tracks.map { it.title }
        assertTrue(titles.contains("Creep"))
        assertTrue(titles.contains("Karma Police"))
    }

    @Test
    fun `getSongsByGenre request includes genre count offset params`() = runTest {
        server.enqueue(mockOk(GET_SONGS_BY_GENRE_FIXTURE))

        api.getSongsByGenre(genre = "Rock", count = 20, offset = 10)

        val request = server.takeRequest()
        assertEquals("/rest/getSongsByGenre.view", request.url.encodedPath)
        assertEquals("Rock", request.url.queryParameter("genre"))
        assertEquals("20", request.url.queryParameter("count"))
        assertEquals("10", request.url.queryParameter("offset"))
    }

    @Test
    fun `getSongsByGenre returns empty list when songsByGenre absent`() = runTest {
        server.enqueue(mockOk("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))

        val tracks = api.getSongsByGenre("Rock")

        assertTrue(tracks.isEmpty())
    }

    // -------------------------------------------------------------------------
    // getCoverArtUrl
    // -------------------------------------------------------------------------

    @Test
    fun `getCoverArtUrl builds URL targeting getCoverArt dot view`() {
        val url = api.getCoverArtUrl("art-al1", 300)

        assertNotNull(url)
        assertEquals("/rest/getCoverArt.view", url!!.encodedPath)
        assertEquals("art-al1", url.queryParameter("id"))
        assertEquals("300", url.queryParameter("size"))
    }

    @Test
    fun `getCoverArtUrl without size omits size param`() {
        val url = api.getCoverArtUrl("art-al1", null)

        assertNotNull(url)
        assertNull(url!!.queryParameter("size"))
    }

    @Test
    fun `getCoverArtUrl includes auth params`() {
        val url = api.getCoverArtUrl("art-al1", 300)

        assertNotNull(url)
        assertNotNull(url!!.queryParameter("t")) // token
        assertNotNull(url.queryParameter("s"))   // salt
        assertEquals("alice", url.queryParameter("u"))
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    companion object {
        private const val GET_ARTISTS_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "artists": {
                  "ignoredArticles": "The El La Los Las Le",
                  "index": [
                    {
                      "name": "A",
                      "artist": [
                        {"id": "ar1", "name": "Arcade Fire", "albumCount": 6, "coverArt": "art-ar1"}
                      ]
                    },
                    {
                      "name": "B",
                      "artist": [
                        {"id": "ar2", "name": "Blur", "albumCount": 7}
                      ]
                    },
                    {
                      "name": "C",
                      "artist": [
                        {"id": "ar3", "name": "Coldplay", "albumCount": 9}
                      ]
                    }
                  ]
                }
              }
            }
        """

        private const val GET_ARTIST_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "artist": {
                  "id": "ar1",
                  "name": "Radiohead",
                  "albumCount": 9,
                  "coverArt": "art-ar1",
                  "album": [
                    {"id": "al1", "artistId": "ar1", "name": "OK Computer", "songCount": 12, "year": 1997, "coverArt": "art-al1"},
                    {"id": "al2", "artistId": "ar1", "name": "Kid A", "songCount": 10, "year": 2000}
                  ]
                }
              }
            }
        """

        private const val GET_ALBUM_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "album": {
                  "id": "al1",
                  "artistId": "ar1",
                  "artist": "Radiohead",
                  "title": "OK Computer",
                  "year": 1997,
                  "coverArt": "art-al1",
                  "songCount": 3,
                  "song": [
                    {"id": "t1", "albumId": "al1", "artistId": "ar1", "title": "Airbag",          "track": 1, "duration": 229, "coverArt": "art-al1"},
                    {"id": "t2", "albumId": "al1", "artistId": "ar1", "title": "Paranoid Android", "track": 2, "duration": 387},
                    {"id": "t3", "albumId": "al1", "artistId": "ar1", "title": "Karma Police",     "track": 3, "duration": 261}
                  ]
                }
              }
            }
        """

        private const val GET_ALBUM_LIST2_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "albumList2": {
                  "album": [
                    {"id": "al3", "artistId": "ar1", "name": "Hail to the Thief", "songCount": 14, "year": 2003},
                    {"id": "al4", "artistId": "ar1", "name": "The Bends",          "songCount": 12, "year": 1995}
                  ]
                }
              }
            }
        """

        private const val GET_GENRES_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "genres": {
                  "genre": [
                    {"value": "Rock",       "songCount": 142, "albumCount": 18},
                    {"value": "Jazz",       "songCount": 34,  "albumCount": 6},
                    {"value": "Electronic", "songCount": 88,  "albumCount": 11}
                  ]
                }
              }
            }
        """

        private const val GET_SONGS_BY_GENRE_FIXTURE = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "songsByGenre": {
                  "song": [
                    {"id": "t10", "albumId": "al10", "artistId": "ar10", "title": "Creep",        "track": 1, "duration": 238},
                    {"id": "t11", "albumId": "al11", "artistId": "ar11", "title": "Karma Police", "track": 3, "duration": 261}
                  ]
                }
              }
            }
        """
    }
}
