package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
        "rating" to "★︎ По рейтингу",
    )
    var sort by remember { mutableStateOf(store.lastCatalogSort(mode)) }
    // Полоса чипов жанров убрана (v0.9.30): дублировала модалку «🎭 Жанр»,
    // причём с другим хранилищем — до карточек стало на одну строку меньше
    var movieGenresAll by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var tvGenresAll by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var countriesAll by remember { mutableStateOf<List<Genre>>(emptyList()) }
    // Фильтры «как в Зоне» (включение): жанры, тип, страны, годы, рейтинг.
    // Живут в LocalStore — переживают выход из каталога и перезапуск.
    var fGenres by remember { mutableStateOf(store.filterGenreIds()) }
    var fType by remember { mutableStateOf(store.filterType()) }
    var fCountries by remember { mutableStateOf(store.filterCountryIds()) }
    var fYears by remember { mutableStateOf(store.filterYearIds()) }
    var fYearPreset by remember { mutableStateOf(store.filterYearPreset()) }
    var fMinRating by remember { mutableStateOf(store.filterMinRating()) }
    // Какая модалка фильтра открыта: "genre" | "type" | "country" | "year" | "rating" | null
    var openFilter by remember { mutableStateOf<String?>(null) }
    // Справочники жанров/стран — для списков и имён бейджей
    LaunchedEffect(Unit) {
        if (movieGenresAll.isEmpty()) {
            withContext(Dispatchers.IO) {
                movieGenresAll = runCatching { MediaApi.movieGenres() }.getOrDefault(emptyList())
                tvGenresAll = runCatching { MediaApi.tvGenres() }.getOrDefault(emptyList())
                countriesAll = runCatching { MediaApi.countries() }.getOrDefault(emptyList())
            }
        }
    }

    fun nameById(lists: List<List<Genre>>, id: Int): String =
        lists.firstOrNull { l -> l.any { it.id == id } }?.firstOrNull { it.id == id }?.name ?: "#$id"

    fun yearLabel(): String {
        val preset = when (fYearPreset) {
            "2020+" -> "2020+"; "2010-2019" -> "2010–2019"
            "2000-2009" -> "2000–2009"; "1990s" -> "90-е"
            else -> ""
        }
        val ys = fYears.sortedDescending()
        val yearsTxt = if (ys.size > 3) "${ys.take(3).joinToString(", ")}…" else ys.joinToString(", ")
        return listOf(preset, yearsTxt).filter { it.isNotBlank() }.joinToString(", ")
    }


    Box {
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
                    color = Color(0xFFBFE3F7),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
        item {
            // Кнопки фильтров «как в Зоне»: Жанр · Тип · Страна · Год · Рейтинг.
            // Каждая открывает свою модалку со списком с галочками.
            val activeN = listOf(fGenres.size, fCountries.size, fYears.size + if (fYearPreset.isNotBlank()) 1 else 0, if (fType.isBlank()) 0 else 1, if (fMinRating > 0) 1 else 0).sum()
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 🔍 Поиск — на каждой вкладке (просьба владельца)
                androidx.tv.material3.Surface(
                    onClick = onSearch,
                    shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1F2430),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFA9D7F5),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("🔍", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
                Spacer(Modifier.width(8.dp))
                listOf(
                    "genre" to listOf("🎭 Жанр", fGenres.size),
                    "type" to listOf("🎬 Тип", if (fType.isBlank()) 0 else 1),
                    "country" to listOf("🌍 Страна", fCountries.size),
                    "year" to listOf("📅 Год", fYears.size + if (fYearPreset.isNotBlank()) 1 else 0),
                    "rating" to listOf("⭐ Рейтинг", if (fMinRating > 0) 1 else 0),
                ).forEachIndexed { idx, (key, pair) ->
                    val label = pair[0] as String
                    val n = pair[1] as Int
                    if (idx > 0) Spacer(Modifier.width(6.dp))
                    androidx.tv.material3.Surface(
                        onClick = { openFilter = key },
                        shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFA9D7F5),
                            focusedContentColor = Color.Black,
                        ),
                        modifier = Modifier.mouseClick { openFilter = key },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(label, fontSize = 15.sp)
                            if (n > 0) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    n.toString(),
                                    color = Color(0xFF0E1116),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(Color(0xFFA9D7F5), RoundedCornerShape(7.dp))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
                if (activeN > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "включено фильтров: $activeN",
                        color = Color(0xFF8A94A6),
                        fontSize = 13.sp,
                    )
                }
            }
            // Строка включённых фильтров: бейджи с ✕ — клик снимает группу
            if (activeN > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    val badges = buildList {
                        if (fGenres.isNotEmpty()) {
                            add("жанр: " + fGenres.sorted().joinToString(", ") { nameById(listOf(movieGenresAll, tvGenresAll), it) })
                        }
                        if (fType.isNotBlank()) add("тип: " + if (fType == "movie") "Фильмы" else "Сериалы")
                        if (fCountries.isNotEmpty()) {
                            add("страна: " + fCountries.sorted().joinToString(", ") { nameById(listOf(countriesAll), it) })
                        }
                        val yl = yearLabel()
                        if (yl.isNotBlank()) add("год: $yl")
                        if (fMinRating > 0) add("рейтинг: от $fMinRating")
                    }
                    badges.forEach { b ->
                        androidx.tv.material3.Surface(
                            onClick = { openFilter = b.substringBefore(':') },
                            shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF1E3A4D),
                                contentColor = Color(0xFFA9D7F5),
                                focusedContainerColor = Color(0xFFA9D7F5),
                                focusedContentColor = Color(0xFF0E1116),
                            ),
                            modifier = Modifier.mouseClick { openFilter = b.substringBefore(':') },
                        ) {
                            Text(
                                "$b  ✕",
                                fontSize = 12.5.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    androidx.tv.material3.Surface(
                        onClick = {
                            store.clearAllFilters()
                            fGenres = emptySet(); fType = ""; fCountries = emptySet()
                            fYears = emptySet(); fYearPreset = ""; fMinRating = 0
                        },
                        shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color(0xFF8A94A6),
                            focusedContainerColor = Color(0xFFA9D7F5),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                        modifier = Modifier.mouseClick {
                            store.clearAllFilters()
                            fGenres = emptySet(); fType = ""; fCountries = emptySet()
                            fYears = emptySet(); fYearPreset = ""; fMinRating = 0
                        },
                    ) {
                        Text(
                            "очистить всё",
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            SortChips(sortOptions, sort) {
                sort = it
                store.saveCatalogSort(mode, it)
            }
        }
        // Полоса чипов жанров убрана (v0.9.30): жанр — через модалку «🎭 Жанр»
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
                            )
                        }
                    },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) && store.passesFilters(it) },
                )
            }
        }
        if (sort == "shelves") {
            item {
                // Новинки — первыми: свежие релизы, на них сразу есть раздачи
                PagedMediaRow(
                    title = "🆕 Новинки",
                    loader = { page ->
                        if (mode == "movies") MediaApi.newMovies(page) else MediaApi.newTv(page)
                    },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) && store.passesFilters(it) },
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
                    filterFn = { store.isAllowed(it) && store.passesFilters(it) },
                )
            }
            item {
                PagedMediaRow(
                    title = "🇷🇺 Русские $noun",
                    loader = { page ->
                        if (mode == "movies") MediaApi.russianMovies(page) else MediaApi.russianTv(page)
                    },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) && store.passesFilters(it) },
                )
            }
            item {
                PagedMediaRow(
                    title = "🌍 Зарубежные $noun",
                    loader = { page ->
                        if (mode == "movies") MediaApi.foreignMovies(page) else MediaApi.foreignTv(page)
                    },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) && store.passesFilters(it) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    // Модалка фильтра рисуется ПОВЕРХ каталога, а не через ранний return:
    // return размонтировал полки — при закрытии они пересоздавались и
    // постраничная загрузка начиналась заново (вечная «Загрузка…»).
    openFilter?.let { which ->
        val opts: List<FilterOption> = when (which) {
            "genre" -> {
                val all = movieGenresAll + tvGenresAll
                all.distinctBy { it.id }
                    .sortedBy { it.name }
                    .map { FilterOption("g:${it.id}", it.name, it.id in fGenres) }
            }
            "type" -> listOf(
                FilterOption("t:", "Все", fType.isBlank()),
                FilterOption("t:movie", "Фильмы", fType == "movie"),
                FilterOption("t:tv", "Сериалы", fType == "tv"),
            )
            "country" -> countriesAll
                .sortedBy { it.name }
                .map { FilterOption("c:${it.id}", it.name, it.id in fCountries) }
            "year" -> {
                val presets = listOf(
                    "2020+" to "2020+", "2010-2019" to "2010–2019",
                    "2000-2009" to "2000–2009", "1990s" to "90-е",
                )
                presets.map { (id, label) -> FilterOption("p:$id", label, fYearPreset == id) } +
                    (2026 downTo 1990).map { y ->
                        FilterOption("y:$y", y.toString(), y in fYears)
                    }
            }
            else -> listOf(
                FilterOption("r:0", "Любой", fMinRating == 0),
                FilterOption("r:7", "от 7", fMinRating == 7),
                FilterOption("r:8", "от 8", fMinRating == 8),
            )
        }
        FilterListDialog(
            title = when (which) {
                "genre" -> "Жанр"; "type" -> "Тип"; "country" -> "Страна"
                "year" -> "Год"; else -> "Рейтинг"
            },
            options = opts,
            onToggle = { opt ->
                val id = opt.id.substringAfter(':')
                when (which) {
                    "genre" -> {
                        val gid = id.toIntOrNull() ?: return@FilterListDialog
                        fGenres = if (gid in fGenres) fGenres - gid else fGenres + gid
                        store.setFilterGenreIds(fGenres)
                    }
                    "type" -> {
                        fType = if (fType == id) "" else id
                        store.setFilterType(fType)
                    }
                    "country" -> {
                        val cid = id.toIntOrNull() ?: return@FilterListDialog
                        fCountries = if (cid in fCountries) fCountries - cid else fCountries + cid
                        store.setFilterCountryIds(fCountries)
                    }
                    "year" -> {
                        if (opt.id.startsWith("p:")) {
                            fYearPreset = if (fYearPreset == id) "" else id
                            store.setFilterYearPreset(fYearPreset)
                        } else {
                            val y = id.toIntOrNull() ?: return@FilterListDialog
                            fYears = if (y in fYears) fYears - y else fYears + y
                            store.setFilterYearIds(fYears)
                        }
                    }
                    else -> {
                        val r = id.toIntOrNull() ?: return@FilterListDialog
                        fMinRating = if (fMinRating == r) 0 else r
                        store.setFilterMinRating(fMinRating)
                    }
                }
            },
            onReset = {
                when (which) {
                    "genre" -> { fGenres = emptySet(); store.setFilterGenreIds(fGenres) }
                    "type" -> { fType = ""; store.setFilterType("") }
                    "country" -> { fCountries = emptySet(); store.setFilterCountryIds(fCountries) }
                    "year" -> { fYears = emptySet(); fYearPreset = ""; store.setFilterYearIds(fYears); store.setFilterYearPreset("") }
                    else -> { fMinRating = 0; store.setFilterMinRating(0) }
                }
            },
            onClose = { openFilter = null },
        )
    }
    }
}
