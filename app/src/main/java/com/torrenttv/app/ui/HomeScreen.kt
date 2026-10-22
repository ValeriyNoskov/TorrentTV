package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.BuildConfig
import com.torrenttv.app.data.Genre
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.TmdbClient
import com.torrenttv.app.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Домашний экран: поиск, «Продолжить», «Избранное» и каталоги с жанрами и подгрузкой. */
@Composable
fun HomeScreen(
    currentVersion: String,
    onOpenSearch: () -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var favorites by remember { mutableStateOf(store.favorites()) }
    var progress by remember { mutableStateOf(store.continueWatching()) }
    var movieGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var tvGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var selectedMovieGenre by remember { mutableStateOf<Genre?>(null) }
    var selectedTvGenre by remember { mutableStateOf<Genre?>(null) }
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        UpdateManager.checkForUpdate(currentVersion) { updateInfo = it }
        val (mg, tg) = withContext(Dispatchers.IO) {
            TmdbClient.movieGenres() to TmdbClient.tvGenres()
        }
        movieGenres = mg
        tvGenres = tg
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        Column {
            // ---------- Верхняя панель ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Поиск")
                    }
                    Spacer(Modifier.width(24.dp))
                    Text(
                        text = "TorrentTV",
                        color = Color(0xFF4FC3F7),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "v$currentVersion",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp,
                )
            }

            if (!TmdbClient.isConfigured) {
                Text(
                    text = "⚠ Ключ TMDB не задан: каталоги пусты. Добавьте tmdbApiKey в gradle.properties.",
                    color = Color(0xFFFFD54F),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }

            // ---------- Каталоги ----------
            LazyColumn(Modifier.fillMaxSize()) {
                if (progress.isNotEmpty()) {
                    item {
                        MediaRow(
                            title = "Продолжить просмотр",
                            items = progress.map { it.item },
                            onClick = onOpenDetails,
                        )
                    }
                }
                item {
                    MediaRow(
                        title = "Избранное",
                        items = favorites,
                        onClick = onOpenDetails,
                        emptyText = "Пока пусто — добавьте фильмы из карточки (☆)",
                    )
                }
                item {
                    PagedMediaRow(
                        title = "🔥 Сейчас в тренде",
                        loader = { page -> TmdbClient.trending(page) },
                        onOpen = onOpenDetails,
                    )
                }
                item {
                    Text(
                        text = "Фильмы по жанрам",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 17.sp,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                    )
                    GenreChips(movieGenres, selectedMovieGenre) { selectedMovieGenre = it }
                }
                selectedMovieGenre?.let { g ->
                    item {
                        PagedMediaRow(
                            title = "Фильмы · ${g.name}",
                            loader = { page -> TmdbClient.moviesByGenre(g.id, page) },
                            onOpen = onOpenDetails,
                            keyTag = g.id,
                        )
                    }
                }
                item {
                    PagedMediaRow(
                        title = "Популярные фильмы",
                        loader = { page -> TmdbClient.popularMovies(page) },
                        onOpen = onOpenDetails,
                    )
                }
                item {
                    PagedMediaRow(
                        title = "Топ фильмов",
                        loader = { page -> TmdbClient.topRatedMovies(page) },
                        onOpen = onOpenDetails,
                    )
                }
                item {
                    Text(
                        text = "Сериалы по жанрам",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 17.sp,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                    )
                    GenreChips(tvGenres, selectedTvGenre) { selectedTvGenre = it }
                }
                selectedTvGenre?.let { g ->
                    item {
                        PagedMediaRow(
                            title = "Сериалы · ${g.name}",
                            loader = { page -> TmdbClient.tvByGenre(g.id, page) },
                            onOpen = onOpenDetails,
                            keyTag = g.id,
                        )
                    }
                }
                item {
                    PagedMediaRow(
                        title = "Популярные сериалы",
                        loader = { page -> TmdbClient.popularTv(page) },
                        onOpen = onOpenDetails,
                    )
                }
                item {
                    PagedMediaRow(
                        title = "Топ сериалов",
                        loader = { page -> TmdbClient.topRatedTv(page) },
                        onOpen = onOpenDetails,
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        // ---------- Оверлей обновления ----------
        updateInfo?.let { info ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                color = Color(0xFF1B2A38),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp, 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Доступно обновление v${info.version}",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (updateStatus.isNotBlank()) {
                            Text(updateStatus, color = Color(0xFFFFD54F), fontSize = 13.sp)
                        }
                    }
                    Button(onClick = {
                        UpdateManager.downloadAndInstall(
                            context,
                            info,
                            onProgress = { updateStatus = it },
                            onError = { updateStatus = it },
                        )
                    }) {
                        Text("Обновить")
                    }
                }
            }
        }
    }
}
