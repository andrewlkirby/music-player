package com.musicplayer.presentation

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.musicplayer.presentation.theme.MusicPlayerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MusicPlayerTheme {
                val navController = rememberNavController()
                val playerState by playerViewModel.uiState.collectAsState()

                // Permission handling
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                val permissionState = rememberPermissionState(permission)
                LaunchedEffect(Unit) {
                    if (!permissionState.status.isGranted) {
                        permissionState.launchPermissionRequest()
                    }
                }

                val bottomNavItems = listOf(
                    Triple(Screen.Songs.route, Icons.Default.MusicNote, "Songs"),
                    Triple(Screen.Albums.route, Icons.Default.Album, "Albums"),
                    Triple(Screen.Artists.route, Icons.Default.Person, "Artists"),
                    Triple(Screen.Playlists.route, Icons.AutoMirrored.Filled.QueueMusic, "Lists"),
                    Triple(Screen.Folders.route, Icons.Default.Folder, "Folders")
                )

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val showBottomBar = currentRoute !in listOf(Screen.NowPlaying.route)

                Scaffold(
                    bottomBar = {
                        Column {
                            // Mini player (shown when there's a current song and not on now playing)
                            AnimatedVisibility(
                                visible = playerState.currentSong != null && showBottomBar,
                                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                            ) {
                                MiniPlayer(
                                    state = playerState,
                                    onTap = { navController.navigate(Screen.NowPlaying.route) },
                                    onPlayPause = { playerViewModel.togglePlayPause() },
                                    onNext = { playerViewModel.seekToNext() }
                                )
                            }

                            // Bottom nav bar
                            if (showBottomBar) {
                                NavigationBar {
                                    val navLabelStyle = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp
                                    )
                                    bottomNavItems.forEach { (route, icon, label) ->
                                        NavigationBarItem(
                                            icon = { Icon(icon, contentDescription = label) },
                                            label = {
                                                Text(
                                                    label,
                                                    style = navLabelStyle,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            selected = navBackStackEntry?.destination
                                                ?.hierarchy?.any { it.route == route } == true,
                                            onClick = {
                                                if (currentRoute != route) {
                                                    navController.navigate(route) {
                                                        popUpTo(navController.graph.findStartDestination().id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    // Settings gear at end of bottom bar
                                    NavigationBarItem(
                                        icon = { Icon(Icons.Default.Settings, "Settings") },
                                        label = {
                                            Text(
                                                "Settings",
                                                style = navLabelStyle,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        selected = currentRoute == Screen.Settings.route,
                                        onClick = {
                                            if (currentRoute != Screen.Settings.route) {
                                                navController.navigate(Screen.Settings.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    MusicNavHost(
                        navController = navController,
                        playerViewModel = playerViewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}