package com.adagiostream.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.adagiostream.android.model.PlaybackState
import com.adagiostream.android.ui.components.AdagioSplashOverlay
import com.adagiostream.android.ui.components.MiniPlayerBar
import com.adagiostream.android.ui.navigation.Screen
import com.adagiostream.android.ui.navigation.bottomNavItems
import com.adagiostream.android.ui.screens.accounts.AccountsScreen
import com.adagiostream.android.ui.screens.accounts.AddAccountScreen
import com.adagiostream.android.ui.screens.channels.ChannelsScreen
import com.adagiostream.android.ui.screens.favorites.FavoritesScreen
import com.adagiostream.android.ui.screens.groups.GroupManagementScreen
import com.adagiostream.android.ui.screens.licenses.LicensesScreen
import com.adagiostream.android.ui.screens.loved.LovedTracksScreen
import com.adagiostream.android.ui.screens.m3us.MyM3UsScreen
import com.adagiostream.android.ui.screens.m3us.PlaylistDetailScreen
import com.adagiostream.android.ui.screens.nowplaying.NowPlayingSheet
import com.adagiostream.android.ui.screens.nowplaying.NowPlayingViewModel
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
    val listeningTimeMs by nowPlayingViewModel.listeningTimeMs.collectAsStateWithLifecycle()
    val trackMetadata by nowPlayingViewModel.currentTrackMetadata.collectAsStateWithLifecycle()
    val espnGame by nowPlayingViewModel.currentESPNGame.collectAsStateWithLifecycle()
    val isTimeShifted by nowPlayingViewModel.isTimeShifted.collectAsStateWithLifecycle()
    val channels by settingsViewModel.channels.collectAsStateWithLifecycle()
    var showNowPlaying by remember { mutableStateOf(false) }
    var hasAttemptedStartupStream by remember { mutableStateOf(false) }
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(channels, settings.startupStreamID) {
        if (!hasAttemptedStartupStream && settings.startupStreamID != null && channels.isNotEmpty()) {
            hasAttemptedStartupStream = true
            settingsViewModel.playStartupStream()
        }
    }

    AdagioStreamTheme(
        appearanceMode = settings.appearanceMode,
        textSizeMode = settings.textSizeMode,
    ) {
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
                            listeningTimeMs = listeningTimeMs,
                            trackMetadata = trackMetadata,
                            espnGame = espnGame,
                            artworkDisplayMode = settings.artworkDisplayMode,
                            isTimeShifted = isTimeShifted,
                            onPlayPause = { nowPlayingViewModel.togglePlayPause() },
                            onStop = { nowPlayingViewModel.stop() },
                            onSeekToLive = { nowPlayingViewModel.seekToLive() },
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
                composable(Screen.Loved.route) {
                    LovedTracksScreen()
                }
                composable(Screen.MyM3Us.route) {
                    MyM3UsScreen(
                        onPlaylistClick = { playlistId ->
                            navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                        },
                    )
                }
                composable(
                    route = Screen.PlaylistDetail.route,
                    arguments = listOf(
                        navArgument("playlistId") { type = NavType.StringType },
                    ),
                ) {
                    PlaylistDetailScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Accounts.route) {
                    AccountsScreen(
                        onAddAccount = {
                            navController.navigate(Screen.AddAccount.createRoute())
                        },
                        onEditAccount = { accountId ->
                            navController.navigate(Screen.AddAccount.createRoute(accountId))
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onNavigateToAccounts = {
                            navController.navigate(Screen.Accounts.route)
                        },
                        onNavigateToGroups = {
                            navController.navigate(Screen.Groups.route)
                        },
                        onNavigateToLicenses = {
                            navController.navigate(Screen.Licenses.route)
                        },
                    )
                }
                composable(Screen.Groups.route) {
                    GroupManagementScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Licenses.route) {
                    LicensesScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Screen.AddAccount.route,
                    arguments = listOf(
                        navArgument("accountId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) {
                    AddAccountScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        if (showNowPlaying) {
            NowPlayingSheet(
                onDismiss = { showNowPlaying = false },
                artworkDisplayMode = settings.artworkDisplayMode,
            )
        }

        AdagioSplashOverlay(
            visible = showSplash,
            onFinished = { showSplash = false },
        )
    }
}
