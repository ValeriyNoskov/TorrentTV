package com.torrenttv.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.torrenttv.app.data.AppLog
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.RutrackerrCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Каталог «Торренты»: только то, что РЕАЛЬНО есть в трекере (rutrackerr.ru),
 * без рейтингов КП. Фильтры: тип (фильмы/сериалы/шоу), жанр, год, страна.
 * Жанр и год фильтрует сам сайт (страница категории / xfsearch по году),
 * тип и страна — клиентски по загруженным карточкам.
 *
 * Качество в карточках каталога трекер не отдаёт (оно появляется в самой
 * раздаче) — фильтр качества живёт в списке раздач выбранного тайтла.
 */
@Composable
fun TorrentsScreen(onOpenDetails: (MediaItem) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { com.torrenttv.app.data.LocalStore(context) }
    // ---------- Фильтры ----------
    // Сохраняются в настройках: выход из раздела (в карточку/на главную) и
    // даже перезапуск приложения — фильтры остаются (жалоба: «фильтр не
    // сохраняется, выкидывает на главную»)
    val saved = remember { store.loadTorrentsFilter() }
    var type by remember { mutableStateOf(saved[0] ?: "all") }          // all | movies | tv | shows
    var genre by remember { mutableStateOf(saved[1]) }                  // slug или null
    var year by remember { mutableStateOf(saved[2]) }                   // «2024» или null
    var country by remember { mutableStateOf(saved[3]) }                // клиентский фильтр
    // Трассировка «чёрного экрана»: композиция экрана дошла до сюда?
    androidx.compose.runtime.SideEffect {
        AppLog.log("torrents", "композиция «Торрентов»: тип=$type жанр=$genre год=$year страна=$country")
    }
    LaunchedEffect(type, genre, year, country) {
        store.saveTorrentsFilter(type, genre, year, country)
    }

    // ---------- Данные ----------
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    // Поиск по трекеру прямо в разделе: заменяет источник (страницу категории)
    var searchMode by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }
    var showKb by remember { mutableStateOf(false) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Внешняя клавиатура (Настройки → Ввод): раньше этот экран её игнорировал
    // и всегда показывал экранную — теперь как в «Поиске»
    val externalKb = remember { store.useExternalKeyboard() }
    val kbFieldFocus = remember { FocusRequester() }
    var voiceStatus by remember { mutableStateOf("") }

    // Голосовой ввод: тот же системный распознаватель, что в «Поиске»
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val text = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                searchQ = text.trim()
                voiceStatus = ""
            }
        }
    }

    fun startVoice() {
        voiceStatus = ""
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите название для поиска в трекере")
            }
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            voiceStatus = "Голосовой ввод недоступен на этом устройстве"
        }
    }

    val gridState = rememberLazyGridState()

    // Какие годы реально есть в каталоге (xfsearch сайта)
    val yearChoices = remember {
        (2026 downTo 2020).map { it.toString() } + listOf("2015", "2010-е", "2000-е")
    }

    // Серверный источник: поиск > жанр > год > страница по типу
    fun sourceUrl(p: Int): String? {
        if (searchMode) return null // поиск идёт отдельной веткой
        val base = RutrackerrCatalog.baseUrl
        return when {
            genre != null -> "$base/$genre/" + if (p > 1) "page/$p/" else ""
            year != null && !year!!.contains('-') -> "$base/xfsearch/year/$year/" + if (p > 1) "page/$p/" else ""
            type == "tv" -> "$base/serialy/" + if (p > 1) "page/$p/" else ""
            else -> "$base/popular/" + if (p > 1) "page/$p/" else ""
        }
    }

    suspend fun loadPage(p: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        if (searchMode) {
            // Поиск по трекеру: групповые карточки из AJAX-выдачи, без догрузки
            val found = if (p > 1) emptyList() else RutrackerrCatalog.search(searchQ)
            return@withContext found.filter { it ->
                val typeOk = when (type) {
                    "movies" -> it.type == "movie"
                    "tv" -> it.type == "tv"
                    "shows" -> SHOW_WORDS.containsMatchIn(it.title)
                    else -> true
                }
                val countryOk = country == null ||
                    it.country.split(',').any { c -> c.trim().equals(country!!, ignoreCase = true) }
                typeOk && countryOk
            }
        }
        val url = sourceUrl(p) ?: return@withContext emptyList()
        val fetched = RutrackerrCatalog.categoryByUrl(url)
        // Клиентские фильтры поверх серверных: тип, год-диапазон, страна
        fetched.filter { it ->
            val typeOk = when (type) {
                "movies" -> it.type == "movie"
                "tv" -> it.type == "tv"
                "shows" -> SHOW_WORDS.containsMatchIn(it.title)
                else -> true
            }
            val yearOk = when {
                year == null -> true
                year!!.contains('-') -> {
                    // Декада: «2010-е» — 2010..2019
                    val (from, to) = year!!.removeSuffix("-е").split('-').map { it.toInt() }
                    (it.year.toIntOrNull() ?: 0) in from..to
                }
                else -> it.year == year
            }
            val countryOk = country == null ||
                it.country.split(',').any { c -> c.trim().equals(country!!, ignoreCase = true) }
            typeOk && yearOk && countryOk
        }
    }

    // Перезагрузка при смене фильтров
    var loadError by remember { mutableStateOf("") }
    // Офлайн-режим: показываем кеш, время последнего успешного обновления
    var offlineSince by remember { mutableStateOf(0L) }
    LaunchedEffect(type, genre, year, country, reloadTick) {
        loading = true
        loadError = ""
        page = 1
        endReached = false
        // Трассировка: эффект запущен (если этой строки в логе нет при
        // наличии «композиции» — эффект не стартует, аномалия Recomposer)
        AppLog.log("torrents", "эффект загрузки запущен (reloadTick=$reloadTick)")
        AppLog.log("torrents", "загрузка каталога: тип=$type жанр=$genre год=$year")
        try {
            items = loadPage(1).distinctBy { it.storeKey }
            if (items.isNotEmpty() && !searchMode) store.saveTorrentsCache(items)
        } catch (e: Exception) {
            // Молчаливое падение корутины = «чёрный экран» без объяснений —
            // фиксируем в лог и показываем пользователю
            items = emptyList()
            loadError = "${e.javaClass.simpleName}: ${e.message ?: "ошибка загрузки"}"
            AppLog.log("torrents", "ОШИБКА загрузки каталога: $loadError")
        }
        // Офлайн-каталог (ROADMAP №12): сайт не ответил — показываем последний
        // сохранённый список, чтобы раздел не был пустой дырой
        if (items.isEmpty() && loadError.isNotBlank()) {
            val (cached, ts) = store.loadTorrentsCache()
            if (cached.isNotEmpty()) {
                items = cached
                offlineSince = ts
                loadError = ""
                AppLog.log("torrents", "офлайн: показан кеш ${cached.size} карточек")
            }
        }
        loading = false
        AppLog.log("torrents", "каталог: тип=$type жанр=$genre год=$year страна=$country поиск=$searchMode → ${items.size}")
    }

    // Бесконечная подгрузка: приблизились к концу списка — грузим следующую страницу
    val shouldLoadMore by derivedStateOf {
        val info = gridState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        !loading && !endReached && info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
    }
    LaunchedEffect(shouldLoadMore) {
        if (!shouldLoadMore || items.isEmpty()) return@LaunchedEffect // пустой список — не долбим страницы
        loading = true
        val next = page + 1
        val more = try {
            loadPage(next)
        } catch (e: Exception) {
            AppLog.log("torrents", "ОШИБКА догрузки стр.$next: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        if (more.isEmpty()) endReached = true else {
            page = next
            // Дедуп по ключу карточки (страницы могут повторять)
            items = (items + more).distinctBy { it.storeKey }
        }
        loading = false
    }

    // Список стран — из загруженных карточек (динамический)
    val countryChoices = remember(items) {
        items.flatMap { it.country.split(',') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    // Итоговый список: декадный/серверный год уже отфильтрован, страна тоже
    val shown = items

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        Text(
            text = "🧲 Торренты — что реально есть в трекере",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )

        // ---------- Поиск по трекеру ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 2.dp),
        ) {
            androidx.tv.material3.Surface(
                onClick = { showKb = !showKb },
                shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = if (showKb) Color(0xFF3A2E14) else Color(0xFF1F2430),
                    contentColor = if (showKb) Color(0xFFF5B942) else Color.White,
                    focusedContainerColor = Color(0xFFF5B942),
                    focusedContentColor = Color(0xFF0E1116),
                ),
            ) { Text("🔍 Найти в трекере", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
            Spacer(Modifier.width(8.dp))
            androidx.tv.material3.Surface(
                onClick = { showKb = true; startVoice() },
                shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF1F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFF5B942),
                    focusedContentColor = Color(0xFF0E1116),
                ),
            ) { Text("🎤 Голос", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
            if (searchMode) {
                Spacer(Modifier.width(8.dp))
                androidx.tv.material3.Surface(
                    onClick = { searchMode = false; searchQ = ""; reloadTick++ },
                    shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1F2430),
                        contentColor = Color(0xFFF5B942),
                        focusedContainerColor = Color(0xFFF5B942),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) { Text("✕ «$searchQ» — сбросить поиск", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
            }
        }
        if (showKb) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            ) {
                TvKeyboard(
                    onChar = { searchQ += it },
                    onBackspace = { if (searchQ.isNotEmpty()) searchQ = searchQ.dropLast(1) },
                    onClear = { searchQ = "" },
                    firstKeyFocus = searchFocus,
                )
                Spacer(Modifier.width(12.dp))
                androidx.tv.material3.Surface(
                    onClick = {
                        if (searchQ.isNotBlank()) {
                            showKb = false
                            searchMode = true
                            reloadTick++
                        }
                    },
                    modifier = Modifier.height(52.dp),
                    shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF2E4A22),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFF7FB84F),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) { Text("Найти ▶", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ---- Фильтры ----
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterRow(label = "Тип") {
                    FilterChip("Все", type == "all") { type = "all" }
                    FilterChip("Фильмы", type == "movies") { type = "movies" }
                    FilterChip("Сериалы", type == "tv") { type = "tv" }
                    FilterChip("Шоу", type == "shows") { type = "shows" }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterRow(label = "Жанр") {
                    FilterChip("Любой", genre == null) { genre = null }
                    for ((slug, name) in RutrackerrCatalog.genreList()) {
                        FilterChip(name, genre == slug) { genre = slug }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterRow(label = "Год") {
                    FilterChip("Любой", year == null) { year = null }
                    for (y in yearChoices) {
                        FilterChip(y, year == y) { year = y }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterRow(label = "Страна") {
                    FilterChip("Любая", country == null) { country = null }
                    for (c in countryChoices) {
                        FilterChip(c, country == c) { country = c }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = when {
                        offlineSince > 0 ->
                            "📡 Офлайн: сайт не ответил — показан сохранённый список от " +
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(offlineSince)) + " · «↻ Повторить» ниже"
                        loadError.isNotEmpty() -> "⚠ Не удалось загрузить каталог: $loadError"
                        loading && items.isEmpty() -> "Загружаю раздачи…"
                        shown.isEmpty() -> "По этим фильтрам ничего нет — ослабьте фильтры"
                        else -> "Найдено: ${shown.size}" + if (endReached) "" else " (листаю дальше — подгрузится ещё)"
                    },
                    color = if (loadError.isNotEmpty()) Color(0xFFF5B942) else Color.White.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // Ошибка загрузки — кнопка повтора (раньше был молчаливый «чёрный экран»)
            if (loadError.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    androidx.tv.material3.Surface(
                        onClick = { reloadTick++ },
                        shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFF5B942),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                    ) { Text("↻ Повторить", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                }
            }
            // ---- Карточки ----
            items(shown, key = { it.storeKey }) { item ->
                PosterCard(item) { onOpenDetails(item) }
            }
            if (loading && items.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                        Text("Подгружаю ещё…", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** Ключевые слова шоу (реалити/ток) — как в popularShows Рутрекера. */
private val SHOW_WORDS = Regex(
    "(?i)шоу|реалити|ток-|выживани|голос|битва|маска|фабрик|звёзд|звезд|проект",
)

@Composable
private fun FilterRow(label: String, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFFF5B942),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(64.dp).padding(start = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            // Чипы фильтра — ОДНИМ рядом внутри item: раньше content() эмитил
            // несколько Surface в один слот LazyRow, и они рисовались друг на
            // друге (виден был только последний чип). Ряд кладёт их подряд,
            // прокрутка остаётся — ширина item больше экрана
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFF3A2E14) else Color(0xFF1F2430),
            contentColor = if (selected) Color(0xFFF5B942) else Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color(0xFFF5B942),
            focusedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Text(
            text = if (selected) "✓ $label" else label,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
