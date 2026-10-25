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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.torrenttv.app.data.AppLog
import com.torrenttv.app.data.JackettProvider
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.RutrackerrCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Каталог «Торренты»: только то, что РЕАЛЬНО есть в трекере (rutrackerr.ru),
 * плюс поиск через Jackett, если он настроен в Настройках.
 *
 * Фильтры (тип, жанры, страны, годы) — мультивыбор в оверлее по кнопке
 * «⚙ Фильтры»: раньше четыре ряда чипов занимали пол-экрана, и в сетке
 * помещалось меньше десятка карточек.
 */
@Composable
fun TorrentsScreen(onOpenDetails: (MediaItem) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalStore(context) }

    // ---------- Фильтры (мультивыбор, сохраняются между запусками) ----------
    val savedFilters = remember { store.loadTorrentsFilters() }
    var types by remember { mutableStateOf(savedFilters.types) }        // movie | tv | show
    var genres by remember { mutableStateOf(savedFilters.genres) }      // slug'и категорий
    var countries by remember { mutableStateOf(savedFilters.countries) }
    var years by remember { mutableStateOf(savedFilters.years) }        // «2024», «2010-е»
    var showFilters by remember { mutableStateOf(false) }


    androidx.compose.runtime.SideEffect {
        AppLog.log(
            "torrents",
            "композиция «Торрентов»: типы=$types жанры=$genres страны=$countries годы=$years",
        )
    }
    LaunchedEffect(types, genres, countries, years) {
        store.saveTorrentsFilters(LocalStore.TorrentsFilters(types, genres, countries, years))
    }

    // ---------- Данные ----------
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    // Сортировка: «Популярное» (по умолчанию) / «Поступившие» (дата появления
    // раздач на трекере). Выбор запоминается.
    var sort by remember { mutableStateOf(store.lastTorrentsSort()) }
    fun changeSort(v: String) {
        if (sort == v) return
        sort = v
        store.saveTorrentsSort(v)
        reloadTick++
    }
    var updatedAt by remember { mutableStateOf(0L) }
    var loadError by remember { mutableStateOf("") }
    var offlineSince by remember { mutableStateOf(0L) }

    // ---------- Поиск ----------
    var searchMode by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }
    var inputOpen by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("") }
    val kbFieldFocus = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }
    // Внешняя клавиатура (Настройки → Ввод): раньше экран её игнорировал
    // и всегда показывал экранную — теперь как в «Поиске»
    val externalKb = remember { store.useExternalKeyboard() }

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

    fun runSearch() {
        if (searchQ.isBlank()) return
        inputOpen = false
        searchMode = true
        reloadTick++
        runCatching { findFocus.requestFocus() }
    }

    val gridState = rememberLazyGridState()

    // ---------- Проверка карточки фильтрами ----------
    fun matches(it: MediaItem): Boolean {
        val typeOk = types.isEmpty() ||
            (it.type == "movie" && types.contains("movie")) ||
            (it.type == "tv" && types.contains("tv")) ||
            (types.contains("show") && SHOW_WORDS.containsMatchIn(it.title))
        val genreOk = genres.isEmpty() ||
            it.rtGenres.any { g -> genres.contains(g) } ||
            (it.type == "tv" && genres.contains("serialy"))
        val yearOk = years.isEmpty() || years.any { y ->
            if (y.contains('-')) {
                val (from, to) = y.removeSuffix("-е").removeSuffix("е").split('-')
                    .map { it.trim().toIntOrNull() ?: 0 }
                (it.year.toIntOrNull() ?: 0) in from..to
            } else it.year == y
        }
        val countryOk = countries.isEmpty() ||
            it.country.split(',').map { c -> c.trim() }
                .any { c -> countries.any { it.equals(c, ignoreCase = true) } }
        return typeOk && genreOk && yearOk && countryOk
    }

    // Серверный источник: один жанр > сериалы > популярное.
    // Годы и страны фильтруются клиентски по загруженным карточкам.
    fun sourceUrl(p: Int): String? {
        if (searchMode) return null
        val base = RutrackerrCatalog.baseUrl
        val suffix = if (p > 1) "page/$p/" else ""
        return when {
            genres.size == 1 -> "$base/${genres.first()}/$suffix"
            types == setOf("tv") -> "$base/serialy/$suffix"
            else -> "$base/popular/$suffix"
        }
    }

    /** Название раздачи → название тайтла (убираем серию, год, качество, релиз-группу). */
    fun cleanTitle(name: String): String {
        var s = name.substringBefore('[')
        s = s.replace(Regex("""\b(19|20)\d{2}\b"""), " ")
        s = s.replace(
            Regex(
                """(?i)\b(WEB-?DL|WEB-?Rip|WEB|BDRip|HDRip|HDRezka|Blu-?Ray|HDTV|SATRip|DVDRip|1080p|720p|2160p|4K|UHD|x264|x265|H\.?26[45]|HEVC|DUB|MEGapeer|ExKinoRay|Files-?x|LostFilm)\b\.?""",
            ),
            " ",
        )
        s = s.replace(Regex("""[._]+"""), " ")
        return s.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '|', ' ')
    }

    /** Поиск: Jackett (если настроен) + трекер. Объединяем и дедуплицируем. */
    suspend fun searchCards(q: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MediaItem>()
        val seen = mutableSetOf<String>()
        fun add(title: String, year: String, tv: Boolean, idPrefix: String) {
            val key = title.lowercase().replace('ё', 'е').trim()
            if (key.length < 2 || !seen.add(key)) return
            out += MediaItem(
                id = kotlin.math.abs((idPrefix + key).hashCode()),
                type = if (tv) "tv" else "movie",
                title = title,
                year = year,
            )
        }

        val cfg = store.jackettConfig()
        if (cfg?.isConfigured == true) {
            val jackett = runCatching { JackettProvider.search(cfg, q) }.getOrDefault(emptyList())
            AppLog.log("torrents", "поиск Jackett «$q» → ${jackett.size} раздач")
            jackett.sortedByDescending { it.seeds }.forEach { r ->
                add(
                    title = cleanTitle(r.name),
                    year = Regex("""(19|20)\d{2}""").find(r.name)?.value.orEmpty(),
                    tv = IS_TV_NAME.containsMatchIn(r.name),
                    idPrefix = "jk-",
                )
            }
        }
        val tracker = runCatching { RutrackerrCatalog.search(q) }.getOrDefault(emptyList())
        AppLog.log("torrents", "поиск по трекеру «$q» → ${tracker.size} карточек")
        tracker.forEach { add(it.title, it.year, it.type == "tv", "rt-") }
        out.take(60)
    }

    suspend fun loadPage(p: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        if (searchMode) {
            return@withContext if (p > 1) emptyList() else searchCards(searchQ).filter { matches(it) }
        }
        if (sort == "posted") {
            return@withContext RutrackerrCatalog.lastnews(p).filter { matches(it) }
        }
        val url = sourceUrl(p) ?: return@withContext emptyList()
        RutrackerrCatalog.categoryByUrl(url).filter { matches(it) }
    }

    LaunchedEffect(types, genres, countries, years, reloadTick, searchMode, sort) {
        loading = true
        loadError = ""
        page = 0
        endReached = false
        // Небольшая пауза: при переборе галочек в фильтрах не долбим сайт на каждый клик
        delay(300)
        AppLog.log("torrents", "эффект загрузки запущен (reloadTick=$reloadTick поиск=$searchMode)")
        val acc = mutableListOf<MediaItem>()
        try {
            // Сразу несколько страниц: одна страница трекера — всего 10 карточек,
            // после фильтров оставалось 7, и раздел выглядел «пустым».
            // В поиске страниц нет — там один запрос.
            val pages = if (searchMode) 1 else PAGES_AT_START
            for (p in 1..pages) {
                val part = loadPage(p)
                if (part.isEmpty()) {
                    endReached = true
                    break
                }
                acc += part
                page = p
            }
            items = acc.distinctBy { it.storeKey }.sortedWith(catalogOrder())
            if (items.isNotEmpty() && !searchMode) store.saveTorrentsCache(items)
            if (items.isNotEmpty()) updatedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            items = sortedOf(acc)
            loadError = "${e.javaClass.simpleName}: ${e.message ?: "ошибка загрузки"}"
            AppLog.log("torrents", "ОШИБКА загрузки каталога: $loadError")
        }
        // Офлайн-каталог: сайт не ответил — показываем последний сохранённый список
        if (items.isEmpty() && loadError.isNotBlank() && !searchMode) {
            val (cached, ts) = store.loadTorrentsCache()
            if (cached.isNotEmpty()) {
                items = cached
                offlineSince = ts
                loadError = ""
                AppLog.log("torrents", "офлайн: показан кеш ${cached.size} карточек")
            }
        }
        loading = false
        AppLog.log("torrents", "каталог → ${items.size} карточек (страниц ${page}, поиск=$searchMode)")
    }

    // Бесконечная подгрузка: следим за позицией сетки через snapshotFlow.
    // Раньше LaunchedEffect перезапускался на каждый флаг и отменял сам себя
    // (LeftCompositionCancellationException) — догрузка молча умирала.
    LaunchedEffect(gridState, reloadTick, searchMode) {
        androidx.compose.runtime.snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to info.totalItemsCount
        }.collect { (last, total) ->
            if (total == 0 || last < total - 6) return@collect
            if (loading || endReached || searchMode || items.isEmpty()) return@collect
            loading = true
            val next = page + 1
            val more = try {
                loadPage(next)
            } catch (e: Exception) {
                AppLog.log("torrents", "ОШИБКА догрузки стр.$next: ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
            if (more.isEmpty()) {
                endReached = true
            } else {
                page = next
                items = (items + more).distinctBy { it.storeKey }
            }
            loading = false
            android.util.Log.d("VNosKino", "torrents: подгрузка стр.$next → ${items.size} карточек")
        }
    }

    // Страны: основные — первыми, остальные по алфавиту (как в макете)
    val countryChoices = remember(items) {
        val all = items.flatMap { it.country.split(',') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val main = MAIN_COUNTRIES.filter { c -> all.any { it.equals(c, true) } }
        val rest = all.filter { c -> main.none { it.equals(c, true) } }.sorted()
        main + rest
    }

    val shown = items
    val filters = LocalStore.TorrentsFilters(types, genres, countries, years)

    Box(Modifier.fillMaxSize().background(Color(0xFF0E1116))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "🧲 Торренты",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = if (searchMode) "поиск: «$searchQ»" else "только то, что реально есть в трекере",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 14.sp,
                )
            }

            // ---------- Кнопки: фильтры, поиск, сброс ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 2.dp),
            ) {
                ActionPill(text = "📥 Поступившие", active = sort == "posted") { changeSort("posted") }
                Spacer(Modifier.width(6.dp))
                ActionPill(text = "🔥 Популярное", active = sort == "popular") { changeSort("popular") }
                Spacer(Modifier.width(10.dp))
                ActionPill(
                    text = "⚙ Фильтры" + if (filters.count > 0) " · ${filters.count}" else "",
                    active = showFilters || filters.count > 0,
                ) { showFilters = true }
                Spacer(Modifier.width(8.dp))
                // Выбранные фильтры — чипами, ✕ снимает значение
                for (t in types.toList()) {
                    ChipX("Тип: " + typeName(t)) { types = types - t }
                    Spacer(Modifier.width(6.dp))
                }
                for (g in genres.toList().take(2)) {
                    ChipX("Жанр: " + genreName(g)) { genres = genres - g }
                    Spacer(Modifier.width(6.dp))
                }
                for (c in countries.toList().take(2)) {
                    ChipX("Страна: $c") { countries = countries - c }
                    Spacer(Modifier.width(6.dp))
                }
                for (y in years.toList().take(2)) {
                    ChipX("Год: $y") { years = years - y }
                    Spacer(Modifier.width(6.dp))
                }
                if (!filters.isEmpty) {
                    ActionPill(text = "✕ Сбросить всё", active = false) {
                        types = emptySet(); genres = emptySet()
                        countries = emptySet(); years = emptySet()
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                if (searchMode) {
                    ActionPill(text = "✕ Сбросить поиск", active = true) {
                        searchMode = false; searchQ = ""; reloadTick++
                    }
                } else {
                    ActionPill(text = "🔍 Найти в трекере", active = inputOpen) {
                        inputOpen = !inputOpen
                        if (inputOpen && externalKb) {
                            runCatching { kbFieldFocus.requestFocus() }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    ActionPill(text = "🎤 Голос", active = false) { inputOpen = true; startVoice() }
                }
            }

            if (voiceStatus.isNotBlank()) {
                Text(
                    voiceStatus,
                    color = Color(0xFFFFD54F),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 36.dp, vertical = 2.dp),
                )
            }

            // ---------- Ввод запроса: внешняя клавиатура или экранная ----------
            if (inputOpen) {
                if (externalKb) {
                    LaunchedEffect(Unit) { runCatching { kbFieldFocus.requestFocus() } }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQ,
                            onValueChange = { searchQ = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFF5B942)),
                            modifier = Modifier
                                .focusRequester(kbFieldFocus)
                                .width(620.dp)
                                .background(Color(0xFF1F2430), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (searchQ.isEmpty()) {
                                        Text(
                                            "Печатайте с внешней клавиатуры…",
                                            color = Color.White.copy(alpha = 0.35f),
                                            fontSize = 18.sp,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        ActionPill(
                            text = "Найти ▶",
                            active = true,
                            modifier = Modifier.focusRequester(findFocus),
                        ) { runSearch() }
                    }
                } else {
                    KeyboardSheet(
                        value = searchQ,
                        title = "Поиск раздач",
                        doneLabel = "🔍 Найти",
                        onDone = { runSearch() },
                        onChar = { searchQ += it },
                        onBackspace = { if (searchQ.isNotEmpty()) searchQ = searchQ.dropLast(1) },
                        onClear = { searchQ = "" },
                        onDismiss = { inputOpen = false },
                        onSuggest = { searchQ = it },
                    )
                }
            }

            // ---------- Статус ----------
            Text(
                text = when {
                    searchMode && shown.isEmpty() && !loading ->
                        "Ничего не нашлось — попробуйте другое название или снимите фильтры"
                    offlineSince > 0 ->
                        "📡 Офлайн: показан сохранённый список от " +
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(offlineSince))
                    loadError.isNotEmpty() -> "⚠ Не удалось загрузить каталог: $loadError"
                    loading && shown.isEmpty() -> "Загружаю…"
                    shown.isEmpty() -> "По этим фильтрам ничего нет — ослабьте фильтры"
                    else -> buildString {
                        if (updatedAt > 0L) {
                            append("Обновлено ")
                            append(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(updatedAt)))
                            append(" · ")
                        }
                        append("${shown.size} карточек")
                        if (!searchMode && !endReached) append(" · листайте — подгрузится ещё")
                    }
                },
                color = if (loadError.isNotEmpty()) Color(0xFFF5B942) else Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 2.dp),
            )

            if (loadError.isNotEmpty()) {
                ActionPill(
                    text = "↻ Повторить",
                    active = false,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 2.dp),
                ) { reloadTick++ }
            }

            // ---------- Сетка карточек ----------
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 26.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.storeKey }) { item ->
                    PosterCard(item) { onOpenDetails(item) }
                }
                if (loading && shown.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                            Text("Подгружаю ещё…", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // ---------- Оверлей фильтров ----------
        if (showFilters) {
            FiltersOverlay(
                types = types,
                genres = genres,
                countries = countries,
                years = years,
                countryChoices = countryChoices,
                onTypes = { types = it },
                onGenres = { genres = it },
                onCountries = { countries = it },
                onYears = { years = it },
                onClose = { showFilters = false },
            )
        }
    }
}

/** Ключевые слова шоу (реалити/ток) — как в popularShows Рутрекера. */
private val SHOW_WORDS = Regex(
    "(?i)шоу|реалити|ток-|выживани|голос|битва|маска|фабрик|звёзд|звезд|проект",
)

/** Признак сериала в имени раздачи (Jackett). */
private val IS_TV_NAME = Regex("(?i)\\bS\\d{1,2}(E\\d{1,3})?\\b|\\d{1,2}x\\d{1,3}|сезон|сери[ияя]")

/** Основные страны — всегда сверху в списке фильтра. */
private val MAIN_COUNTRIES = listOf(
    "Россия", "США", "Великобритания", "Франция", "Германия", "Корея", "Япония",
    "Индия", "Испания", "Италия", "Канада", "Австралия", "Турция", "Китай",
    "Украина", "Польша", "Швеция", "Нидерланды",
)

private fun typeName(t: String): String = when (t) {
    "movie" -> "Фильмы"
    "tv" -> "Сериалы"
    "show" -> "Шоу"
    else -> t
}

private fun genreName(slug: String): String =
    RutrackerrCatalog.genreList().firstOrNull { it.first == slug }?.second ?: slug

/** Компактная кнопка-«пилюля» в шапке каталога. */
@Composable
private fun ActionPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (active) Color(0xFF3A2E14) else Color(0xFF1F2430),
            contentColor = if (active) Color(0xFFF5B942) else Color.White,
            focusedContainerColor = Color(0xFFF5B942),
            focusedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Text(text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

/** Чип выбранного фильтра с крестиком. */
@Composable
private fun ChipX(text: String, onRemove: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onRemove,
        shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF262C3A),
            contentColor = Color(0xFFC6CFDD),
            focusedContainerColor = Color(0xFFF5B942),
            focusedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Text("$text ✕", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/**
 * Оверлей фильтров: разделы слева-сверху, выбранное — галочками,
 * мультивыбор в каждом разделе. Пусто = «любой».
 */
@Composable
private fun FiltersOverlay(
    types: Set<String>,
    genres: Set<String>,
    countries: Set<String>,
    years: Set<String>,
    countryChoices: List<String>,
    onTypes: (Set<String>) -> Unit,
    onGenres: (Set<String>) -> Unit,
    onCountries: (Set<String>) -> Unit,
    onYears: (Set<String>) -> Unit,
    onClose: () -> Unit,
) {
    val total = types.size + genres.size + countries.size + years.size
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF20E1116))
            .padding(28.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚙ Фильтры", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Text(
                    if (total == 0) "ничего не выбрано — показываю всё" else "выбрано: $total",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                ActionPill(text = "↺ Сбросить", active = false) {
                    onTypes(emptySet()); onGenres(emptySet())
                    onCountries(emptySet()); onYears(emptySet())
                }
                Spacer(Modifier.width(8.dp))
                ActionPill(text = "✓ Применить", active = true) { onClose() }
                Spacer(Modifier.width(8.dp))
                ActionPill(text = "✕ Закрыть", active = false) { onClose() }
            }
            Text(
                "Галочками можно выбрать несколько значений в каждом разделе. Пусто = «любой».",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    FilterSection("Тип") {
                        MultiChip("Фильмы", types.contains("movie")) { onTypes(toggle(types, "movie")) }
                        MultiChip("Сериалы", types.contains("tv")) { onTypes(toggle(types, "tv")) }
                        MultiChip("Шоу", types.contains("show")) { onTypes(toggle(types, "show")) }
                    }
                }
                item {
                    FilterSection("Жанры") {
                        for ((slug, name) in RutrackerrCatalog.genreList()) {
                            MultiChip(name, genres.contains(slug)) { onGenres(toggle(genres, slug)) }
                        }
                    }
                }
                item {
                    FilterSection("Страны") {
                        for (c in countryChoices) {
                            MultiChip(c, countries.contains(c)) { onCountries(toggle(countries, c)) }
                        }
                    }
                }
                item {
                    FilterSection("Годы") {
                        for (y in yearChoices()) {
                            MultiChip(y, years.contains(y)) { onYears(toggle(years, y)) }
                        }
                    }
                }
            }
        }
    }
}

private fun toggle(set: Set<String>, value: String): Set<String> =
    if (set.contains(value)) set - value else set + value

private fun yearChoices(): List<String> {
    val years = (2026 downTo 1990).map { it.toString() }
    val decades = listOf("2020-е", "2010-е", "2000-е", "1990-е")
    return decades + years
}

/** Раздел фильтров: заголовок и строки чипов (FlowRow переносит по ширине). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title.uppercase(),
            color = Color(0xFF8B95A7),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { content() }
    }
}

/** Чип с галочкой (мультивыбор). */
@Composable
private fun MultiChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFF3A2E14) else Color(0xFF1F2430),
            contentColor = if (selected) Color(0xFFF5B942) else Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color(0xFFF5B942),
            focusedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Text(
            text = (if (selected) "✓ " else "☐ ") + label,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Сколько страниц каталога грузим сразу: 1 страница трекера = 10 карточек. */
private const val PAGES_AT_START = 3

/**
 * Порядок каталога: сначала свежие годы, внутри года — карточки с постером.
 * Раньше шло «как отдал сайт»: впереди висели старые раздачи без обложек.
 */
private fun catalogOrder(): Comparator<MediaItem> =
    compareByDescending<MediaItem> { m -> m.year.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0 }
        .thenByDescending { m -> m.posterUrl.isNotBlank() }

/** Единый вид списка после загрузки/ошибки: без дублей и в порядке каталога. */
private fun sortedOf(list: List<MediaItem>): List<MediaItem> =
    list.distinctBy { it.storeKey }.sortedWith(catalogOrder())
