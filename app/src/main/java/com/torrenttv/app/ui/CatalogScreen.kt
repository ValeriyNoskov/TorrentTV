package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.torrenttv.app.data.Genre
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.MediaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Каталог с табом: Фильмы (mode="movies"), Сериалы ("tv") или Шоу ("shows").
 * Полоса жанров + полки «Популярные» и «Топ» с бесконечной подгрузкой.
 */
@Composable
fun CatalogScreen(mode: String, onOpenDetails: (MediaItem) -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    val noun = when (mode) { "movies" -> "фильмы"; "shows" -> "шоу"; else -> "сериалы" }
    val title = when (mode) { "movies" -> "Фильмы"; "shows" -> "Шоу"; else -> "Сериалы" }

    // Сортировка: «полки» (по умолчанию) / поступившие / по году / по рейтингу.
    // Выбор запоминается отдельно для каждой вкладки.
    val sortOptions = listOf(
        "shelves" to "📚 Полки",
        "posted" to "📥 Поступившие",
        "year" to "🆕 Новинки",
        "rating" to "⭐ По рейтингу",
    )
    var sort by remember { mutableStateOf(store.lastCatalogSort(mode)) }
    var genres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    // Жанр запоминается: выход из карточки не сбрасывает выбор (как в «Торрентах»)
    var selected by remember { mutableStateOf<Genre?>(null) }

    LaunchedEffect(mode) {
        genres = withContext(Dispatchers.IO) {
            when (mode) {
                "movies" -> MediaApi.movieGenres()
                "shows" -> emptyList()
                else -> MediaApi.tvGenres()
            }
        }
        // Восстановить последний выбранный жанр этой вкладки
        val savedId = store.lastCatalogGenre(mode)
        if (selected == null && savedId != 0) selected = genres.firstOrNull { it.id == savedId }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        item {
            Text(
                text = title,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
            )
        }
        if (!MediaApi.isConfigured) {
            item {
                Text(
                    text = "⚠ Метаданные не настроены. Получите бесплатный ключ на kinopoiskapiunofficial.tech и укажите kpApiKey= в gradle.properties.",
                    color = Color(0xFFFFD54F),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
        item {
            SortChips(sortOptions, sort) {
                sort = it
                store.saveCatalogSort(mode, it)
            }
        }
        // «Поступившие» — лента трекера, жанровых фильтров у неё нет
        if (sort != "posted") {
            item {
                GenreChips(genres, selected) {
                    selected = it
                    store.saveCatalogGenre(mode, it?.id ?: 0)
                }
            }
        }
        if (sort != "shelves") {
            item {
                val sortTitle = sortOptions.firstOrNull { it.first == sort }?.second.orEmpty()
                PagedMediaRow(
                    title = "$title · $sortTitle",
                    loader = { page ->
                        when {
                            sort == "posted" -> MediaApi.postedItems(
                                page,
                                if (mode == "movies") "movie" else "tv",
                            )
                            else -> MediaApi.orderedMoviesTv(
                                page,
                                if (sort == "year") "YEAR" else "RATING",
                                shows = mode == "shows",
                                movies = mode == "movies",
                                genreId = selected?.id,
                            )
                        }
                    },
                    onOpen = onOpenDetails,
                    keyTag = "${sort}-${selected?.id ?: 0}",
                    filterFn = { store.isAllowed(it) },
                )
            }
        }
        if (sort == "shelves") {
            selected?.let { g ->
                item {
                    PagedMediaRow(
                        title = "$title · ${g.name}",
                        loader = { page ->
                            if (mode == "movies") MediaApi.moviesByGenre(g.id, page)
                            else MediaApi.tvByGenre(g.id, page)
                        },
                        onOpen = onOpenDetails,
                        keyTag = g.id,
                        filterFn = { store.isAllowed(it) },
                    )
                }
            }
            item {
                // Новинки — первыми: свежие релизы, на них сразу есть раздачи
                PagedMediaRow(
                    title = "🆕 Новинки",
                    loader = { page ->
                        if (mode == "movies") MediaApi.newMovies(page) else MediaApi.newTv(page)
                    },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) },
                )
            }
            item {
                PagedMediaRow(
                    title = "🔥 Популярные $noun",
                    loader = { page ->
                        when (mode) {
                            "movies" -> MediaApi.popularMovies(page)
                            "shows" -> MediaApi.popularShows(page)
                            else -> MediaApi.popularTv(page)
                        }
                    },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) },
                )
            }
            item {
                PagedMediaRow(
                    title = "🇷🇺 Русские $noun",
                    loader = { page ->
                        if (mode == "movies") MediaApi.russianMovies(page) else MediaApi.russianTv(page)
                    },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) },
                )
            }
            item {
                PagedMediaRow(
                    title = "🌍 Зарубежные $noun",
                    loader = { page ->
                        if (mode == "movies") MediaApi.foreignMovies(page) else MediaApi.foreignTv(page)
                    },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
