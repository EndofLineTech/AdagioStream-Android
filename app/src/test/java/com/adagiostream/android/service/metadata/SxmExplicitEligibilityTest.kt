package com.adagiostream.android.service.metadata

import com.adagiostream.android.model.SXMMetadataSource
import com.adagiostream.android.testutil.TestFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SxmExplicitEligibilityTest {
    private fun service(scope: kotlinx.coroutines.CoroutineScope) = SXMMetadataService(
        xmPlaylistApi = XMPlaylistApi(OkHttpClient()),
        stellarTunerLogApi = StellarTunerLogApi(OkHttpClient()),
        initialSource = SXMMetadataSource.STELLARTUNERLOG,
        scope = scope,
    )

    @Test
    fun `only exact selected raw names are eligible`() = runTest {
        val service = service(this)
        var requested = 0
        service.stationListFetcher = {
            requested++
            listOf(MatchableStation("The Highway", "highway"))
        }
        val channels = listOf(
            TestFixtures.makeChannel(id = "selected", name = "The Highway", group = "Arbitrary Group"),
            TestFixtures.makeChannel(id = "legacy", name = "The Highway", group = "SiriusXM"),
            TestFixtures.makeChannel(id = "case", name = "The Highway", group = "arbitrary group"),
        )

        service.matchChannels(channels, setOf("Arbitrary Group"), emptyList())
        service.matchJob?.join()

        assertEquals(1, requested)
        assertEquals("highway", service.stationIdForChannel("selected"))
        assertNull(service.stationIdForChannel("legacy"))
        assertNull(service.stationIdForChannel("case"))
    }

    @Test
    fun `same selected name across providers makes every channel eligible`() = runTest {
        val service = service(this)
        service.stationListFetcher = { listOf(MatchableStation("The Highway", "highway")) }
        val channels = listOf(
            TestFixtures.makeChannel(id = "provider-a:1", name = "The Highway", group = "Satellite"),
            TestFixtures.makeChannel(id = "provider-b:9", name = "The Highway", group = "Satellite"),
        )

        service.matchChannels(channels, setOf("Satellite"), emptyList())
        service.matchJob?.join()

        assertEquals("highway", service.stationIdForChannel("provider-a:1"))
        assertEquals("highway", service.stationIdForChannel("provider-b:9"))
    }

    @Test
    fun `explicit empty selection performs no station request and clears mappings`() = runTest {
        val service = service(this)
        var requested = 0
        service.stationListFetcher = {
            requested++
            listOf(MatchableStation("The Highway", "highway"))
        }
        val channel = TestFixtures.makeChannel(id = "c1", name = "The Highway", group = "Satellite")
        service.matchChannels(listOf(channel), setOf("Satellite"), emptyList())
        service.matchJob?.join()
        assertEquals("highway", service.stationIdForChannel("c1"))

        service.matchChannels(listOf(channel), emptySet(), emptyList())

        assertEquals(1, requested)
        assertFalse(service.hasMappedChannels())
        assertNull(service.matchJob)
    }

    @Test
    fun `deselection invalidates an in-flight station response`() = runTest {
        val service = service(this)
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        service.stationListFetcher = {
            fetchStarted.complete(Unit)
            releaseFetch.await()
            listOf(MatchableStation("The Highway", "stale"))
        }
        val channel = TestFixtures.makeChannel(id = "c1", name = "The Highway", group = "Satellite")

        service.matchChannels(listOf(channel), setOf("Satellite"), emptyList())
        val staleJob = service.matchJob
        fetchStarted.await()
        service.matchChannels(listOf(channel), emptySet(), emptyList())
        releaseFetch.complete(Unit)
        staleJob?.join()

        assertNull(service.stationIdForChannel("c1"))
        assertFalse(service.hasMappedChannels())
    }

    @Test
    fun `metadata source change retains the explicit selection`() = runTest {
        val service = service(this)
        service.stationListFetcher = { listOf(MatchableStation("The Highway", "station")) }
        val channel = TestFixtures.makeChannel(id = "c1", name = "The Highway", group = "Satellite")
        service.matchChannels(listOf(channel), setOf("Satellite"), emptyList())
        service.matchJob?.join()

        service.sourceChanged(SXMMetadataSource.XMPLAYLIST)
        service.matchJob?.join()

        assertEquals("station", service.stationIdForChannel("c1"))
    }
}
