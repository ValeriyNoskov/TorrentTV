package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
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
fun CatalogScreen(mode: String, onOpenDetails: (MediaItem) -> Unit, onSearch: () -> Unit = {}) {
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
    // ⚙ Фильтры прямо в каталоге (как у Кинопоиска): скрытые жанры + страны
    var showFilters by remember { mutableStateOf(false) }
    var movieGenresAll by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var tvGenresAll by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var countriesAll by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var excludedG by remember { mutableStateOf(store.excludedGenreIds().toSet()) }
    var excludedC by remember { mutableStateOf(store.excludedCountryIds().toSet()) }
    LaunchedEffect(showFilters) {
        if (showFilters && movieGenresAll.isEmpty()) {
            withContext(Dispatchers.IO) {
                movieGenresAll = runCatching { MediaApi.movieGenres() }.getOrDefault(emptyList())
                tvGenresAll = runCatching { MediaApi.tvGenres() }.getOrDefault(emptyList())
                countriesAll = runCatching { MediaApi.countries() }.getOrDefault(emptyList())
            }
        }
    }
    if (showFilters) {
        FilterPicker(
            title = "Скрыть из каталогов",
            groups = listOf("Жанры фильмов:" to movieGenresAll, "Жанры сериалов:" to tvGenresAll, "Страны:" to countriesAll),
            excluded = excludedG + excludedC,
            onToggle = { g ->
                if (g.id in excludedG || g.id in excludedC) {
                    // снять: id может быть и жанром, и страной — чистим оба набора
                    excludedG = excludedG - g.id
                    excludedC = excludedC - g.id
                } else {
                    // жанры и страны могут пересекаться по id у КП — относим к жанрам,
                    // если id есть в их списках, иначе к странам
                    if (movieGenresAll.any { it.id == g.id } || tvGenresAll.any { it.id == g.id }) {
                        excludedG = excludedG + g.id
                        store.setExcludedGenreIds(excludedG)
                        return@FilterPicker
                    }
                    excludedC = excludedC + g.id
                }
                store.setExcludedGenreIds(excludedG)
                store.setExcludedCountryIds(excludedC)
            },
            onClearAll = {
                excludedG = emptySet()
                excludedC = emptySet()
                store.setExcludedGenreIds(excludedG)
                store.setExcludedCountryIds(excludedC)
            },
            onClose = { showFilters = false },
        )
        return
    }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 🔍 Поиск — на каждой вкладке (просьба владельца)
                androidx.tv.material3.Surface(
                    onClick = onSearch,
                    shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1F2430),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFF5B942),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("🔍", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
                Spacer(Modifier.width(8.dp))
                // ⚙ Фильтры — с счётчиком скрытого (как у Кинопоиска)
                val hiddenN = excludedG.size + excludedC.size
                androidx.tv.material3.Surface(
                    onClick = { showFilters = true },
                    shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1F2430),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFF5B942),
                        focusedContentColor = Color.Black,
                    ),
                ) {
                    Text(
                        if (hiddenN > 0) "⚙ Фильтры · $hiddenN" else "⚙ Фильтры",
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
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
