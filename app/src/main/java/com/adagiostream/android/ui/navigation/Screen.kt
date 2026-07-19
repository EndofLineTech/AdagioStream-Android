package com.adagiostream.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    /**
     * IPTV channel list — labelled "Live" in the bottom nav (beads_adagio-15x.2).
     * Route stays "channels" so deep links / saved nav state don't break.
     */
    data object Channels : Screen("channels", "Live", Icons.Default.Radio)

    /**
     * Full multi-channel EPG guide (beads_adagio-7pm) — reached from a top-bar icon on
     * [Channels], not a bottom-nav slot (tab layout is owned by another workstream).
     */
    data object Guide : Screen("guide", "Guide", Icons.Default.CalendarMonth)

    /**
     * Favorites management screen (beads_adagio-15x.1) — no longer a bottom-nav
     * destination. Favorites are pinned atop [Channels] instead; this route is
     * reached via the "Manage" button on that pinned section, and hosts the
     * drag-to-reorder UX.
     */
    data object Favorites : Screen("favorites", "Favorites", Icons.Default.Star)

    /** Loved tracks — promoted to a top-level bottom-nav tab (beads_adagio-15x.2). */
    data object Loved : Screen("loved", "Loved", Icons.Default.MusicNote)
    data object Accounts : Screen("accounts", "Accounts", Icons.Default.Storage)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object Groups : Screen("groups", "Groups", Icons.Default.Folder)
    /** Custom M3U playlists. Route stays "my_m3us" (beads_adagio-15x.2 renames the label only). */
    data object MyM3Us : Screen("my_m3us", "Custom M3Us", Icons.AutoMirrored.Filled.PlaylistPlay)
    data object PlaylistDetail : Screen("playlist_detail/{playlistId}", "Playlist", Icons.AutoMirrored.Filled.PlaylistPlay) {
        fun createRoute(playlistId: String): String = "playlist_detail/$playlistId"
    }
    data object Setup : Screen("setup", "Setup", Icons.Default.Settings)
    data object Licenses : Screen("licenses", "Licenses", Icons.Default.Gavel)
    data object PrivacyPolicy : Screen("privacy_policy", "Privacy Policy", Icons.Default.Gavel)
    data object AddAccount : Screen("add_account?accountId={accountId}", "Add Account", Icons.Default.Storage) {
        fun createRoute(accountId: String? = null): String {
            return if (accountId != null) "add_account?accountId=$accountId" else "add_account"
        }
    }

    /**
     * Library tab — Navidrome library browse (baw.2.5).
     *
     * Labelled "Library" in the bottom nav and always visible (beads_adagio-15x.3
     * removed the former musicTabEnabled alpha gate). Route stays "music".
     */
    data object Music : Screen("music", "Library", Icons.Default.Headset)

    // Music sub-routes (artist, album, and genre detail; no separate bottom-nav items)
    data object ArtistDetail : Screen("music/artist/{artistId}", "Artist", Icons.Default.Headset) {
        fun createRoute(artistId: String): String = "music/artist/$artistId"
    }
    data object AlbumDetail : Screen("music/album/{albumId}", "Album", Icons.Default.Headset) {
        fun createRoute(albumId: String): String = "music/album/$albumId"
    }

    /**
     * Album browse — getAlbumList2 with a selectable ordering (baw.9.1).
     * Accessible from inside [MusicLibraryScreen]; tapping an album navigates
     * to the existing [AlbumDetail] route.
     */
    data object AlbumBrowse : Screen("music/albums", "Albums", Icons.Default.MusicNote)

    /**
     * Genre browse — list of all genres (baw.2.4).
     * Accessible from inside [MusicLibraryScreen].
     */
    data object GenreBrowse : Screen("music/genres", "Genres", Icons.Default.MusicNote)

    /**
     * Genre detail — songs for a selected genre (baw.2.4).
     * [genreName] is URL-encoded by the caller.
     */
    data object GenreDetail : Screen("music/genres/{genreName}", "Genre", Icons.Default.MusicNote) {
        fun createRoute(genreName: String): String =
            "music/genres/${android.net.Uri.encode(genreName)}"
    }

    /**
     * Full-text search screen (baw.4.1).
     * Sectioned results: Artists / Albums / Songs.
     */
    data object MusicSearch : Screen("music/search", "Search", Icons.Default.Search)

    /**
     * Storage-management screen for offline downloads (baw.6.2).
     * Reached from Settings → Downloaded Music.
     */
    data object DownloadedMusic : Screen("music/downloads", "Downloaded Music", Icons.Default.Download)

    /**
     * Audiobookshelf entry (beads_adagio-59p.1.4) — library picker when the
     * server has >1 book library, otherwise auto-forwards to
     * [AudiobookLibrary]. Reached from an "Audiobooks" row on the Library tab
     * (shown only when an Audiobookshelf account exists) — same pattern as
     * the Albums/Genres/Playlists rows, no bottom-nav slot.
     */
    data object Audiobooks : Screen("music/audiobooks", "Audiobooks", Icons.AutoMirrored.Filled.MenuBook)

    /** Book list for one Audiobookshelf library (beads_adagio-59p.1.4). */
    data object AudiobookLibrary : Screen(
        route = "music/audiobooks/library/{libraryId}",
        label = "Audiobooks",
        icon = Icons.AutoMirrored.Filled.MenuBook,
    ) {
        fun createRoute(libraryId: String): String = "music/audiobooks/library/$libraryId"
    }

    /** Audiobook detail — one book's header + progress (beads_adagio-59p.1.4). */
    data object AudiobookDetail : Screen(
        route = "music/audiobooks/item/{itemId}",
        label = "Audiobook",
        icon = Icons.AutoMirrored.Filled.MenuBook,
    ) {
        fun createRoute(itemId: String): String = "music/audiobooks/item/$itemId"
    }

    /**
     * Server-side playlist list (baw.4.2).
     * Distinct from the IPTV M3U [PlaylistDetail] route.
     */
    data object NavidromePlaylistList : Screen(
        route = "music/navidrome-playlists",
        label = "Playlists",
        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
    )

    /**
     * Server-side playlist detail — tracks for one playlist (baw.4.2 / baw.4.3).
     * [playlistId] is passed as a URL path segment.
     */
    data object NavidromePlaylistDetail : Screen(
        route = "music/navidrome-playlists/{playlistId}",
        label = "Playlist",
        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
    ) {
        fun createRoute(playlistId: String): String = "music/navidrome-playlists/$playlistId"
    }
}

/**
 * Bottom-nav tabs, in display order (beads_adagio-15x.2): Live · Library ·
 * Loved · Custom M3Us · Settings.
 *
 * Favorites has no bottom-nav slot — it's pinned atop [Screen.Channels]
 * instead (beads_adagio-15x.1). Library ([Screen.Music]) is always present;
 * the former musicTabEnabled alpha gate is gone (beads_adagio-15x.3).
 */
val bottomNavItems = listOf(
    Screen.Channels,
    Screen.Music,
    Screen.Loved,
    Screen.MyM3Us,
    Screen.Settings,
)
