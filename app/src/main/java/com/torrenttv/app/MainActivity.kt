package com.torrenttv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import com.torrenttv.app.data.IptvChannel
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.ui.CatalogScreen
import com.torrenttv.app.ui.DetailsScreen
import com.torrenttv.app.ui.EpisodesScreen
import com.torrenttv.app.ui.HomeScreen
import com.torrenttv.app.ui.IptvPlayerScreen
import com.torrenttv.app.ui.IptvScreen
import com.torrenttv.app.ui.MagnetEntryScreen
import com.torrenttv.app.ui.PlayerScreen
import com.torrenttv.app.ui.SearchScreen
import com.torrenttv.app.ui.SettingsScreen
import com.torrenttv.app.ui.TopTabBar
import com.torrenttv.app.ui.TorrentSearchScreen

/** Экраны приложения. Верхние — вкладки с общей панелью табов. */
sealed interface Screen {
    data object Home : Screen
    data object Movies : Screen
    data object TvShows : Screen
    data object Iptv : Screen
    data object Search : Screen
    data object Settings : Screen

    // Полноэкранные (без таб-панели)
    data class Details(val item: MediaItem) : Screen
    data class Episodes(val item: MediaItem) : Screen
    data class MagnetEntry(val item: MediaItem) : Screen
    data class TorrentSearch(val item: MediaItem, val episode: com.torrenttv.app.data.Episode? = null) : Screen
    data class Player(val item: MediaItem, val episode: com.torrenttv.app.data.Episode? = null) : Screen
    data class IptvPlayer(val channel: IptvChannel) : Screen

    companion object {
        val TABS: List<Screen> = listOf(Home, Movies, TvShows, Iptv, Search, Settings)
    }
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

    // Системная «Назад» не должна закрывать приложение, пока мы не на «Главной»:
    // плеер/magnet/автопоиск → карточка, карточка и вкладки → «Главная».
    BackHandler(screen != Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Player ->
                if (s.episode != null) Screen.Episodes(s.item) else Screen.Details(s.item)
            is Screen.MagnetEntry -> Screen.Details(s.item)
            is Screen.IptvPlayer -> Screen.Iptv
            else -> Screen.Home
        }
    }

    if (screen in Screen.TABS) {
        // Вкладки: общая панель сверху + содержимое вкладки
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0E1116)),
        ) {
            TopTabBar(active = screen) { screen = it }
            when (screen) {
                Screen.Home -> HomeScreen(
                    currentVersion = BuildConfig.VERSION_NAME,
                    onOpenDetails = { screen = Screen.Details(it) },
                )

                Screen.Movies -> CatalogScreen(isMovies = true, onOpenDetails = { screen = Screen.Details(it) })
                Screen.TvShows -> CatalogScreen(isMovies = false, onOpenDetails = { screen = Screen.Details(it) })
                Screen.Iptv -> IptvScreen(
                    onPlay = { screen = Screen.IptvPlayer(it) },
                )
                Screen.Search -> SearchScreen(
                    onBack = { screen = Screen.Home },
                    onOpenDetails = { screen = Screen.Details(it) },
                )
                Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Home },
                )
                else -> Unit
            }
        }
    } else {
        // Полноэкранные: карточка, magnet, плеер
        when (val s = screen) {
            is Screen.Details -> DetailsScreen(
                item = s.item,
                onBack = { screen = Screen.Home },
                onWatch = { screen = Screen.Player(s.item) },
                onEnterMagnet = { screen = Screen.MagnetEntry(s.item) },
                onAutoSearch = { screen = Screen.TorrentSearch(s.item) },
                onOpenDetails = { screen = Screen.Details(it) },
                onEpisodes = { screen = Screen.Episodes(s.item) },
            )

            is Screen.Episodes -> EpisodesScreen(
                item = s.item,
                onPlay = { ep -> screen = Screen.Player(s.item, ep) },
                onFind = { ep -> screen = Screen.TorrentSearch(s.item, ep) },
                onBack = { screen = Screen.Details(s.item) },
            )

            is Screen.MagnetEntry -> MagnetEntryScreen(
                item = s.item,
                onDone = { screen = Screen.Player(s.item) },
                onBack = { screen = Screen.Details(s.item) },
            )

            is Screen.TorrentSearch -> TorrentSearchScreen(
                item = s.item,
                episode = s.episode,
                onDone = {
                    screen = if (s.episode != null) Screen.Player(s.item, s.episode)
                    else Screen.Details(s.item)
                },
                onBack = {
                    screen = if (s.episode != null) Screen.Episodes(s.item)
                    else Screen.Details(s.item)
                },
            )

            is Screen.Player -> PlayerScreen(
                item = s.item,
                episode = s.episode,
                onBack = {
                    screen = if (s.episode != null) Screen.Episodes(s.item)
                    else Screen.Details(s.item)
                },
            )

            is Screen.IptvPlayer -> IptvPlayerScreen(
                channel = s.channel,
                onBack = { screen = Screen.Iptv },
            )

            else -> Unit
        }
    }
}
