package com.torrenttv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.MaterialTheme
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.ui.DetailsScreen
import com.torrenttv.app.ui.HomeScreen
import com.torrenttv.app.ui.SearchScreen

/** Экраны приложения. */
sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data class Details(val item: MediaItem) : Screen
    data class MagnetEntry(val item: MediaItem) : Screen
    data class Player(val item: MediaItem) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            currentVersion = BuildConfig.VERSION_NAME,
            onOpenSearch = { screen = Screen.Search },
            onOpenDetails = { screen = Screen.Details(it) },
        )

        Screen.Search -> SearchScreen(
            onBack = { screen = Screen.Home },
            onOpenDetails = { screen = Screen.Details(it) },
        )

        is Screen.Details -> DetailsScreen(
            item = s.item,
            onBack = { screen = Screen.Home },
            onWatch = { screen = Screen.Player(s.item) },
            onEnterMagnet = { screen = Screen.MagnetEntry(s.item) },
        )

        is Screen.MagnetEntry -> MagnetEntryScreen(
            item = s.item,
            onDone = { screen = Screen.Player(s.item) },
            onBack = { screen = Screen.Details(s.item) },
        )

        is Screen.Player -> PlayerScreen(
            item = s.item,
            onBack = { screen = Screen.Details(s.item) },
        )
    }
}
