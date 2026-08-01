package com.musicplayer.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.musicplayer.presentation.browse.albums.AlbumsScreen
import com.musicplayer.presentation.browse.albums.AlbumDetailScreen
import com.musicplayer.presentation.browse.artists.ArtistsScreen
import com.musicplayer.presentation.browse.artists.ArtistDetailScreen
import com.musicplayer.presentation.browse.folders.FoldersScreen
import com.musicplayer.presentation.browse.playlists.PlaylistsScreen
import com.musicplayer.presentation.browse.playlists.PlaylistDetailScreen
import com.musicplayer.presentation.browse.songs.SongsScreen
import com.musicplayer.presentation.nowplaying.NowPlayingScreen
import com.musicplayer.presentation.search.SearchScreen
import com.musicplayer.presentation.equalizer.EqualizerScreen
import com.musicplayer.presentation.settings.SettingsScreen

sealed class Screen(val route: String) {
    data object Songs : Screen("songs")
    data object Albums : Screen("albums")
    data object AlbumDetail : Screen("albums/{albumId}") {
        fun createRoute(albumId: Long) = "albums/$albumId"
    }
    data object Artists : Screen("artists")
    data object ArtistDetail : Screen("artists/{artistId}") {
        fun createRoute(artistId: Long) = "artists/$artistId"
    }
    data object Playlists : Screen("playlists")
    data object PlaylistDetail : Screen("playlists/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlists/$playlistId"
    }
    data object Folders : Screen("folders")
    data object NowPlaying : Screen("now_playing")
    data object Search : Screen("search")
    data object Equalizer : Screen("equalizer")
    data object Settings : Screen("settings")
}

@Composable
fun MusicNavHost(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Songs.route,
        modifier = modifier
    ) {
        composable(Screen.Songs.route) {
            SongsScreen(
                playerViewModel = playerViewModel,
                onNavigateToSearch = { navController.navigate(Screen.Search.route) }
            )
        }
        composable(Screen.Albums.route) {
            AlbumsScreen(
                onAlbumClick = { albumId ->
                    navController.navigate(Screen.AlbumDetail.createRoute(albumId))
                }
            )
        }
        composable(
            Screen.AlbumDetail.route,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType })
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getLong("albumId") ?: return@composable
            AlbumDetailScreen(
                albumId = albumId,
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Artists.route) {
            ArtistsScreen(
                onArtistClick = { artistId ->
                    navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                }
            )
        }
        composable(
            Screen.ArtistDetail.route,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType })
        ) { backStackEntry ->
            val artistId = backStackEntry.arguments?.getLong("artistId") ?: return@composable
            ArtistDetailScreen(
                artistId = artistId,
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
                onAlbumClick = { albumId ->
                    navController.navigate(Screen.AlbumDetail.createRoute(albumId))
                }
            )
        }
        composable(Screen.Playlists.route) {
            PlaylistsScreen(
                onPlaylistClick = { playlistId ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                }
            )
        }
        composable(
            Screen.PlaylistDetail.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
            PlaylistDetailScreen(
                playlistId = playlistId,
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Folders.route) {
            FoldersScreen(playerViewModel = playerViewModel)
        }
        composable(Screen.NowPlaying.route) {
            NowPlayingScreen(
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
                onEqualizerClick = { navController.navigate(Screen.Equalizer.route) }
            )
        }
        composable(Screen.Search.route) {
            SearchScreen(
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Equalizer.route) {
            EqualizerScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
