package com.adagiostream.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.ui.components.MiniPlayerBar
import com.adagiostream.android.ui.navigation.Screen
import com.adagiostream.android.ui.navigation.bottomNavItems
import com.adagiostream.android.ui.screens.channels.ChannelsScreen
import com.adagiostream.android.ui.screens.favorites.FavoritesScreen
import com.adagiostream.android.ui.screens.nowplaying.NowPlayingSheet
import com.adagiostream.android.ui.screens.nowplaying.NowPlayingViewModel
import com.adagiostream.android.ui.screens.providers.AddProviderScreen
import com.adagiostream.android.ui.screens.providers.ProvidersScreen
import com.adagiostream.android.ui.screens.settings.SettingsScreen
import com.adagiostream.android.ui.screens.settings.SettingsViewModel
import com.adagiostream.android.ui.theme.AdagioStreamTheme

@Composable
fun MainScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    nowPlayingViewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val playbackState by nowPlayingViewModel.playbackState.collectAsStateWithLifecycle()
    val currentChannel by nowPlayingViewModel.currentChannel.collectAsStateWithLifecycle()
    var showNowPlaying by remember { mutableStateOf(false) }

    AdagioStreamTheme(appearanceMode = settings.appearanceMode) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        val showMiniPlayer = currentChannel != null && playbackState !is PlaybackState.Idle

        Scaffold(
            bottomBar = {
                Column {
                    if (showMiniPlayer && currentChannel != null) {
                        MiniPlayerBar(
                            channel = currentChannel!!,
                            playbackState = playbackState,
                            onPlayPause = { nowPlayingViewModel.togglePlayPause() },
                            onStop = { nowPlayingViewModel.stop() },
                            onClick = { showNowPlaying = true },
                        )
                    }
                    NavigationBar {
                        bottomNavItems.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon, contentDescription = screen.label) },
                                label = { Text(screen.label) },
                                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Channels.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                composable(Screen.Channels.route) {
                    ChannelsScreen()
                }
                composable(Screen.Favorites.route) {
                    FavoritesScreen()
                }
                composable(Screen.Providers.route) {
                    ProvidersScreen(
                        onAddProvider = {
                            navController.navigate(Screen.AddProvider.route)
                        },
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsScreen()
                }
                composable(Screen.AddProvider.route) {
                    AddProviderScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        if (showNowPlaying) {
            NowPlayingSheet(
                onDismiss = { showNowPlaying = false },
            )
        }
    }
}
