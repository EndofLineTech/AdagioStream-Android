package com.adagiostream.android.service.metadata

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Covers beads_adagio-59p.3.1: the StellarTunerLog decode/display contract —
 * lenient per-entry decoding (a malformed entry drops, the response survives),
 * cut-type filtering (blocklist, "link" stays visible), and first-observation
 * startedAt synthesis (the API has no per-track timestamps).
 */
class StellarTunerLogApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StellarTunerLogApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        api = StellarTunerLogApi(client, baseUrl = server.url("/v1").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
    }

    private fun stationJson(
        id: String = "8206",
        artist: String = "Ace of Base",
        title: String = "The Sign",
        cutType: String = "Song",
    ) = """{"id":"$id","name":"90s on 9","channel_number":9,"artist":"$artist","title":"$title","album":"Happy Nation","cut_type":"$cutType","artwork_url":"https://example.com/art.jpg"}"""

    // MARK: - Lenient decode

    @Test
    fun `malformed channel entry drops without failing the catalog`() = runTest {
        enqueue(
            """{"updated_utc":"2026-07-19T00:00:00Z","channel_count":3,"channels":{
                "8206":{"id":"8206","name":"90s on 9","channel_number":9},
                "8210":{"channel_number":10},
                "8300":{"id":"8300","name":"Octane"}
            }}""",
        )
        val stations = api.fetchStations()

        assertEquals(listOf("8206", "8300"), stations?.map { it.identifier })
        assertEquals(listOf("90s on 9", "Octane"), stations?.map { it.name })
    }

    @Test
    fun `malformed nowplaying entry drops without failing the feed`() = runTest {
        enqueue(
            """{"updated_utc":"2026-07-19T00:00:00Z","station_count":3,"stations":{
                "8206":${stationJson()},
                "9999":"not even an object",
                "8300":${stationJson(id = "8300", artist = "Disturbed", title = "Down with the Sickness")}
            }}""",
        )
        val feed = api.getFeed()

        assertEquals(setOf("8206", "8300"), feed.keys)
        assertEquals("Ace of Base", feed["8206"]?.artist)
        assertEquals("Happy Nation", feed["8206"]?.album)
    }

    @Test
    fun `explicit null artist coerces to empty instead of failing the decode`() = runTest {
        enqueue(
            """{"station":{"id":"8206","name":"90s on 9","artist":null,"title":"The Sign","cut_type":"Song"}}""",
        )
        val result = api.getRecentTrack("8206", nowMillis = 1_000L)

        assertTrue(result.isSuccess)
        assertEquals("", result.getOrNull()?.artist)
        assertEquals("The Sign", result.getOrNull()?.title)
    }

    // MARK: - Cut-type filtering

    @Test
    fun `hidden cut types are filtered case-insensitively`() {
        for (cut in listOf("spot", "Spot", "PROMO", "fill", "Perm")) {
            val station = STLStation(id = "1", name = "Ch", artist = "A", title = "T", cutType = cut)
            assertNull("cut '$cut' must hide", station.toTrackMetadataOrNull(0L))
        }
    }

    @Test
    fun `link and live program cuts stay visible`() {
        for (cut in listOf("link", "Link", "Song", "Music", "talk", "PGM_Segement")) {
            val station = STLStation(id = "1", name = "Ch", artist = "A", title = "T", cutType = cut)
            assertEquals("cut '$cut' must show", "T", station.toTrackMetadataOrNull(0L)?.title)
        }
    }

    @Test
    fun `blank cut type hides, and displayable cut with no artist or title hides`() {
        assertNull(STLStation(id = "1", name = "Ch", artist = "A", title = "T", cutType = " ").toTrackMetadataOrNull(0L))
        assertNull(STLStation(id = "1", name = "Ch", artist = "", title = " ", cutType = "Song").toTrackMetadataOrNull(0L))
        // Artist OR title alone is enough
        assertEquals("A", STLStation(id = "1", name = "Ch", artist = "A", title = "", cutType = "Song").toTrackMetadataOrNull(0L)?.artist)
    }

    @Test
    fun `non-displayable nowplaying answer is success-null so the display clears`() = runTest {
        enqueue("""{"station":${stationJson(cutType = "Spot")}}""")
        val result = api.getRecentTrack("8206", nowMillis = 1_000L)

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `fetch failure is a failure result so the caller keeps the last track`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        assertTrue(api.getRecentTrack("8206").isFailure)
    }

    // MARK: - startedAt synthesis

    @Test
    fun `re-observing the same track reuses the original startedAt`() = runTest {
        val t1 = System.currentTimeMillis() - 60_000L
        val t2 = t1 + 30_000L
        enqueue("""{"station":${stationJson()}}""")
        enqueue("""{"station":${stationJson()}}""")

        val first = api.getRecentTrack("8206", nowMillis = t1).getOrNull()
        val second = api.getRecentTrack("8206", nowMillis = t2).getOrNull()

        assertEquals(t1, first?.timestamp)
        assertEquals("re-observation keeps the first-observation time", t1, second?.timestamp)
    }

    @Test
    fun `a new track gets a fresh startedAt`() = runTest {
        val t1 = System.currentTimeMillis() - 60_000L
        val t3 = t1 + 45_000L
        enqueue("""{"station":${stationJson()}}""")
        enqueue("""{"station":${stationJson(artist = "Blur", title = "Song 2")}}""")

        api.getRecentTrack("8206", nowMillis = t1)
        val next = api.getRecentTrack("8206", nowMillis = t3).getOrNull()

        assertEquals("Song 2", next?.title)
        assertEquals(t3, next?.timestamp)
    }

    @Test
    fun `history answers trackAt for time-shift catch-up`() = runTest {
        val t1 = System.currentTimeMillis() - 120_000L
        val t2 = t1 + 90_000L
        enqueue("""{"station":${stationJson()}}""")
        enqueue("""{"station":${stationJson(artist = "Blur", title = "Song 2")}}""")

        api.getRecentTrack("8206", nowMillis = t1)
        api.getRecentTrack("8206", nowMillis = t2)

        // A catch-up lookup between the two observations resolves to the first.
        assertEquals("The Sign", api.trackAt("8206", t1 + 30_000L)?.title)
        assertEquals("Song 2", api.trackAt("8206", t2 + 1_000L)?.title)
        // clearHistory (source switch) drops everything
        api.clearHistory()
        assertNull(api.trackAt("8206", t2 + 1_000L))
    }
}
