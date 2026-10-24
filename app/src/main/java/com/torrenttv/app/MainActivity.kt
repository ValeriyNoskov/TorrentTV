package com.torrenttv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import com.torrenttv.app.ui.AppTypography
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.ui.CatalogScreen
import com.torrenttv.app.ui.DetailsScreen
import com.torrenttv.app.ui.EpisodesScreen
import com.torrenttv.app.ui.TrailerScreen
import com.torrenttv.app.ui.TorrentsScreen
import com.torrenttv.app.ui.FavoritesScreen
import com.torrenttv.app.ui.ContinueScreen
import com.torrenttv.app.ui.WatchLaterScreen
import com.torrenttv.app.ui.HomeScreen
import com.torrenttv.app.ui.MagnetEntryScreen
import com.torrenttv.app.ui.PlayerScreen
import com.torrenttv.app.ui.SearchScreen
import com.torrenttv.app.ui.SettingsScreen
import com.torrenttv.app.ui.SideNav
import com.torrenttv.app.ui.SmartPlayScreen
import com.torrenttv.app.ui.TorrentFilesScreen
import com.torrenttv.app.ui.TorrentResultsScreen
import com.torrenttv.app.ui.TorrentSearchScreen

/** Экраны приложения. Верхние — вкладки с общей панелью табов. */
sealed interface Screen {
    data object Home : Screen
    data object Movies : Screen
    data object TvShows : Screen
    data object Shows : Screen
    data object Torrents : Screen
    data object Favorites : Screen
    data object Continue : Screen
    data object WatchLater : Screen
    data object Search : Screen
    data object Settings : Screen

    // Полноэкранные (без таб-панели)
    data class Details(val item: MediaItem) : Screen
    data class Episodes(val item: MediaItem, val season: Int = 0) : Screen
    data class MagnetEntry(val item: MediaItem) : Screen
    data class TorrentSearch(
        val item: MediaItem,
        val episode: com.torrenttv.app.data.Episode? = null,
        /** Zona-режим: сразу подобрать лучшую раздачу и начать просмотр. */
        val autoStart: Boolean = true,
    ) : Screen
    data class Watch(val item: MediaItem) : Screen
    data class Trailer(val item: MediaItem) : Screen
    data class RutubeWatch(val videoId: String, val title: String, val thumb: String) : Screen
    data class Player(val item: MediaItem, val episode: com.torrenttv.app.data.Episode? = null, val fileIndex: Int = -1) : Screen
    data class TorrentFiles(val item: MediaItem, val magnet: String) : Screen
    data class TorrentResults(
        val item: MediaItem,
        val query: String,
        val results: List<com.torrenttv.app.data.TorrentResult>,
        val poster: String = "",
        val posterMap: Map<String, String> = emptyMap(),
    ) : Screen

    companion object {
        val TABS: List<Screen> = listOf(Home, Movies, TvShows, Shows, Favorites, Continue, WatchLater, Search, Settings)
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        /** Обработчик кнопок пульта активного плеера: true — кнопка перехвачена.
         *  Живёт на уровне Activity: пульт работает при ЛЮБОМ фокусе. */
        @Volatile var remoteKeyHandler: ((Int) -> Boolean)? = null
    }

    /** Уход из приложения — фиксируем настройки в облачном бэкапе (экономно, при изменениях). */
    override fun onPause() {
        super.onPause()
        com.torrenttv.app.data.SettingsBackup.save(this)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP && event.repeatCount == 0) {
            if (remoteKeyHandler?.invoke(event.keyCode) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.torrenttv.app.data.AppLog.init(this)
        // Дисковый кэш метаданных: экономит дневной лимит API и работает без сети
        com.torrenttv.app.data.KinopoiskClient.attachCache(cacheDir)
        // Настройки переживают сброс данных: из пустого хранилища восстановим бэкап из Gist.
        // Ключи применены ниже в onCreate — после восстановления применяем их повторно.
        com.torrenttv.app.data.SettingsBackup.restoreIfNeeded(this) {
            val s = com.torrenttv.app.data.LocalStore(this)
            com.torrenttv.app.data.AppLog.recording = s.loggingEnabled()
            com.torrenttv.app.data.KinopoiskClient.overrideApiKey = s.kpApiKey()
            com.torrenttv.app.data.TmdbClient.overrideApiKey = s.tmdbApiKey()
            com.torrenttv.app.data.AppLog.log("backup", "ключи применены после восстановления")
        }
        // Личные ключи баз фильмов (перекрывают вшитые общие)
        val store0 = com.torrenttv.app.data.LocalStore(this)
        // Галочка «Запись логов» (Настройки → Логи): включена по умолчанию
        com.torrenttv.app.data.AppLog.recording = store0.loggingEnabled()
        com.torrenttv.app.data.KinopoiskClient.overrideApiKey = store0.kpApiKey()
        com.torrenttv.app.data.TmdbClient.overrideApiKey = store0.tmdbApiKey()
        // Прогрев torrent-сессии: DHT/трекеры готовы заранее —
        // метаданные раздач и стрим стартуют быстрее
        Thread { com.torrenttv.app.torrent.TorrentEngine.start() }.apply {
            isDaemon = true
            start()
        }
        // Размер карточек из настроек (Настройки → Плеер → «Размер карточек»)
        com.torrenttv.app.ui.cardWidthDp =
            com.torrenttv.app.data.LocalStore(this).cardWidthDp()
        setContent {
            MaterialTheme(typography = AppTypography) {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // Последняя открытая вкладка: из карточки/плеера BACK возвращает СЮДА,
    // а не на «Главную» (жалоба: вышел из «Спецов» — выкинуло с «Торрентов»)
    var lastTab by remember { mutableStateOf<Screen>(Screen.Home) }

    // Полный лог навигации: «нажал кнопку → открылось то-то» — видно в выгрузке лога
    LaunchedEffect(screen) {
        if (screen in Screen.TABS) lastTab = screen
        val name = when (val s = screen) {
            Screen.Home -> "Главная"
            Screen.Movies -> "Фильмы"
            Screen.TvShows -> "Сериалы"
            Screen.Shows -> "Шоу"
            Screen.Torrents -> "Торренты"
            Screen.Favorites -> "Избранное"
            Screen.Continue -> "Продолжить"
            Screen.WatchLater -> "Смотреть позже"
            Screen.Search -> "Поиск"
            Screen.Settings -> "Настройки"
            is Screen.Details -> "Детали «${s.item.title}»"
            is Screen.Episodes -> "Серии «${s.item.title}» сезон ${s.season}"
            is Screen.MagnetEntry -> "Ввод магнита «${s.item.title}»"
            is Screen.TorrentSearch -> if (s.autoStart) "Автопоиск раздач «${s.item.title}»" else "Поиск раздач «${s.item.title}»"
            is Screen.Watch -> "Умный подбор «${s.item.title}»"
            is Screen.Trailer -> "Трейлер «${s.item.title}»"
            is Screen.RutubeWatch -> "Rutube «${s.title}»"
            is Screen.Player -> if (s.episode != null) "Плеер «${s.item.title}» ${s.episode.code}" else "Плеер «${s.item.title}»"
            is Screen.TorrentFiles -> "Состав раздачи «${s.item.title}»"
            is Screen.TorrentResults -> "Результаты «${s.item.title}»"
        }
        com.torrenttv.app.data.AppLog.log("nav", "открыл: $name")
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val appStore = androidx.compose.ui.platform.LocalContext.current.let {
        remember { com.torrenttv.app.data.LocalStore(it) }
    }

    // На «Главной» выход двойным «Назад»: первое нажатие — подсказка,
    // второе в течение 2 сек — закрываем приложение
    var lastBackAt by remember { mutableStateOf(0L) }
    BackHandler(screen == Screen.Home) {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000) {
            (context as? android.app.Activity)?.finishAffinity()
        } else {
            lastBackAt = now
            android.widget.Toast.makeText(
                context,
                "Нажмите «Назад» ещё раз для выхода",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Системная «Назад» не должна закрывать приложение, пока мы не на «Главной»:
    // плеер → серии → карточка → ТА ВКЛАДКА, откуда зашли (не всегда «Главная»)
    BackHandler(screen != Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Player ->
                if (s.episode != null) Screen.Episodes(s.item) else Screen.Details(s.item)
            is Screen.MagnetEntry -> Screen.Details(s.item)
            else -> lastTab
        }
    }

    if (screen in Screen.TABS) {
        // Вкладки: левый навигационный бар + содержимое вкладки
        Row(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0E1116)),
        ) {
            SideNav(active = screen) { screen = it }
            Box(Modifier.weight(1f)) {
                when (screen) {
                Screen.Home -> HomeScreen(
                    currentVersion = BuildConfig.VERSION_NAME,
                    onOpenDetails = { screen = Screen.Details(it) },
                    onOpenCatalog = { mode ->
                        screen = when (mode) {
                            "movies" -> Screen.Movies
                            "tv" -> Screen.TvShows
                            else -> Screen.Shows
                        }
                    },
                )

                Screen.Movies -> CatalogScreen(mode = "movies", onOpenDetails = { screen = Screen.Details(it) })
                Screen.TvShows -> CatalogScreen(mode = "tv", onOpenDetails = { screen = Screen.Details(it) })
                Screen.Shows -> CatalogScreen(mode = "shows", onOpenDetails = { screen = Screen.Details(it) })
                Screen.Torrents -> TorrentsScreen(onOpenDetails = { screen = Screen.Details(it) })
                Screen.Favorites -> FavoritesScreen(onOpenDetails = { screen = Screen.Details(it) })
                Screen.Continue -> ContinueScreen(onOpenDetails = { screen = Screen.Details(it) })
                Screen.WatchLater -> WatchLaterScreen(onOpenDetails = { screen = Screen.Details(it) })
                Screen.Search -> SearchScreen(
                    onBack = { screen = Screen.Home },
                    onOpenDetails = { screen = Screen.Details(it) },
                    onOpenResults = { card, q, list, pmap ->
                        screen = Screen.TorrentResults(card, q, list, poster = card.posterUrl, posterMap = pmap)
                    },
                    onWatchRutube = { vid, title, thumb ->
                        screen = Screen.RutubeWatch(vid, title, thumb)
                    },
                    onTorrentSearch = { q ->
                        // Прямой торрент-поиск по запросу: фиктивная карточка
                        screen = Screen.TorrentSearch(
                            MediaItem(id = q.hashCode(), type = "movie", title = q),
                            autoStart = false,
                        )
                    },
                )
                Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Home },
                )
                else -> Unit
                }
            }
        }
    } else {
        // Полноэкранные: карточка, magnet, плеер
        when (val s = screen) {
            is Screen.Details -> DetailsScreen(
                item = s.item,
                // Возврат на ту вкладку, откуда открыли (Торренты/Каталог/Поиск)
                onBack = { screen = lastTab },
                onWatch = { screen = Screen.Player(s.item) },
                onEnterMagnet = { screen = Screen.MagnetEntry(s.item) },
                // Zona-флоу: «Смотреть» → авто-подбор лучшей раздачи → плеер
                onWatchSmart = { screen = Screen.Watch(s.item) },
                onAutoSearch = { screen = Screen.TorrentSearch(s.item, autoStart = true) },
                onManualSearch = { screen = Screen.TorrentSearch(s.item, autoStart = false) },
                onTrailer = { screen = Screen.Trailer(s.item) },
                onOpenDetails = { screen = Screen.Details(it) },
                onEpisodes = { season -> screen = Screen.Episodes(s.item, season) },
                onPlayEpisode = { ep -> screen = Screen.Player(s.item, ep) },
            )

            is Screen.Watch -> SmartPlayScreen(
                item = s.item,
                onBack = { screen = Screen.Details(s.item) },
                onPlayTorrent = { screen = Screen.Player(s.item) },
                // Сериал: показываем состав раздачи — пользователь выбирает серию
                onPickFiles = { r ->
                    screen = Screen.TorrentFiles(s.item, com.torrenttv.app.data.TorrentSearch.toMagnet(r))
                },
            )

            is Screen.Trailer -> TrailerScreen(
                item = s.item,
                onBack = { screen = Screen.Details(s.item) },
            )

            is Screen.RutubeWatch -> com.torrenttv.app.ui.RutubeWatchScreen(
                videoId = s.videoId,
                title = s.title,
                onBack = { screen = Screen.Search },
            )

            is Screen.Episodes -> EpisodesScreen(
                item = s.item,
                initialSeason = s.season.takeIf { it > 0 },
                onPlay = { ep -> screen = Screen.Player(s.item, ep) },
                onFind = { ep -> screen = Screen.TorrentSearch(s.item, ep) },
                onBack = { screen = Screen.Details(s.item) },
            )

            is Screen.MagnetEntry -> MagnetEntryScreen(
                item = s.item,
                onDone = { screen = Screen.Player(s.item) },
                onBack = { screen = Screen.Details(s.item) },
            )

            is Screen.TorrentFiles -> TorrentFilesScreen(
                item = s.item,
                magnet = s.magnet,
                // Выбран файл (серия) — сохраняем magnet и открываем плеер
                onPick = { idx ->
                    appStore.saveMagnet(s.item, s.magnet)
                    screen = Screen.Player(s.item, fileIndex = idx)
                },
                onBack = { screen = Screen.Search },
            )

            is Screen.TorrentResults -> TorrentResultsScreen(
                item = s.item,
                query = s.query,
                results = s.results,
                poster = s.poster,
                onPickTorrent = { r ->
                    screen = Screen.TorrentFiles(s.item, com.torrenttv.app.data.TorrentSearch.toMagnet(r))
                },
                onBack = { screen = Screen.Search },
            )

            is Screen.TorrentSearch -> TorrentSearchScreen(
                item = s.item,
                episode = s.episode,
                autoStart = s.autoStart,
                onDone = {
                    screen = if (s.episode != null) Screen.Player(s.item, s.episode)
                    else Screen.Player(s.item)
                },
                onBack = {
                    screen = if (s.episode != null) Screen.Episodes(s.item)
                    else Screen.Details(s.item)
                },
            )

            is Screen.Player -> PlayerScreen(
                item = s.item,
                episode = s.episode,
                startIndex = s.fileIndex,
                onBack = {
                    screen = if (s.episode != null) Screen.Episodes(s.item)
                    else Screen.Details(s.item)
                },
                // Автоплей: серия закончилась → сразу следующая
                onPlayNext = { next -> screen = Screen.Player(s.item, next) },
                // ⚙ «Другое качество» → экран выбора раздач/ссылок
                onPickTorrent = { screen = Screen.Watch(s.item) },
            )

            else -> Unit
        }
    }
}
