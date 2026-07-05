package com.adagiostream.android.service.metadata

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Covers beads_adagio-q5a: time-shift catch-up must show the track that was
 * actually playing at the buffered position, not the live now-playing track.
 * [XMPlaylistApi.trackAt] is the lookup that answers that — sourced from a
 * 10-minute track history built from [XMPlaylistApi.getRecentTrack] responses,
 * mirroring iOS SXMMetadataService.showTrack(at:)/trackHistory.
 */
class XMPlaylistApiHistoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: XMPlaylistApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        api = XMPlaylistApi(client, baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun stationResponse(vararg tracks: Pair<Instant, String>): String {
        val results = tracks.joinToString(",") { (startedAt, title) ->
            """{"timestamp":"$startedAt","track":{"id":"$title","title":"$title","artists":["Artist"]}}"""
        }
        return """{"results":[$results]}"""
    }

    @Test
    fun `empty history returns null`() = runTest {
        // No getRecentTrack call has ever populated history for this deeplink.
        assertNull(api.trackAt("never-polled", System.currentTimeMillis()))
    }

    @Test
    fun `delay older than history returns null`() = runTest {
        val now = Instant.now()
        server.enqueue(MockResponse.Builder().code(200).body(
            stationResponse(now.minusSeconds(60) to "Recent Song")
        ).build())
        api.getRecentTrack("station-a")

        // Asking for a point 20 minutes back — well before the only known track.
        val tooFarBack = now.minusSeconds(20 * 60).toEpochMilli()
        assertNull(api.trackAt("station-a", tooFarBack))
    }

    @Test
    fun `exact boundary — timestamp equal to query time matches`() = runTest {
        val startedAt = Instant.now().minusSeconds(30)
        server.enqueue(MockResponse.Builder().code(200).body(
            stationResponse(startedAt to "Boundary Song")
        ).build())
        api.getRecentTrack("station-b")

        val track = api.trackAt("station-b", startedAt.toEpochMilli())
        assertEquals("Boundary Song", track?.title)
    }

    @Test
    fun `picks the most recent track at or before the delay, not the live track`() = runTest {
        val now = Instant.now()
        val older = now.minusSeconds(500) // comfortably inside the 10-minute history window
        val newer = now.minusSeconds(60)
        server.enqueue(MockResponse.Builder().code(200).body(
            stationResponse(newer to "Now Playing", older to "Played Earlier")
        ).build())
        api.getRecentTrack("station-c")

        // Live/most-recent track is "Now Playing" (used by getRecentTrack's return value),
        // but a catch-up lookup ~5 minutes behind live should resolve to the earlier track.
        val delayedAt = now.minusSeconds(300).toEpochMilli()
        val track = api.trackAt("station-c", delayedAt)
        assertEquals("Played Earlier", track?.title)
    }

    /**
     * baw.13 MINOR-4: [XMPlaylistApi.trackAt] must reject entries older than
     * [historyWindowMs][XMPlaylistApi] *relative to the query time*, not rely
     * solely on [XMPlaylistApi.mergeIntoHistory]'s poll-time pruning. Simulates
     * polling having stopped: the entry is fresh enough to survive the
     * wall-clock cutoff at merge time, but the query point is 20 minutes after
     * the entry's own timestamp — well outside the 10-minute window relative to
     * the query. The old bound (`timestamp in 1..atEpochMillis`) would return
     * this as a stale hit.
     */
    @Test
    fun `entry stale relative to query time is rejected even though poll-time pruning kept it`() = runTest {
        val trackTime = Instant.now().minusSeconds(30)
        server.enqueue(MockResponse.Builder().code(200).body(
            stationResponse(trackTime to "Stale Relative To Query")
        ).build())
        api.getRecentTrack("station-d")

        val queryTime = trackTime.plusSeconds(20 * 60).toEpochMilli() // 20 min after the track
        assertNull(api.trackAt("station-d", queryTime))
    }
}
