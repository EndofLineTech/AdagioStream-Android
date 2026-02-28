package com.adagiostream.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
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
    data object Favorites : Screen("favorites", "Favorites", Icons.Default.Favorite)
    data object Accounts : Screen("accounts", "Accounts", Icons.Default.Storage)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object AddAccount : Screen("add_account?accountId={accountId}", "Add Account", Icons.Default.Storage) {
        fun createRoute(accountId: String? = null): String {
            return if (accountId != null) "add_account?accountId=$accountId" else "add_account"
        }
    }
}

val bottomNavItems = listOf(Screen.Channels, Screen.Favorites, Screen.Accounts, Screen.Settings)
