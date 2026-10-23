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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.MediaApi
import com.torrenttv.app.data.TorrentHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Раскладка экранной клавиатуры (ЙЦУКЕН)
private val KEY_ROWS = listOf(
    listOf('Й', 'Ц', 'У', 'К', 'Е', 'Н', 'Г', 'Ш', 'Щ', 'З', 'Х', 'Ъ'),
    listOf('Ф', 'Ы', 'В', 'А', 'П', 'Р', 'О', 'Л', 'Д', 'Ж', 'Э'),
    listOf('Я', 'Ч', 'С', 'М', 'И', 'Т', 'Ь', 'Б', 'Ю'),
    listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0'),
    listOf('-', ' '),
)

/**
 * Экран поиска: экранная клавиатура для пульта + голосовой ввод микрофоном.
 * Результаты ищутся в TMDB (фильмы + сериалы) с дебаунсом 500 мс.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    onTorrentSearch: (String) -> Unit = {},
    /** Раздача выбрана прямо из поиска: magnet сохранён — открыть плеер. */
    onPlayTorrent: (MediaItem) -> Unit = {},
    /** Раздача из поиска: полный список раздач запроса (сорт. «лучшая сверху»). */
    onOpenResults: (MediaItem, String, List<com.torrenttv.app.data.TorrentResult>) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var torrents by remember { mutableStateOf<List<com.torrenttv.app.data.TorrentResult>>(emptyList()) }
    // Постер и описание раздач: ключ — название раздачи
    var torrentMeta by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var searchTick by remember { mutableStateOf(0) } // форс-повтор
    val firstKeyFocus = remember { FocusRequester() }

    // Голосовой ввод: системный распознаватель речи
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val text = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                query = text.trim()
                status = ""
            }
        }
    }

    fun startVoice() {
        status = ""
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите название фильма или сериала")
            }
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            status = "Голосовой ввод недоступен на этом устройстве — используйте клавиатуру"
        }
    }

    // Поиск с дебаунсом; searchTick — немедленный поиск по кнопке «Найти».
    // Фильмы и торренты ищутся параллельно — раздачи сразу в результатах.
    var searchTickForce by remember { mutableStateOf(0) }
    LaunchedEffect(query, searchTickForce) {
        if (query.length >= 2) {
            loading = true
            failed = false
            delay(500)
            kotlinx.coroutines.coroutineScope {
                val films = async(Dispatchers.IO) {
                    MediaApi.search(query).filter { store.isAllowed(it) }
                }
                val tr = async(Dispatchers.IO) {
                    runCatching {
                        com.torrenttv.app.data.TorrentSearch.searchDetailed(
                            query = query,
                            altQuery = null,
                            isTvShow = false,
                            imdbId = "",
                            jackett = store.jackettConfig(),
                            privateTrackers = store.privateTrackers(),
                            excludedKeywords = store.excludedKeywords().toSet(),
                        ).first
                    }.getOrDefault(emptyList())
                }
                results = films.await()
                torrents = tr.await()
                    .sortedByDescending { it.seeds }
                    .take(16)
                // Постер и описание раздач — со страниц Рутрекера (параллельно)
                val toLoad = torrents.filter { it.pageUrl.isNotBlank() }.take(12)
                if (toLoad.isNotEmpty()) {
                    val meta = withContext(Dispatchers.IO) {
                        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
                        try {
                            toLoad.map { r ->
                                pool.submit<Pair<String, Pair<String, String>>> {
                                    val html = runCatching {
                                        TorrentHttp.get(r.pageUrl, mapOf("User-Agent" to "Mozilla/5.0 Chrome/120"))
                                    }.getOrNull() ?: return@submit r.name to ("" to "")
                                    val poster = Regex("""<img[^>]+src="([^"]*uploads/posts/[^"]+)"""")
                                        .find(html)?.groupValues?.get(1)
                                        ?.let { if (it.startsWith("http")) it else "https://rutrackerr.ru" + it } ?: ""
                                    // Описание из meta description: срезаем шаблонный префикс
                                    var desc = Regex("""<meta name="description" content="([^"]{20,600})"""")
                                        .find(html)?.groupValues?.get(1) ?: ""
                                    desc = desc.replace(Regex("""^.*?у нас на портале\.\s*"""), "")
                                        .replace(Regex("""\s+Рутрекер[^.]*\."""), "")
                                        .trim()
                                    r.name to (poster to desc)
                                }
                            }.map { it.get() }.toMap()
                        } finally {
                            pool.shutdown()
                        }
                    }
                    torrentMeta = meta
                }
            }
            // пустой ответ при ошибке API (сеть/лимит) — показываем «Повторить»
            failed = results.isEmpty() && MediaApi.lastSearchFailed
            loading = false
        } else {
            results = emptyList()
            torrents = emptyList()
            failed = false
            loading = false
        }
    }

    /** Постер для раздачи: карточка фильма с совпадающим названием. */
    fun posterFor(name: String): Pair<MediaItem, String> {
        val n = name.lowercase().replace('ё', 'е')
        val card = results.firstOrNull { c ->
            val t = c.title.lowercase().replace('ё', 'е')
            t.length >= 3 && (n.contains(t) || t.contains(n))
        } ?: results.firstOrNull { c -> c.posterUrl.isNotBlank() && n.contains(c.title.lowercase().take(6)) }
        val item = card ?: MediaItem(
            id = kotlin.math.abs(("qt-" + name).hashCode()),
            type = "movie",
            title = name.substringBefore('[').substringBefore('(').trim().ifBlank { name },
        )
        return item to (card?.posterUrl ?: "")
    }
    var showKb by remember { mutableStateOf(false) }
    val externalKb = remember { store.useExternalKeyboard() }
    val findFocus = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(24.dp),
    ) {
        // ---------- Верхняя строка ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.width(16.dp))
            Text(
                text = if (query.isBlank()) "Введите название…" else query,
                color = if (query.isBlank()) Color.White.copy(alpha = 0.4f) else Color(0xFFF5B942),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { startVoice() }) { Text("🎤 Голос") }
            Spacer(Modifier.width(10.dp))
            Button(onClick = {
                showKb = !showKb
                if (!showKb) findFocus.requestFocus()
            }) { Text(if (showKb) "⌨ Скрыть ввод" else "⌨ Ввод") }
            Spacer(Modifier.width(10.dp))
            Button(onClick = { searchTickForce++ }, modifier = Modifier.focusRequester(findFocus)) {
                Text("🔍 Найти")
            }
        }
        if (status.isNotBlank()) {
            Text(status, color = Color(0xFFFFD54F), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }

        // ---------- Результаты ----------
        if (results.isNotEmpty()) {
            Text(
                text = if (loading) "Результаты (обновление…)" else "Найдено: ${results.size}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            LazyRow(contentPadding = PaddingValues(vertical = 6.dp)) {
                items(results.size) { i -> PosterCard(results[i]) { onOpenDetails(results[i]) } }
            }
        }
        // ---------- Раздачи: сразу в результатах, с постером ----------
        if (torrents.isNotEmpty()) {
            Text(
                text = "🧲 Раздачи: ${torrents.size}" + if (loading) " (обновление…)" else "",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            LazyRow(contentPadding = PaddingValues(vertical = 6.dp)) {
                items(torrents.size) { i ->
                    val r = torrents[i]
                    val (tItem, cardPoster) = posterFor(r.name)
                    val meta = torrentMeta[r.name]
                    val poster = meta?.first?.takeIf { it.isNotBlank() } ?: cardPoster
                    val desc = meta?.second.orEmpty()
                    val card = tItem.copy(posterUrl = poster)
                    Surface(
                        onClick = {
                            // Полный список раздач запроса: «лучшая сверху», пиры/сиды
                            onOpenResults(card, query, torrents)
                        },
                        modifier = Modifier.width(170.dp).padding(6.dp),
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            focusedContainerColor = Color(0xFF3A2E14),
                        ),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            if (poster.isNotBlank()) {
                                coil.compose.AsyncImage(
                                    model = poster,
                                    contentDescription = r.name,
                                    modifier = Modifier.fillMaxWidth().height(150.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                r.name.substringBefore('[').substringBefore('(').trim().ifBlank { r.name },
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = if (poster.isNotBlank()) 2 else 4,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (desc.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    desc,
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 12.sp,
                                    maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "🧲 ${r.seeds} · ${
                                    android.text.format.Formatter.formatShortFileSize(context, r.sizeBytes)
                                }",
                                color = Color(0xFF8BD17C),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        } else if (loading) {
            Text("Ищем…", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
        } else if (failed) {
            Text(
                "Не удалось получить данные — источник фильмов недоступен или исчерпан лимит API.",
                color = Color(0xFFFFD54F),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = { searchTickForce++ }) { Text("🔄 Повторить") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { onTorrentSearch(query) }) { Text("🧲 Искать в торрентах") }
            }
        } else if (query.length >= 2) {
            // Ничего не найдено по фильму — всегда предложим торренты напрямую
            Text("Фильмов не найдено.", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            Button(onClick = { onTorrentSearch(query) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("🧲 Искать «$query» в торрентах")
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---------- Клавиатура: встроенная или внешняя (системная IME) ----------
        if (showKb) {
            if (externalKb) {
                val kbFieldFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { kbFieldFocus.requestFocus() }
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFF5B942)),
                    modifier = Modifier
                        .focusRequester(kbFieldFocus)
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(Color(0xFF1F2430), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
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
            } else {
                TvKeyboard(
                    onChar = { query += it },
                    onBackspace = { if (query.isNotEmpty()) query = query.dropLast(1) },
                    onClear = { query = "" },
                    firstKeyFocus = firstKeyFocus,
                )
            }
        }
    }
}

/** TV-клавиатура: сетка фокусируемых клавиш, навигация с пульта. */
@Composable
fun TvKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    firstKeyFocus: FocusRequester? = null,
) {
    LaunchedEffect(Unit) { firstKeyFocus?.requestFocus() }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        KEY_ROWS.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rowIndex == 3) {
                    KeyboardKey('␣', wide = true, onClick = { onChar(' ') })
                }
                row.forEachIndexed { colIndex, ch ->
                    KeyboardKey(
                        ch,
                        focusRequester = if (rowIndex == 0 && colIndex == 0) firstKeyFocus else null,
                    ) { onChar(ch) }
                }
                if (rowIndex == 3) {
                    KeyboardKey('⌫', wide = true, onClick = onBackspace)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyboardKey('О', wide = true, label = "Очистить", onClick = onClear)
        }
    }
}

@Composable
private fun KeyboardKey(
    symbol: Char,
    wide: Boolean = false,
    label: String? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(if (wide) 140.dp else 58.dp)
            .height(52.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1F2430),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF31435A),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0xFF273548),
            pressedContentColor = Color.White,
            disabledContainerColor = Color(0xFF1F2430),
            disabledContentColor = Color.White,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label ?: symbol.toString(),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
