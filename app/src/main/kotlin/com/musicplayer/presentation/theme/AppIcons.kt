package com.musicplayer.presentation.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.automirrored.sharp.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.sharp.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

val LocalIconStyle = staticCompositionLocalOf { IconStyle.FILLED }

/**
 * Central icon accessor: every icon the app uses, resolved to the current
 * theme's [IconStyle] (Filled / Outlined / Rounded / Sharp) via [LocalIconStyle].
 */
object AppIcons {
    val MusicNote: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.MusicNote
        IconStyle.OUTLINED -> Icons.Outlined.MusicNote
        IconStyle.ROUNDED -> Icons.Rounded.MusicNote
        IconStyle.SHARP -> Icons.Sharp.MusicNote
    }

    val Album: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Album
        IconStyle.OUTLINED -> Icons.Outlined.Album
        IconStyle.ROUNDED -> Icons.Rounded.Album
        IconStyle.SHARP -> Icons.Sharp.Album
    }

    val Person: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Person
        IconStyle.OUTLINED -> Icons.Outlined.Person
        IconStyle.ROUNDED -> Icons.Rounded.Person
        IconStyle.SHARP -> Icons.Sharp.Person
    }

    val Folder: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Folder
        IconStyle.OUTLINED -> Icons.Outlined.Folder
        IconStyle.ROUNDED -> Icons.Rounded.Folder
        IconStyle.SHARP -> Icons.Sharp.Folder
    }

    val Settings: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Settings
        IconStyle.OUTLINED -> Icons.Outlined.Settings
        IconStyle.ROUNDED -> Icons.Rounded.Settings
        IconStyle.SHARP -> Icons.Sharp.Settings
    }

    val Pause: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Pause
        IconStyle.OUTLINED -> Icons.Outlined.Pause
        IconStyle.ROUNDED -> Icons.Rounded.Pause
        IconStyle.SHARP -> Icons.Sharp.Pause
    }

    val PlayArrow: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.PlayArrow
        IconStyle.OUTLINED -> Icons.Outlined.PlayArrow
        IconStyle.ROUNDED -> Icons.Rounded.PlayArrow
        IconStyle.SHARP -> Icons.Sharp.PlayArrow
    }

    val SkipNext: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.SkipNext
        IconStyle.OUTLINED -> Icons.Outlined.SkipNext
        IconStyle.ROUNDED -> Icons.Rounded.SkipNext
        IconStyle.SHARP -> Icons.Sharp.SkipNext
    }

    val CreateNewFolder: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.CreateNewFolder
        IconStyle.OUTLINED -> Icons.Outlined.CreateNewFolder
        IconStyle.ROUNDED -> Icons.Rounded.CreateNewFolder
        IconStyle.SHARP -> Icons.Sharp.CreateNewFolder
    }

    val FolderOff: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.FolderOff
        IconStyle.OUTLINED -> Icons.Outlined.FolderOff
        IconStyle.ROUNDED -> Icons.Rounded.FolderOff
        IconStyle.SHARP -> Icons.Sharp.FolderOff
    }

    val RemoveCircleOutline: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.RemoveCircleOutline
        IconStyle.OUTLINED -> Icons.Outlined.RemoveCircleOutline
        IconStyle.ROUNDED -> Icons.Rounded.RemoveCircleOutline
        IconStyle.SHARP -> Icons.Sharp.RemoveCircleOutline
    }

    val Refresh: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Refresh
        IconStyle.OUTLINED -> Icons.Outlined.Refresh
        IconStyle.ROUNDED -> Icons.Rounded.Refresh
        IconStyle.SHARP -> Icons.Sharp.Refresh
    }

    val Image: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Image
        IconStyle.OUTLINED -> Icons.Outlined.Image
        IconStyle.ROUNDED -> Icons.Rounded.Image
        IconStyle.SHARP -> Icons.Sharp.Image
    }

    val Close: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Close
        IconStyle.OUTLINED -> Icons.Outlined.Close
        IconStyle.ROUNDED -> Icons.Rounded.Close
        IconStyle.SHARP -> Icons.Sharp.Close
    }

    val SystemUpdate: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.SystemUpdate
        IconStyle.OUTLINED -> Icons.Outlined.SystemUpdate
        IconStyle.ROUNDED -> Icons.Rounded.SystemUpdate
        IconStyle.SHARP -> Icons.Sharp.SystemUpdate
    }

    val CheckCircle: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.CheckCircle
        IconStyle.OUTLINED -> Icons.Outlined.CheckCircle
        IconStyle.ROUNDED -> Icons.Rounded.CheckCircle
        IconStyle.SHARP -> Icons.Sharp.CheckCircle
    }

    val Download: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Download
        IconStyle.OUTLINED -> Icons.Outlined.Download
        IconStyle.ROUNDED -> Icons.Rounded.Download
        IconStyle.SHARP -> Icons.Sharp.Download
    }

    val ErrorOutline: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.ErrorOutline
        IconStyle.OUTLINED -> Icons.Outlined.ErrorOutline
        IconStyle.ROUNDED -> Icons.Rounded.ErrorOutline
        IconStyle.SHARP -> Icons.Sharp.ErrorOutline
    }

    val KeyboardArrowDown: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.KeyboardArrowDown
        IconStyle.OUTLINED -> Icons.Outlined.KeyboardArrowDown
        IconStyle.ROUNDED -> Icons.Rounded.KeyboardArrowDown
        IconStyle.SHARP -> Icons.Sharp.KeyboardArrowDown
    }

    val Equalizer: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Equalizer
        IconStyle.OUTLINED -> Icons.Outlined.Equalizer
        IconStyle.ROUNDED -> Icons.Rounded.Equalizer
        IconStyle.SHARP -> Icons.Sharp.Equalizer
    }

    val Bedtime: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Bedtime
        IconStyle.OUTLINED -> Icons.Outlined.Bedtime
        IconStyle.ROUNDED -> Icons.Rounded.Bedtime
        IconStyle.SHARP -> Icons.Sharp.Bedtime
    }

    val Favorite: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Favorite
        IconStyle.OUTLINED -> Icons.Outlined.Favorite
        IconStyle.ROUNDED -> Icons.Rounded.Favorite
        IconStyle.SHARP -> Icons.Sharp.Favorite
    }

    val FavoriteBorder: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.FavoriteBorder
        IconStyle.OUTLINED -> Icons.Outlined.FavoriteBorder
        IconStyle.ROUNDED -> Icons.Rounded.FavoriteBorder
        IconStyle.SHARP -> Icons.Sharp.FavoriteBorder
    }

    val Shuffle: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Shuffle
        IconStyle.OUTLINED -> Icons.Outlined.Shuffle
        IconStyle.ROUNDED -> Icons.Rounded.Shuffle
        IconStyle.SHARP -> Icons.Sharp.Shuffle
    }

    val SkipPrevious: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.SkipPrevious
        IconStyle.OUTLINED -> Icons.Outlined.SkipPrevious
        IconStyle.ROUNDED -> Icons.Rounded.SkipPrevious
        IconStyle.SHARP -> Icons.Sharp.SkipPrevious
    }

    val RepeatOne: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.RepeatOne
        IconStyle.OUTLINED -> Icons.Outlined.RepeatOne
        IconStyle.ROUNDED -> Icons.Rounded.RepeatOne
        IconStyle.SHARP -> Icons.Sharp.RepeatOne
    }

    val Repeat: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Repeat
        IconStyle.OUTLINED -> Icons.Outlined.Repeat
        IconStyle.ROUNDED -> Icons.Rounded.Repeat
        IconStyle.SHARP -> Icons.Sharp.Repeat
    }

    val Home: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Home
        IconStyle.OUTLINED -> Icons.Outlined.Home
        IconStyle.ROUNDED -> Icons.Rounded.Home
        IconStyle.SHARP -> Icons.Sharp.Home
    }

    val ChevronRight: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.ChevronRight
        IconStyle.OUTLINED -> Icons.Outlined.ChevronRight
        IconStyle.ROUNDED -> Icons.Rounded.ChevronRight
        IconStyle.SHARP -> Icons.Sharp.ChevronRight
    }

    val FolderOpen: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.FolderOpen
        IconStyle.OUTLINED -> Icons.Outlined.FolderOpen
        IconStyle.ROUNDED -> Icons.Rounded.FolderOpen
        IconStyle.SHARP -> Icons.Sharp.FolderOpen
    }

    val Search: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Search
        IconStyle.OUTLINED -> Icons.Outlined.Search
        IconStyle.ROUNDED -> Icons.Rounded.Search
        IconStyle.SHARP -> Icons.Sharp.Search
    }

    val SearchOff: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.SearchOff
        IconStyle.OUTLINED -> Icons.Outlined.SearchOff
        IconStyle.ROUNDED -> Icons.Rounded.SearchOff
        IconStyle.SHARP -> Icons.Sharp.SearchOff
    }

    val Clear: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Clear
        IconStyle.OUTLINED -> Icons.Outlined.Clear
        IconStyle.ROUNDED -> Icons.Rounded.Clear
        IconStyle.SHARP -> Icons.Sharp.Clear
    }

    val Add: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Add
        IconStyle.OUTLINED -> Icons.Outlined.Add
        IconStyle.ROUNDED -> Icons.Rounded.Add
        IconStyle.SHARP -> Icons.Sharp.Add
    }

    val AccessTime: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.AccessTime
        IconStyle.OUTLINED -> Icons.Outlined.AccessTime
        IconStyle.ROUNDED -> Icons.Rounded.AccessTime
        IconStyle.SHARP -> Icons.Sharp.AccessTime
    }

    val Delete: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.Delete
        IconStyle.OUTLINED -> Icons.Outlined.Delete
        IconStyle.ROUNDED -> Icons.Rounded.Delete
        IconStyle.SHARP -> Icons.Sharp.Delete
    }

    val ArrowBack: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.AutoMirrored.Filled.ArrowBack
        IconStyle.OUTLINED -> Icons.AutoMirrored.Outlined.ArrowBack
        IconStyle.ROUNDED -> Icons.AutoMirrored.Rounded.ArrowBack
        IconStyle.SHARP -> Icons.AutoMirrored.Sharp.ArrowBack
    }

    val QueueMusic: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.AutoMirrored.Filled.QueueMusic
        IconStyle.OUTLINED -> Icons.AutoMirrored.Outlined.QueueMusic
        IconStyle.ROUNDED -> Icons.AutoMirrored.Rounded.QueueMusic
        IconStyle.SHARP -> Icons.AutoMirrored.Sharp.QueueMusic
    }

    val VolumeUp: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.AutoMirrored.Filled.VolumeUp
        IconStyle.OUTLINED -> Icons.AutoMirrored.Outlined.VolumeUp
        IconStyle.ROUNDED -> Icons.AutoMirrored.Rounded.VolumeUp
        IconStyle.SHARP -> Icons.AutoMirrored.Sharp.VolumeUp
    }

    val Sort: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.AutoMirrored.Filled.Sort
        IconStyle.OUTLINED -> Icons.AutoMirrored.Outlined.Sort
        IconStyle.ROUNDED -> Icons.AutoMirrored.Rounded.Sort
        IconStyle.SHARP -> Icons.AutoMirrored.Sharp.Sort
    }

    val TrendingUp: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.AutoMirrored.Filled.TrendingUp
        IconStyle.OUTLINED -> Icons.AutoMirrored.Outlined.TrendingUp
        IconStyle.ROUNDED -> Icons.AutoMirrored.Rounded.TrendingUp
        IconStyle.SHARP -> Icons.AutoMirrored.Sharp.TrendingUp
    }

    val PlaylistAdd: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.AutoMirrored.Filled.PlaylistAdd
        IconStyle.OUTLINED -> Icons.AutoMirrored.Outlined.PlaylistAdd
        IconStyle.ROUNDED -> Icons.AutoMirrored.Rounded.PlaylistAdd
        IconStyle.SHARP -> Icons.AutoMirrored.Sharp.PlaylistAdd
    }

    val DragHandle: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.DragHandle
        IconStyle.OUTLINED -> Icons.Outlined.DragHandle
        IconStyle.ROUNDED -> Icons.Rounded.DragHandle
        IconStyle.SHARP -> Icons.Sharp.DragHandle
    }
}
