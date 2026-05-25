package com.adagiostream.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Star
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
    data object Favorites : Screen("favorites", "Favorites", Icons.Default.Star)
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
}

val bottomNavItems = listOf(Screen.Channels, Screen.Favorites, Screen.Loved, Screen.MyM3Us, Screen.Settings)
