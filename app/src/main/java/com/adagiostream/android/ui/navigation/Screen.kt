package com.adagiostream.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gavel
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
    data object Channels : Screen("channels", "Channels", Icons.Default.Radio)

    /**
     * Full multi-channel EPG guide (beads_adagio-7pm) — reached from a top-bar icon on
     * [Channels], not a bottom-nav slot (tab layout is owned by another workstream).
     */
    data object Guide : Screen("guide", "Guide", Icons.Default.CalendarMonth)
    data object Favorites : Screen("favorites", "Favorites", Icons.Default.Star)
    /**
     * Loved tracks — retained as a route for deep-linking from Favorites.
     *
     * NOTE (baw.2.5): The 'Loved' tab was folded into Favorites to keep the
     * bottom nav at 5 items (Material Design max) when the Music tab was added.
     * The LovedTracksScreen is still accessible via a "Loved" button inside
     * FavoritesScreen — it just no longer has its own bottom-nav slot.
     */
    data object Loved : Screen("loved", "Loved", Icons.Default.MusicNote)
    data object Accounts : Screen("accounts", "Accounts", Icons.Default.Storage)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object Groups : Screen("groups", "Groups", Icons.Default.Folder)
    data object MyM3Us : Screen("my_m3us", "My M3Us", Icons.AutoMirrored.Filled.PlaylistPlay)
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
     * Music tab — Navidrome library browse (baw.2.5).
     *
     * Replaces the standalone 'Loved' bottom-nav slot.  Loved tracks are now
     * accessed from inside FavoritesScreen, keeping the nav count at 5.
     *
     * Visibility is gated by [AppSettings.musicTabEnabled] (baw.2.6); when
     * the flag is false the tab is hidden entirely (coded-but-dark).
     */
    data object Music : Screen("music", "Music", Icons.Default.Headset)

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
 * Base bottom-nav items that are always visible.
 *
 * Music is NOT included here — it is conditionally appended in [MainScreen]
 * based on [AppSettings.musicTabEnabled] (baw.2.6 alpha gate).
 *
 * The previous 'Loved' item has been replaced by 'Music'; Loved content is
 * accessible from inside FavoritesScreen (baw.2.5 PO decision).
 */
val bottomNavItems = listOf(
    Screen.Channels,
    Screen.Favorites,
    Screen.MyM3Us,
    Screen.Settings,
)
