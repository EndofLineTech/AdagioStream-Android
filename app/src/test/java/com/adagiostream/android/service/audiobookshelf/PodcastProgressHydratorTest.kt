package com.adagiostream.android.service.audiobookshelf

import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [PodcastProgressHydrator] tests (beads_adagio-59p.2.1): per-key fetch-once
 * caching, hydrated-404 vs not-hydrated distinction, failed-fetch retry, and
 * the bounded-concurrency ceiling.
 */
class PodcastProgressHydratorTest {

    @Test
    fun `repeat hydrate calls do not refetch cached keys`() = runTest {
        val fetches = AtomicInteger(0)
        val hydrator = PodcastProgressHydrator(fetch = { _, _ ->
            fetches.incrementAndGet()
            AbsMediaProgress(progress = 0.5)
        })

        hydrator.hydrate(listOf("show" to "ep1", "show" to "ep2"))
        hydrator.hydrate(listOf("show" to "ep1", "show" to "ep2"))
        hydrator.hydrate(listOf("show" to "ep2", "show" to "ep3"))

        assertEquals(3, fetches.get()) // ep1, ep2, ep3 — once each
        assertEquals(0.5, hydrator.progress.value["show/ep1"]?.progress)
    }

    @Test
    fun `a 404-null result is recorded as hydrated-unplayed, not refetched`() = runTest {
        val fetches = AtomicInteger(0)
        val hydrator = PodcastProgressHydrator(fetch = { _, _ ->
            fetches.incrementAndGet()
            null // the API's 404 → never started
        })

        hydrator.hydrate(listOf("show" to "ep1"))
        hydrator.hydrate(listOf("show" to "ep1"))

        assertEquals(1, fetches.get())
        // Hydrated with a null record — key present, value null.
        assertTrue("show/ep1" in hydrator.progress.value)
        assertNull(hydrator.progress.value["show/ep1"])
        assertEquals(EpisodeProgressState.Unplayed, episodeProgressState(hydrator.progress.value["show/ep1"]))
    }

    @Test
    fun `a failed fetch is retried on the next hydrate pass`() = runTest {
        val fetches = AtomicInteger(0)
        val hydrator = PodcastProgressHydrator(fetch = { _, _ ->
            if (fetches.incrementAndGet() == 1) throw RuntimeException("boom")
            AbsMediaProgress(progress = 0.3)
        })

        hydrator.hydrate(listOf("show" to "ep1"))
        assertFalse("show/ep1" in hydrator.progress.value) // failure leaves it unhydrated

        hydrator.hydrate(listOf("show" to "ep1"))
        assertEquals(2, fetches.get())
        assertEquals(0.3, hydrator.progress.value["show/ep1"]?.progress)
    }

    @Test
    fun `concurrent fetches never exceed the concurrency cap`() = runTest {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        // Gate each fetch so all coroutines pile up against the semaphore.
        val gate = Semaphore(permits = 20, acquiredPermits = 20)
        val hydrator = PodcastProgressHydrator(
            fetch = { _, _ ->
                val now = inFlight.incrementAndGet()
                maxInFlight.updateAndGet { maxOf(it, now) }
                gate.withPermit { } // suspends until released below
                inFlight.decrementAndGet()
                null
            },
            concurrency = 4,
        )

        val job = async { hydrator.hydrate((1..20).map { "show" to "ep$it" }) }
        // Let the first wave saturate the semaphore.
        testScheduler.advanceUntilIdle()
        repeat(20) { gate.release() }
        job.await()

        assertTrue("max in flight was ${maxInFlight.get()}", maxInFlight.get() <= 4)
        assertEquals(20, hydrator.progress.value.size)
    }
}
