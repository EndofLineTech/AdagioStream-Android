package com.adagiostream.android.service.metadata

import com.adagiostream.android.model.SXMMetadataSource
import com.adagiostream.android.testutil.TestFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the SXM station-list retry loop in
 * [SXMMetadataService.matchChannels] (beads_adagio-59p.3.4, iOS parity:
 * SXMMetadataRetryTests). Uses the stationListFetcher and retrySleep seams —
 * no real network, no wall-clock sleeps. The service scope is the test scope,
 * so `matchJob?.join()` runs loops deterministically.
 */
class SXMMetadataRetryTest {

    private val stations = listOf(MatchableStation("90s on 9", "8206"))

    private fun channels() = listOf(
        TestFixtures.makeChannel(id = "c1", name = "90s on 9", group = "SiriusXM"),
    )

    // The APIs are never reached — every test injects stationListFetcher.
    private fun makeService(scope: kotlinx.coroutines.CoroutineScope): SXMMetadataService {
        val client = OkHttpClient()
        return SXMMetadataService(
            xmPlaylistApi = XMPlaylistApi(client),
            stellarTunerLogApi = StellarTunerLogApi(client),
            initialSource = SXMMetadataSource.STELLARTUNERLOG,
            scope = scope,
        )
    }

    // MARK: - Retry until success

    @Test
    fun `retries until success with capped backoff then builds the table`() = runTest {
        val service = makeService(this)
        var attempts = 0
        val delays = mutableListOf<Long>()
        service.stationListFetcher = {
            attempts++
            if (attempts <= 6) null else stations
        }
        service.retrySleep = { delays.add(it); false }

        service.matchChannels(channels(), emptyList())
        service.matchJob?.join()

        assertEquals(7, attempts)
        assertEquals("5s doubling, capped at 60s", listOf(5L, 10L, 20L, 40L, 60L, 60L), delays)
        assertEquals("matching table built after retries", "8206", service.stationIdForChannel("c1"))
    }

    // MARK: - Generation supersession

    @Test
    fun `newer matchChannels supersedes older in-flight loop`() = runTest {
        val service = makeService(this)
        service.retrySleep = { false }
        var fetchCount = 0
        val firstParked = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        service.stationListFetcher = {
            fetchCount++
            if (fetchCount == 1) {
                // Park the first loop's fetch until the test releases it,
                // then hand back a stale result that must NOT land.
                firstParked.complete(Unit)
                releaseFirst.await()
                listOf(MatchableStation("90s on 9", "STALE"))
            } else {
                stations
            }
        }

        service.matchChannels(channels(), emptyList())
        val firstJob = service.matchJob
        // Prove the first loop is parked before superseding it.
        firstParked.await()

        service.matchChannels(channels(), emptyList())
        service.matchJob?.join()
        assertEquals("newest attempt lands", "8206", service.stationIdForChannel("c1"))

        releaseFirst.complete(Unit)
        firstJob?.join() // superseded loop exits via the generation guard
        assertEquals(2, fetchCount)
        assertEquals("stale loop must not overwrite the table", "8206", service.stationIdForChannel("c1"))
    }

    @Test
    fun `supersession arriving during the sleep exits at the post-sleep checkpoint`() = runTest {
        val service = makeService(this)
        var fetchCount = 0
        service.stationListFetcher = {
            fetchCount++
            // First loop's fetch fails so it reaches the sleep; every later
            // fetch (the superseding loop's) succeeds immediately.
            if (fetchCount == 1) null else stations
        }
        var superseded = false
        service.retrySleep = {
            if (!superseded) {
                superseded = true
                // A newer matchChannels lands mid-sleep — the sleeping loop
                // must notice at the post-sleep generation check and exit
                // without fetching again.
                service.matchChannels(channels(), emptyList())
            }
            false
        }

        service.matchChannels(channels(), emptyList())
        val firstJob = service.matchJob
        firstJob?.join() // fails once, sleeps, gets superseded mid-sleep, exits
        service.matchJob?.join() // the superseding loop fetches and builds

        assertEquals("stale loop must not fetch again after its sleep", 2, fetchCount)
        assertEquals("8206", service.stationIdForChannel("c1"))
    }

    // MARK: - Late success re-attach

    @Test
    fun `late station-list success re-attaches metadata to the already-playing channel`() = runTest {
        val service = makeService(this)
        service.retrySleep = { false }
        service.stationListFetcher = { stations }
        // AccountManager wires onMappingBuilt to restart now-playing polling
        // for the channel that was already playing while the map was empty —
        // capture what that restart would resolve to.
        var reattachedStationId: String? = null
        service.onMappingBuilt = {
            reattachedStationId = service.stationIdForChannel("c1")
        }

        service.matchChannels(channels(), emptyList())
        // The loop task has not run yet: the map is still empty at the moment
        // the channel starts playing, so the poll entry point finds no mapping.
        assertNull("no mapping while the map is empty", service.stationIdForChannel("c1"))

        service.matchJob?.join() // fetch succeeds, table builds, late attach fires
        assertEquals("onMappingBuilt re-attach sees the mapping", "8206", reattachedStationId)
    }

    // MARK: - Cancellation exit

    @Test
    fun `cancelled sleep exits the loop immediately`() = runTest {
        val service = makeService(this)
        var attempts = 0
        var sleeps = 0
        service.stationListFetcher = { attempts++; null }
        service.retrySleep = { sleeps++; true } // report cancellation

        service.matchChannels(channels(), emptyList())
        service.matchJob?.join()

        assertEquals("no further fetch attempts after cancellation", 1, attempts)
        assertEquals(1, sleeps)
        assertFalse(service.hasMappedChannels())
    }

    // MARK: - No SXM channels

    @Test
    fun `no SXM channels means no retry loop at all`() = runTest {
        val service = makeService(this)
        var attempts = 0
        service.stationListFetcher = { attempts++; stations }

        service.matchChannels(
            listOf(TestFixtures.makeChannel(id = "c9", name = "News", group = "General")),
            emptyList(),
        )
        service.matchJob?.join()

        assertEquals(0, attempts)
        assertFalse(service.hasMappedChannels())
        assertTrue(service.matchJob == null)
    }
}
