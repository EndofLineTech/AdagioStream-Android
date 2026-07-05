package com.adagiostream.android.service.player

import android.net.Uri
import com.adagiostream.android.service.navidrome.Album
import com.adagiostream.android.service.navidrome.Artist
import com.adagiostream.android.service.navidrome.NavidromePlaylist
import com.adagiostream.android.testutil.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [MusicAutoMediaMapper] (baw.7.1).
 *
 * Two test goals:
 *
 * 1. **Music-browse path** — every browse level produces correctly namespaced,
 *    correctly flagged (browsable/playable) [androidx.media3.common.MediaItem]s.
 *
 * 2. **IPTV non-regression guard** — the IPTV node IDs used by the existing
 *    browse tree ("root", "favorites", group names, "custom_playlist_*", …)
 *    must NOT be identified as music IDs by [MusicAutoMediaMapper.isMusicId].
 *    This test locks the routing so IPTV nodes are never accidentally swallowed
 *    by the music sub-tree.
 */
@RunWith(RobolectricTestRunner::class)
class MusicAutoMediaMapperTest {

    // =========================================================================
    // isMusicId — routing guard
    // =========================================================================

    @Test
    fun `isMusicId returns true for all music node IDs`() {
        assertTrue(MusicAutoMediaMapper.isMusicId(MusicAutoMediaMapper.MUSIC_ROOT_ID))
        assertTrue(MusicAutoMediaMapper.isMusicId(MusicAutoMediaMapper.MUSIC_ARTISTS_ID))
        assertTrue(MusicAutoMediaMapper.isMusicId(MusicAutoMediaMapper.MUSIC_ALBUMS_ID))
        assertTrue(MusicAutoMediaMapper.isMusicId(MusicAutoMediaMapper.MUSIC_SONGS_ID))
        assertTrue(MusicAutoMediaMapper.isMusicId(MusicAutoMediaMapper.MUSIC_PLAYLISTS_ID))
        assertTrue(MusicAutoMediaMapper.isMusicId("music_artist_abc123"))
        assertTrue(MusicAutoMediaMapper.isMusicId("music_album_xyz"))
        assertTrue(MusicAutoMediaMapper.isMusicId("music_track_t1"))
        assertTrue(MusicAutoMediaMapper.isMusicId("music_playlist_p1"))
    }

    /**
     * NON-REGRESSION: every well-known IPTV node ID must return false from
     * [MusicAutoMediaMapper.isMusicId] so the existing IPTV browse tree is never
     * accidentally captured by the music routing branch.
     *
     * If the IPTV tree gains a new top-level node, add its ID here.
     */
    @Test
    fun `isMusicId returns false for all existing IPTV node IDs`() {
        // Top-level well-known IPTV nodes (constants in AudioPlaybackService)
        assertFalse(MusicAutoMediaMapper.isMusicId("root"))
        assertFalse(MusicAutoMediaMapper.isMusicId("favorites"))
        assertFalse(MusicAutoMediaMapper.isMusicId("loved_songs"))
        // Custom playlist / custom group prefixes from the M3U / IPTV layer
        assertFalse(MusicAutoMediaMapper.isMusicId("custom_playlist_abc"))
        assertFalse(MusicAutoMediaMapper.isMusicId("custom_playlist_"))
        assertFalse(MusicAutoMediaMapper.isMusicId("custom_group_xyz"))
        assertFalse(MusicAutoMediaMapper.isMusicId("custom_group_"))
        // Representative group names (channel groups are bare strings in IPTV)
        assertFalse(MusicAutoMediaMapper.isMusicId("Sports"))
        assertFalse(MusicAutoMediaMapper.isMusicId("News"))
        assertFalse(MusicAutoMediaMapper.isMusicId("Movies"))
        // Loved-track synthetic IDs from the loved-songs branch
        assertFalse(MusicAutoMediaMapper.isMusicId("loved_Artist_Title"))
        assertFalse(MusicAutoMediaMapper.isMusicId("loved__"))
        // Edge: empty string
        assertFalse(MusicAutoMediaMapper.isMusicId(""))
    }

    // =========================================================================
    // musicRootItem
    // =========================================================================

    @Test
    fun `musicRootItem has correct ID, is browsable, is not playable`() {
        val item = MusicAutoMediaMapper.musicRootItem()

        assertEquals(MusicAutoMediaMapper.MUSIC_ROOT_ID, item.mediaId)
        assertTrue("musicRootItem must be browsable", item.mediaMetadata.isBrowsable ?: false)
        assertFalse("musicRootItem must not be playable", item.mediaMetadata.isPlayable ?: true)
        assertEquals("Music", item.mediaMetadata.title?.toString())
    }

    // =========================================================================
    // musicRootChildren — four sub-nodes (Artists / Albums / Songs / Playlists)
    // =========================================================================

    @Test
    fun `musicRootChildren returns exactly four items`() {
        assertEquals(4, MusicAutoMediaMapper.musicRootChildren().size)
    }

    @Test
    fun `musicRootChildren contains Artists Albums Songs and Playlists nodes`() {
        val ids = MusicAutoMediaMapper.musicRootChildren().map { it.mediaId }.toSet()
        assertTrue(ids.contains(MusicAutoMediaMapper.MUSIC_ARTISTS_ID))
        assertTrue(ids.contains(MusicAutoMediaMapper.MUSIC_ALBUMS_ID))
        assertTrue(ids.contains(MusicAutoMediaMapper.MUSIC_SONGS_ID))
        assertTrue(ids.contains(MusicAutoMediaMapper.MUSIC_PLAYLISTS_ID))
    }

    @Test
    fun `musicRootChildren all items are browsable and not playable`() {
        MusicAutoMediaMapper.musicRootChildren().forEach { item ->
            assertTrue("${item.mediaId} must be browsable", item.mediaMetadata.isBrowsable ?: false)
            assertFalse("${item.mediaId} must not be playable", item.mediaMetadata.isPlayable ?: true)
        }
    }

    // =========================================================================
    // artistsItems
    // =========================================================================

    @Test
    fun `artistsItems maps each artist to a browsable item with music_artist_ prefix`() {
        val artists = listOf(
            Artist(id = "a1", name = "Beatles", albumCount = 13, updatedAt = 0),
            Artist(id = "a2", name = "Bowie", albumCount = 27, updatedAt = 0),
        )
        val items = MusicAutoMediaMapper.artistsItems(artists)

        assertEquals(2, items.size)
        assertEquals("music_artist_a1", items[0].mediaId)
        assertEquals("music_artist_a2", items[1].mediaId)
        assertEquals("Beatles", items[0].mediaMetadata.title?.toString())
        assertEquals("Bowie", items[1].mediaMetadata.title?.toString())
    }

    @Test
    fun `artistsItems all items are browsable and not playable`() {
        val artists = listOf(Artist(id = "a1", name = "Test", albumCount = 5, updatedAt = 0))
        MusicAutoMediaMapper.artistsItems(artists).forEach { item ->
            assertTrue("${item.mediaId} must be browsable", item.mediaMetadata.isBrowsable ?: false)
            assertFalse("${item.mediaId} must not be playable", item.mediaMetadata.isPlayable ?: true)
        }
    }

    @Test
    fun `artistsItems returns empty list for empty input`() {
        assertEquals(0, MusicAutoMediaMapper.artistsItems(emptyList()).size)
    }

    @Test
    fun `artistsItems resolves artwork uri from coverArt id when resolver provided`() {
        val artists = listOf(Artist(id = "a1", name = "Beatles", albumCount = 1, coverArt = "art-a1", updatedAt = 0))
        val items = MusicAutoMediaMapper.artistsItems(artists) { id -> Uri.parse("https://example.com/art/$id") }

        assertEquals(Uri.parse("https://example.com/art/art-a1"), items[0].mediaMetadata.artworkUri)
    }

    @Test
    fun `artistsItems leaves artwork uri null when coverArt id is absent`() {
        val artists = listOf(Artist(id = "a1", name = "Beatles", albumCount = 1, updatedAt = 0))
        val items = MusicAutoMediaMapper.artistsItems(artists) { id -> Uri.parse("https://example.com/art/$id") }

        assertNull(items[0].mediaMetadata.artworkUri)
    }

    // =========================================================================
    // albumsItems
    // =========================================================================

    @Test
    fun `albumsItems maps each album to a browsable item with music_album_ prefix`() {
        val albums = listOf(
            Album(id = "al1", artistId = "a1", title = "Abbey Road", year = 1969, updatedAt = 0),
            Album(id = "al2", artistId = "a1", title = "Let It Be", year = 1970, updatedAt = 0),
        )
        val items = MusicAutoMediaMapper.albumsItems(albums)

        assertEquals(2, items.size)
        assertEquals("music_album_al1", items[0].mediaId)
        assertEquals("music_album_al2", items[1].mediaId)
        assertEquals("Abbey Road", items[0].mediaMetadata.title?.toString())
    }

    @Test
    fun `albumsItems all items are browsable and not playable`() {
        val albums = listOf(Album(id = "al1", artistId = "a1", title = "Test", updatedAt = 0))
        MusicAutoMediaMapper.albumsItems(albums).forEach { item ->
            assertTrue("${item.mediaId} must be browsable", item.mediaMetadata.isBrowsable ?: false)
            assertFalse("${item.mediaId} must not be playable", item.mediaMetadata.isPlayable ?: true)
        }
    }

    @Test
    fun `albumsItems returns empty list for empty input`() {
        assertEquals(0, MusicAutoMediaMapper.albumsItems(emptyList()).size)
    }

    // =========================================================================
    // tracksItems — MUST be playable, MUST NOT be browsable
    // =========================================================================

    @Test
    fun `tracksItems maps each track to a playable item with music_track_ prefix`() {
        val tracks = TestFixtures.makeTracks(3)
        val items = MusicAutoMediaMapper.tracksItems(tracks)

        assertEquals(3, items.size)
        items.forEachIndexed { idx, item ->
            assertEquals("music_track_${tracks[idx].id}", item.mediaId)
        }
    }

    @Test
    fun `tracksItems all items are playable and NOT browsable`() {
        val items = MusicAutoMediaMapper.tracksItems(TestFixtures.makeTracks(5))
        items.forEach { item ->
            assertTrue("${item.mediaId} must be playable", item.mediaMetadata.isPlayable ?: false)
            assertFalse("${item.mediaId} must not be browsable", item.mediaMetadata.isBrowsable ?: true)
        }
    }

    @Test
    fun `tracksItems returns empty list for empty input`() {
        assertEquals(0, MusicAutoMediaMapper.tracksItems(emptyList()).size)
    }

    @Test
    fun `tracksItems resolves artwork uri from coverArt id when resolver provided`() {
        val track = TestFixtures.makeTracks(1)[0].copy(coverArt = "art-t1")
        val items = MusicAutoMediaMapper.tracksItems(listOf(track)) { id -> Uri.parse("https://example.com/art/$id") }

        assertEquals(Uri.parse("https://example.com/art/art-t1"), items[0].mediaMetadata.artworkUri)
    }

    // =========================================================================
    // playlistsItems
    // =========================================================================

    @Test
    fun `playlistsItems maps each playlist to a browsable item with music_playlist_ prefix`() {
        val playlists = listOf(
            NavidromePlaylist(id = "p1", name = "Favourites", songCount = 10),
            NavidromePlaylist(id = "p2", name = "Workout", songCount = 20),
        )
        val items = MusicAutoMediaMapper.playlistsItems(playlists)

        assertEquals(2, items.size)
        assertEquals("music_playlist_p1", items[0].mediaId)
        assertEquals("music_playlist_p2", items[1].mediaId)
        assertEquals("Favourites", items[0].mediaMetadata.title?.toString())
    }

    @Test
    fun `playlistsItems all items are browsable and not playable`() {
        val playlists = listOf(NavidromePlaylist(id = "p1", name = "Test", songCount = 5))
        MusicAutoMediaMapper.playlistsItems(playlists).forEach { item ->
            assertTrue("${item.mediaId} must be browsable", item.mediaMetadata.isBrowsable ?: false)
            assertFalse("${item.mediaId} must not be playable", item.mediaMetadata.isPlayable ?: true)
        }
    }

    @Test
    fun `playlistsItems returns empty list for empty input`() {
        assertEquals(0, MusicAutoMediaMapper.playlistsItems(emptyList()).size)
    }

    // =========================================================================
    // ID extraction helpers (round-trip through prefix/strip)
    // =========================================================================

    @Test
    fun `trackIdFrom strips the music_track_ prefix`() {
        assertEquals("abc123", MusicAutoMediaMapper.trackIdFrom("music_track_abc123"))
        assertEquals("t-with-dashes", MusicAutoMediaMapper.trackIdFrom("music_track_t-with-dashes"))
    }

    @Test
    fun `artistIdFrom strips the music_artist_ prefix`() {
        assertEquals("ar1", MusicAutoMediaMapper.artistIdFrom("music_artist_ar1"))
    }

    @Test
    fun `albumIdFrom strips the music_album_ prefix`() {
        assertEquals("al9", MusicAutoMediaMapper.albumIdFrom("music_album_al9"))
    }

    @Test
    fun `playlistIdFrom strips the music_playlist_ prefix`() {
        assertEquals("pl5", MusicAutoMediaMapper.playlistIdFrom("music_playlist_pl5"))
    }

    // =========================================================================
    // Namespace collision guard — music IDs must not clash with IPTV IDs
    // =========================================================================

    @Test
    fun `all music ID prefixes are namespaced and distinct from IPTV prefixes`() {
        // Verify no music prefix is a prefix of the other (no accidental routing overlap)
        val musicPrefixes = listOf(
            MusicAutoMediaMapper.MUSIC_ARTIST_PREFIX,
            MusicAutoMediaMapper.MUSIC_ALBUM_PREFIX,
            MusicAutoMediaMapper.MUSIC_TRACK_PREFIX,
            MusicAutoMediaMapper.MUSIC_PLAYLIST_PREFIX,
        )
        val iptvPrefixes = listOf("custom_playlist_", "custom_group_", "loved_")

        musicPrefixes.forEach { mp ->
            iptvPrefixes.forEach { ip ->
                assertFalse(
                    "'$mp' must not start with IPTV prefix '$ip'",
                    mp.startsWith(ip),
                )
                assertFalse(
                    "IPTV prefix '$ip' must not start with music prefix '$mp'",
                    ip.startsWith(mp),
                )
            }
        }
    }
}
