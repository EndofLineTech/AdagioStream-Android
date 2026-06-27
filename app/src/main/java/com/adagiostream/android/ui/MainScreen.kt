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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import com.adagiostream.android.ui.screens.privacy.PrivacyPolicyScreen
import com.adagiostream.android.ui.screens.loved.LovedTracksScreen
import com.adagiostream.android.ui.screens.music.AlbumDetailScreen
import com.adagiostream.android.ui.screens.music.ArtistDetailScreen
import com.adagiostream.android.ui.screens.music.GenreBrowseScreen
import com.adagiostream.android.ui.screens.music.GenreDetailScreen
import com.adagiostream.android.ui.screens.music.MusicLibraryScreen
import com.adagiostream.android.ui.screens.music.NavidromePlaylistDetailScreen
import com.adagiostream.android.ui.screens.music.NavidromePlaylistListScreen
import com.adagiostream.android.ui.screens.music.SearchResultsScreen
import com.adagiostream.android.ui.screens.music.StorageManagementScreen
import com.adagiostream.android.ui.screens.m3us.MyM3UsScreen
import com.adagiostream.android.ui.screens.m3us.PlaylistDetailScreen
import com.adagiostream.android.ui.screens.nowplaying.NowPlayingSheet
import com.adagiostream.android.ui.screens.nowplaying.NowPlayingViewModel
import com.adagiostream.android.ui.screens.settings.SettingsScreen
import com.adagiostream.android.ui.screens.settings.SettingsViewModel
import com.adagiostream.android.ui.screens.setup.SetupScreen
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
    val isCasting by nowPlayingViewModel.isCasting.collectAsStateWithLifecycle()
    val castDeviceName by nowPlayingViewModel.castDeviceName.collectAsStateWithLifecycle()
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
        val isOnSetup = currentDestination?.route == Screen.Setup.route
        val startDestination = if (settings.setupCompleted) Screen.Channels.route else Screen.Setup.route

        Scaffold(
            bottomBar = {
                if (!isOnSetup) {
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
                                isCasting = isCasting,
                                castDeviceName = castDeviceName,
                                onPlayPause = { nowPlayingViewModel.togglePlayPause() },
                                onStop = { nowPlayingViewModel.stop() },
                                onSeekToLive = { nowPlayingViewModel.seekToLive() },
                                onClick = { showNowPlaying = true },
                            )
                        }
                        NavigationBar {
                            // Conditionally add Music when the alpha gate is enabled (baw.2.5, baw.2.6).
                            // Inserted between MyM3Us and Settings for a balanced layout:
                            //   Channels | Favorites | MyM3Us | Music | Settings
                            val visibleNavItems = if (settings.musicTabEnabled) {
                                val base = bottomNavItems.toMutableList()
                                // Insert Music before Settings
                                val settingsIdx = base.indexOfFirst { it == Screen.Settings }
                                base.add(settingsIdx, Screen.Music)
                                base
                            } else {
                                bottomNavItems
                            }
                            visibleNavItems.forEach { screen ->
                                NavigationBarItem(
                                    icon = { Icon(screen.icon, contentDescription = screen.label) },
                                    label = { Text(screen.label) },
                                    selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                    onClick = {
                                        navController.navigate(screen.route) {
                                            popUpTo(Screen.Channels.route) {
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
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                composable(Screen.Setup.route) {
                    SetupScreen(
                        onSetupComplete = {
                            settingsViewModel.reloadSettings()
                            navController.navigate(Screen.Channels.route) {
                                popUpTo(Screen.Setup.route) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Screen.Channels.route) {
                    ChannelsScreen()
                }
                composable(Screen.Favorites.route) {
                    FavoritesScreen(
                        onNavigateToLoved = {
                            navController.navigate(Screen.Loved.route)
                        },
                    )
                }
                composable(Screen.Loved.route) {
                    LovedTracksScreen()
                }
                // Music tab routes (baw.2.5) — always registered so deep-links work
                // even when the tab is hidden by the alpha gate.
                composable(Screen.Music.route) {
                    MusicLibraryScreen(
                        onArtistClick = { artistId ->
                            navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                        },
                        onGenresClick = {
                            navController.navigate(Screen.GenreBrowse.route)
                        },
                        onSearchClick = {
                            navController.navigate(Screen.MusicSearch.route)
                        },
                        onPlaylistsClick = {
                            navController.navigate(Screen.NavidromePlaylistList.route)
                        },
                    )
                }
                // Search screen (baw.4.1)
                composable(Screen.MusicSearch.route) {
                    SearchResultsScreen(
                        onBack = { navController.popBackStack() },
                        onArtistClick = { artistId ->
                            navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                        },
                        onAlbumClick = { albumId ->
                            navController.navigate(Screen.AlbumDetail.createRoute(albumId))
                        },
                    )
                }
                // Navidrome playlist list (baw.4.2)
                composable(Screen.NavidromePlaylistList.route) {
                    NavidromePlaylistListScreen(
                        onBack = { navController.popBackStack() },
                        onPlaylistClick = { playlistId ->
                            navController.navigate(Screen.NavidromePlaylistDetail.createRoute(playlistId))
                        },
                    )
                }
                // Navidrome playlist detail (baw.4.2 / baw.4.3)
                composable(
                    route = Screen.NavidromePlaylistDetail.route,
                    arguments = listOf(
                        navArgument("playlistId") { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    val playlistId = backStackEntry.arguments?.getString("playlistId") ?: ""
                    NavidromePlaylistDetailScreen(
                        onBack = { navController.popBackStack() },
                        playlistId = playlistId,
                    )
                }
                composable(
                    route = Screen.ArtistDetail.route,
                    arguments = listOf(
                        navArgument("artistId") { type = NavType.StringType },
                    ),
                ) {
                    ArtistDetailScreen(
                        onAlbumClick = { albumId ->
                            navController.navigate(Screen.AlbumDetail.createRoute(albumId))
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Screen.AlbumDetail.route,
                    arguments = listOf(
                        navArgument("albumId") { type = NavType.StringType },
                    ),
                ) {
                    AlbumDetailScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                // Genre browse routes (baw.2.4)
                composable(Screen.GenreBrowse.route) {
                    GenreBrowseScreen(
                        onGenreClick = { genreName ->
                            navController.navigate(Screen.GenreDetail.createRoute(genreName))
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Screen.GenreDetail.route,
                    arguments = listOf(
                        navArgument("genreName") { type = NavType.StringType },
                    ),
                ) {
                    GenreDetailScreen(
                        onBack = { navController.popBackStack() },
                        backStackEntry = it,
                    )
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
                composable(Screen.DownloadedMusic.route) {
                    StorageManagementScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onNavigateToAccounts = {
                            navController.navigate(Screen.Accounts.route)
                        },
                        onNavigateToDownloads = {
                            navController.navigate(Screen.DownloadedMusic.route)
                        },
                        onNavigateToGroups = {
                            navController.navigate(Screen.Groups.route)
                        },
                        onNavigateToLicenses = {
                            navController.navigate(Screen.Licenses.route)
                        },
                        onNavigateToPrivacyPolicy = {
                            navController.navigate(Screen.PrivacyPolicy.route)
                        },
                        onDataDeleted = {
                            settingsViewModel.reloadSettings()
                            navController.navigate(Screen.Setup.route) {
                                popUpTo(0) { inclusive = true }
                            }
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
                composable(Screen.PrivacyPolicy.route) {
                    PrivacyPolicyScreen(
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
