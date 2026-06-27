package com.adagiostream.android.testutil

import com.adagiostream.android.model.Account
import com.adagiostream.android.model.AccountType
import com.adagiostream.android.model.Channel
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.service.navidrome.Track

object TestFixtures {

    // --- M3U Samples ---

    val MINIMAL_M3U = """
        #EXTM3U
        #EXTINF:-1,Test Channel
        http://example.com/stream1
    """.trimIndent()

    val FULL_ATTRIBUTES_M3U = """
        #EXTM3U
        #EXTINF:-1 tvg-id="ch1" tvg-name="Channel One" tvg-logo="http://logo.com/1.png" group-title="News",Channel 1
        http://example.com/stream1
        #EXTINF:-1 tvg-id="ch2" tvg-name="Channel Two" tvg-logo="http://logo.com/2.png" group-title="Sports",Channel 2
        http://example.com/stream2
    """.trimIndent()

    val EMPTY_M3U = "#EXTM3U"

    val NO_HEADER_M3U = """
        #EXTINF:-1,No Header Channel
        http://example.com/stream1
    """.trimIndent()

    val MISSING_URL_M3U = """
        #EXTM3U
        #EXTINF:-1,Orphan Channel
    """.trimIndent()

    val BLANK_ATTRIBUTES_M3U = """
        #EXTM3U
        #EXTINF:-1 tvg-id="" tvg-name="" tvg-logo="" group-title="",Fallback Name
        http://example.com/stream1
    """.trimIndent()

    // --- EPG XML Samples ---

    val MINIMAL_EPG_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <programme channel="ch1" start="20250101120000 +0000" stop="20250101130000 +0000">
            <title>Test Show</title>
          </programme>
        </tv>
    """.trimIndent()

    val MULTI_CHANNEL_EPG_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <programme channel="ch1" start="20250101120000 +0000" stop="20250101130000 +0000">
            <title>Show A</title>
            <desc>Description A</desc>
          </programme>
          <programme channel="ch1" start="20250101130000 +0000" stop="20250101140000 +0000">
            <title>Show B</title>
          </programme>
          <programme channel="ch2" start="20250101120000 +0000" stop="20250101130000 +0000">
            <title>Show C</title>
            <desc>Description C</desc>
          </programme>
        </tv>
    """.trimIndent()

    val EMPTY_EPG_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv></tv>
    """.trimIndent()

    val MISSING_TITLE_EPG_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <programme channel="ch1" start="20250101120000 +0000" stop="20250101130000 +0000">
            <desc>No title here</desc>
          </programme>
        </tv>
    """.trimIndent()

    // --- Factory Methods ---

    fun makeChannel(
        id: String = "test-id",
        name: String = "Test Channel",
        streamURL: String = "http://example.com/stream",
        logoURL: String? = null,
        group: String = "Default",
        epgChannelID: String? = null,
        isFavorite: Boolean = false,
        xtreamStreamId: Long? = null,
    ) = Channel(
        id = id,
        name = name,
        streamURL = streamURL,
        logoURL = logoURL,
        group = group,
        epgChannelID = epgChannelID,
        isFavorite = isFavorite,
        xtreamStreamId = xtreamStreamId,
    )

    fun makeTrack(
        id: String = "track-1",
        albumId: String = "album-1",
        artistId: String = "artist-1",
        title: String = "Test Track",
        artist: String? = "Test Artist",
        trackNumber: Int? = 1,
        duration: Int? = 180,
        coverArt: String? = "cover-1",
        updatedAt: Int = 0,
    ) = Track(
        id = id,
        albumId = albumId,
        artistId = artistId,
        title = title,
        artist = artist,
        trackNumber = trackNumber,
        duration = duration,
        coverArt = coverArt,
        updatedAt = updatedAt,
    )

    /** Builds [count] distinct tracks (ids/titles `track-0..track-N`) for queue tests. */
    fun makeTracks(count: Int): List<Track> =
        (0 until count).map { makeTrack(id = "track-$it", title = "Track $it", trackNumber = it + 1) }

    fun makeAccount(
        id: String = "account-1",
        name: String = "Test Account",
        type: AccountType = AccountType.M3U(url = "http://example.com/playlist.m3u"),
    ) = Account(id = id, name = name, type = type)

    fun makeEPGEntry(
        channelID: String = "ch1",
        title: String = "Test Show",
        description: String? = null,
        start: Long = System.currentTimeMillis() - 1_800_000,
        end: Long = System.currentTimeMillis() + 1_800_000,
    ) = EPGEntry(
        channelID = channelID,
        title = title,
        description = description,
        start = start,
        end = end,
    )
}
