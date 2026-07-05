package com.adagiostream.android.service.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.adagiostream.android.service.navidrome.Album
import com.adagiostream.android.service.navidrome.Artist
import com.adagiostream.android.service.navidrome.NavidromePlaylist
import com.adagiostream.android.service.navidrome.Track

/**
 * Pure helper that maps Navidrome domain objects to namespaced Media3 [MediaItem]s
 * for the Android Auto music browse tree (baw.7.1).
 *
 * All music media IDs use a `music_*` prefix so they never collide with the
 * existing IPTV channel/group IDs (ROOT_ID = "root", FAVORITES_ID = "favorites",
 * group names, "custom_playlist_*", "custom_group_*"). The routing in
 * [com.adagiostream.android.service.player.AudioPlaybackService.onGetChildren]
 * delegates here after recognising the prefix.
 *
 * This object is stateless and framework-free so it can be unit-tested on the
 * JVM with Robolectric (for Media3 stubs) without Hilt or the full service stack.
 */
object MusicAutoMediaMapper {

    // -------------------------------------------------------------------------
    // Media ID constants — all music-tree IDs start with "music"
    // -------------------------------------------------------------------------

    const val MUSIC_ROOT_ID = "music"
    const val MUSIC_ARTISTS_ID = "music_artists"
    const val MUSIC_ALBUMS_ID = "music_albums"
    const val MUSIC_SONGS_ID = "music_songs"
    const val MUSIC_PLAYLISTS_ID = "music_playlists"
    const val MUSIC_ARTIST_PREFIX = "music_artist_"
    const val MUSIC_ALBUM_PREFIX = "music_album_"
    const val MUSIC_TRACK_PREFIX = "music_track_"
    const val MUSIC_PLAYLIST_PREFIX = "music_playlist_"

    // -------------------------------------------------------------------------
    // ID routing helpers
    // -------------------------------------------------------------------------

    /** Returns true when [id] belongs to the music browse sub-tree. */
    fun isMusicId(id: String): Boolean =
        id == MUSIC_ROOT_ID ||
        id == MUSIC_ARTISTS_ID ||
        id == MUSIC_ALBUMS_ID ||
        id == MUSIC_SONGS_ID ||
        id == MUSIC_PLAYLISTS_ID ||
        id.startsWith(MUSIC_ARTIST_PREFIX) ||
        id.startsWith(MUSIC_ALBUM_PREFIX) ||
        id.startsWith(MUSIC_TRACK_PREFIX) ||
        id.startsWith(MUSIC_PLAYLIST_PREFIX)

    /** Extracts the raw track ID from a `music_track_<trackId>` media ID. */
    fun trackIdFrom(mediaId: String): String = mediaId.removePrefix(MUSIC_TRACK_PREFIX)

    /** Extracts the raw artist ID from a `music_artist_<artistId>` media ID. */
    fun artistIdFrom(mediaId: String): String = mediaId.removePrefix(MUSIC_ARTIST_PREFIX)

    /** Extracts the raw album ID from a `music_album_<albumId>` media ID. */
    fun albumIdFrom(mediaId: String): String = mediaId.removePrefix(MUSIC_ALBUM_PREFIX)

    /** Extracts the raw playlist ID from a `music_playlist_<playlistId>` media ID. */
    fun playlistIdFrom(mediaId: String): String = mediaId.removePrefix(MUSIC_PLAYLIST_PREFIX)

    /**
     * Index of [trackId] within [contextTracks], or null when it isn't a member
     * (baw.13 MAJOR-1). `null` signals that [contextTracks] must NOT be trusted:
     * Android Auto pre-fetches sibling browse nodes in parallel, so the browse
     * context remembered between "user opened this list" and "user taps a track"
     * can be overwritten by an unrelated node before the tap resolves. Blindly
     * defaulting to index 0 in that case would silently start playback from the
     * wrong album/playlist; the caller must fall through to a single-track queue
     * instead (mirrors the membership guard in
     * [com.adagiostream.android.service.player.AudioPlaybackService.channelListForContext]
     * for the IPTV path).
     */
    fun trackIndexInContext(contextTracks: List<Track>, trackId: String): Int? =
        contextTracks.indexOfFirst { it.id == trackId }.takeIf { it >= 0 }

    // -------------------------------------------------------------------------
    // Music root child node — the "Music" entry in the Root browse list
    // -------------------------------------------------------------------------

    /**
     * Returns the "Music" entry as a browsable MediaItem for the Root list.
     * Only shown when a Subsonic account is configured.
     */
    fun musicRootItem(): MediaItem = buildBrowsable(MUSIC_ROOT_ID, "Music", null)

    // -------------------------------------------------------------------------
    // Music sub-nodes (Artists / Albums / Playlists) — children of MUSIC_ROOT_ID
    // -------------------------------------------------------------------------

    /**
     * Returns the four top-level music browse nodes for the [MUSIC_ROOT_ID] parent.
     *
     * All four are browsable and not playable:
     *  - [MUSIC_ARTISTS_ID] → artist list
     *  - [MUSIC_ALBUMS_ID]  → newest 50 albums
     *  - [MUSIC_SONGS_ID]   → random 100 songs (iOS parity)
     *  - [MUSIC_PLAYLISTS_ID] → Navidrome server-side playlists
     */
    fun musicRootChildren(): List<MediaItem> = listOf(
        buildBrowsable(MUSIC_ARTISTS_ID, "Artists", null),
        buildBrowsable(MUSIC_ALBUMS_ID, "Albums", null),
        buildBrowsable(MUSIC_SONGS_ID, "Songs", null),
        buildBrowsable(MUSIC_PLAYLISTS_ID, "Playlists", null),
    )

    // -------------------------------------------------------------------------
    // Browse level builders
    // -------------------------------------------------------------------------

    /**
     * Maps a list of [Artist]s to browsable items with correctly-namespaced IDs.
     *
     * @param coverArtUrl Resolves a Subsonic cover-art ID to an authenticated
     *   artwork [Uri] (account-derived, so it can't live in this stateless
     *   object) — defaults to no artwork.
     */
    fun artistsItems(artists: List<Artist>, coverArtUrl: (String) -> Uri? = { null }): List<MediaItem> =
        artists.map { artist ->
            buildBrowsable(
                id = "$MUSIC_ARTIST_PREFIX${artist.id}",
                title = artist.name,
                subtitle = if (artist.albumCount > 0) "${artist.albumCount} albums" else null,
                artworkUri = artist.coverArt?.let(coverArtUrl),
            )
        }

    /** Maps a list of [Album]s to browsable items with correctly-namespaced IDs. */
    fun albumsItems(albums: List<Album>, coverArtUrl: (String) -> Uri? = { null }): List<MediaItem> =
        albums.map { album ->
            buildBrowsable(
                id = "$MUSIC_ALBUM_PREFIX${album.id}",
                title = album.title,
                subtitle = album.year?.toString(),
                artworkUri = album.coverArt?.let(coverArtUrl),
            )
        }

    /**
     * Maps [Track]s to playable (never browsable) items with correctly-namespaced IDs.
     *
     * @param coverArtUrl Resolves a Subsonic cover-art ID to an authenticated
     *   artwork [Uri] (account-derived) — defaults to no artwork.
     */
    fun tracksItems(tracks: List<Track>, coverArtUrl: (String) -> Uri? = { null }): List<MediaItem> =
        tracks.map { track ->
            buildPlayable(
                id = "$MUSIC_TRACK_PREFIX${track.id}",
                title = track.title,
                artist = track.artist,
                artworkUri = track.coverArt?.let(coverArtUrl),
            )
        }

    /** Maps [NavidromePlaylist]s to browsable items with correctly-namespaced IDs. */
    fun playlistsItems(playlists: List<NavidromePlaylist>, coverArtUrl: (String) -> Uri? = { null }): List<MediaItem> =
        playlists.map { pl ->
            buildBrowsable(
                id = "$MUSIC_PLAYLIST_PREFIX${pl.id}",
                title = pl.name,
                subtitle = if (pl.songCount > 0) "${pl.songCount} tracks" else null,
                artworkUri = pl.coverArt?.let(coverArtUrl),
            )
        }

    // -------------------------------------------------------------------------
    // Private builders
    // -------------------------------------------------------------------------

    private fun buildBrowsable(id: String, title: String, subtitle: String?, artworkUri: Uri? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { if (subtitle != null) setSubtitle(subtitle) }
                    .apply { if (artworkUri != null) setArtworkUri(artworkUri) }
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun buildPlayable(id: String, title: String, artist: String?, artworkUri: Uri? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .apply { if (artworkUri != null) setArtworkUri(artworkUri) }
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
}
