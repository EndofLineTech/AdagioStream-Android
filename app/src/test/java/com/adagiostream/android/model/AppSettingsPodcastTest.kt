package com.adagiostream.android.model

import com.adagiostream.android.service.audiobookshelf.PodcastEpisodeEndBehavior
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers beads_adagio-59p.2.2: the podcast episode end behavior must default
 * to Next Unplayed (the iOS default) and fall back there on garbage without
 * nuking the rest of the settings decode — same contract as the SXM source.
 */
class AppSettingsPodcastTest {

    // Same leniency as the app's DI-provided Json
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `end behavior defaults to Next Unplayed when unset`() {
        assertEquals(
            PodcastEpisodeEndBehavior.NEXT_UNPLAYED,
            json.decodeFromString<AppSettings>("{}").podcastEpisodeEndBehavior,
        )
    }

    @Test
    fun `persisted end behavior choices decode via their iOS raw values`() {
        assertEquals(
            PodcastEpisodeEndBehavior.STOP,
            json.decodeFromString<AppSettings>("""{"podcastEpisodeEndBehavior":"stop"}""").podcastEpisodeEndBehavior,
        )
        assertEquals(
            PodcastEpisodeEndBehavior.NEXT_UNPLAYED,
            json.decodeFromString<AppSettings>("""{"podcastEpisodeEndBehavior":"nextUnplayed"}""").podcastEpisodeEndBehavior,
        )
        assertEquals(
            PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER,
            json.decodeFromString<AppSettings>("""{"podcastEpisodeEndBehavior":"continueInSortOrder"}""").podcastEpisodeEndBehavior,
        )
    }

    @Test
    fun `garbage end behavior falls back to Next Unplayed without failing the whole settings decode`() {
        val settings = json.decodeFromString<AppSettings>(
            """{"podcastEpisodeEndBehavior":"playEverythingTwice","debugLoggingEnabled":true}""",
        )
        assertEquals(PodcastEpisodeEndBehavior.NEXT_UNPLAYED, settings.podcastEpisodeEndBehavior)
        // The rest of the settings survive — a bad value must not reset everything
        assertEquals(true, settings.debugLoggingEnabled)
    }

    @Test
    fun `end behavior round-trips through its serial name`() {
        val encoded = json.encodeToString(
            AppSettings.serializer(),
            AppSettings(podcastEpisodeEndBehavior = PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER),
        )
        assertEquals(
            PodcastEpisodeEndBehavior.CONTINUE_IN_SORT_ORDER,
            json.decodeFromString<AppSettings>(encoded).podcastEpisodeEndBehavior,
        )
    }
}
