package com.torrenttv.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.OnlineService
import com.torrenttv.app.data.RezkaService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Онлайн-просмотр: Filmix (если подключён) + HDRezka (fallback/второй источник).
 * Поиск карточки по названию, выбор сезона/серии/перевода, воспроизведение
 * прямой ссылки в Media3.
 */

/** Карточка любого источника для единого списка выбора. */
private data class Card(
    val source: String,
    val fx: OnlineService.OnlineMatch? = null,
    val rz: RezkaService.RezkaMatch? = null,
) {
    val title: String get() = fx?.title ?: rz?.title ?: ""
    val originalTitle: String get() = fx?.originalTitle ?: rz?.originalTitle ?: ""
    val year: String get() = fx?.year ?: rz?.year ?: ""
    val isSerial: Boolean get() = fx?.isSerial ?: rz?.isSerial ?: false
    val sourceLabel: String get() = if (source == "rezka") "HDRezka" else "Filmix"
}

@Composable
fun OnlineScreen(item: MediaItem, onBack: () -> Unit, onPlay: (OnlineService.OnlineLink) -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var status by remember { mutableStateOf("Ищу в Filmix и HDRezka…") }
    var cards by remember { mutableStateOf<List<Card>>(emptyList()) }
    var chosen by remember { mutableStateOf<Card?>(null) }
    var links by remember { mutableStateOf<List<OnlineService.OnlineLink>>(emptyList()) }

    // HDRezka-сериал: сезоны/эпизоды получаем из get_episodes, ссылку — на клике
    var rzSeasons by remember { mutableStateOf<List<Int>>(emptyList()) }
    var rzEpisodes by remember { mutableStateOf<Map<Int, List<Int>>>(emptyMap()) }
    var rzSeason by remember { mutableStateOf(0) }
    var rzEpisodeLinks by remember { mutableStateOf<List<OnlineService.OnlineLink>?>(null) }

    var selectedSeason by remember { mutableStateOf(0) }
    var selectedEpisode by remember { mutableStateOf<OnlineService.OnlineLink?>(null) }

    val token = remember { store.filmixToken() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    /** Ссылки эпизода HDRezka (асинхронно, вне композиции). */
    fun loadRezkaEpisode(
        m: RezkaService.RezkaMatch,
        season: Int,
        episode: Int,
        onResult: (List<OnlineService.OnlineLink>) -> Unit,
    ) {
        scope.launch {
            val got = withContext(Dispatchers.IO) { RezkaService.streams(m, season, episode) }
            onResult(got)
        }
    }

    // Шаг 1: поиск карточек в обоих источниках параллельно
    LaunchedEffect(item) {
        val query1 = item.originalTitle.ifBlank { item.title }
        val (fx, rz) = withContext(Dispatchers.IO) {
            val f = if (token.isNotBlank()) {
                runCatching {
                    val a = OnlineService.search(token, query1)
                    if (item.title.isNotBlank() && !item.title.equals(query1, true)) {
                        a + OnlineService.search(token, item.title)
                    } else a
                }.getOrDefault(emptyList())
            } else emptyList()
            val r = runCatching {
                RezkaService.search(query1) +
                    (item.title.takeIf { it.isNotBlank() && !it.equals(query1, true) }
                        ?.let { RezkaService.search(it) } ?: emptyList())
            }.getOrDefault(emptyList())
            f to r
        }

        val fxCards = fx.distinctBy { it.filmixId }.map { Card("filmix", fx = it) }
        // Rezka: ищем по обоим запросам, дедуп по url
        val rzCards = rz.distinctBy { it.url }.map { Card("rezka", rz = it) }
        cards = fxCards + rzCards
        com.torrenttv.app.data.AppLog.log(
            "online",
            "\"${item.title}\": filmix=${fxCards.size} rezka=${rzCards.size} (token=${token.isNotBlank()})",
        )

        // Автовыбор: совпадение по оригинальному/русскому названию и году
        val byTitle = { c: Card ->
            (c.originalTitle.equals(item.originalTitle, true) || c.title.equals(item.title, true)) &&
                (c.year.isBlank() || item.year.isBlank() || c.year == item.year)
        }
        chosen = cards.firstOrNull(byTitle)
            ?: cards.firstOrNull { it.title.equals(item.title, true) }
            ?: cards.firstOrNull()
        if (chosen == null) {
            status = "Не найдено в Filmix и HDRezka — смотрите через торренты"
        }
    }

    // Шаг 2: ссылки для выбранной карточки
    LaunchedEffect(chosen) {
        val c = chosen ?: return@LaunchedEffect
        if (c.source == "rezka") {
            status = "Получаю ссылки с HDRezka…"
            val m = c.rz ?: return@LaunchedEffect
            if (c.isSerial) {
                val (seasons, eps) = withContext(Dispatchers.IO) { RezkaService.episodes(m) }
                rzSeasons = seasons
                rzEpisodes = eps
                rzSeason = seasons.firstOrNull() ?: 0
                rzEpisodeLinks = null
                links = emptyList()
                if (seasons.isEmpty()) status = "HDRezka: не удалось получить список серий"
                return@LaunchedEffect
            }
            val got = withContext(Dispatchers.IO) { RezkaService.streams(m) }
            com.torrenttv.app.data.AppLog.log("online", "rezka movie: links=${got.size}")
            if (got.isNotEmpty()) {
                links = got
            } else {
                status = "HDRezka: ссылки не получены — попробуйте торренты"
            }
            return@LaunchedEffect
        }

        // Filmix: если пусто — перебираем другие совпадения, затем сам Rezka
        status = "Получаю ссылки…"
        var lastBlocked = false
        var lastNoField = false

        val fxMatches = cards.filter { it.source == "filmix" && it.fx != null }
        val queue = listOf(c) + fxMatches.filter { it.fx?.filmixId != c.fx?.filmixId }.take(4)
        for (cand in queue) {
            val id = cand.fx?.filmixId ?: continue
            val info = withContext(Dispatchers.IO) { OnlineService.playerInfo(token, id) }
            com.torrenttv.app.data.AppLog.log(
                "online",
                "filmix $id: links=${info.links.size} blocked=${info.allBlocked} hasField=${info.hasPlayerLinks}",
            )
            if (info.links.isNotEmpty()) {
                links = info.links
                selectedSeason = info.links.filter { it.season > 0 }.minOfOrNull { it.season } ?: 0
                return@LaunchedEffect
            }
            lastBlocked = lastBlocked || info.allBlocked
            lastNoField = lastNoField || !info.hasPlayerLinks
        }

        // Fallback: HDRezka
        val rzCard = cards.firstOrNull { it.source == "rezka" }
        if (rzCard?.rz != null) {
            status = "Filmix пуст — пробую HDRezka…"
            val m = rzCard.rz!!
            if (rzCard.isSerial) {
                val (seasons, eps) = withContext(Dispatchers.IO) { RezkaService.episodes(m) }
                rzSeasons = seasons
                rzEpisodes = eps
                rzSeason = seasons.firstOrNull() ?: 0
                rzEpisodeLinks = null
                if (seasons.isNotEmpty()) return@LaunchedEffect
            } else {
                val got = withContext(Dispatchers.IO) { RezkaService.streams(m) }
                if (got.isNotEmpty()) {
                    links = got
                    return@LaunchedEffect
                }
            }
        }

        links = emptyList()
        status = when {
            token.isBlank() ->
                "Filmix не подключён, HDRezka недоступен — смотрите через торренты"
            withContext(Dispatchers.IO) { OnlineService.profile(token) } == null ->
                "Токен Filmix не активен, HDRezka недоступен — смотрите через торренты"
            lastBlocked ->
                "Контент заблокирован правообладателем (Filmix), HDRezka не ответил — смотрите через торренты"
            lastNoField ->
                "У найденных карточек нет онлайн-ссылок — смотрите через торренты"
            else ->
                "Не удалось получить ссылки — проверьте сеть или используйте торренты"
        }
        com.torrenttv.app.data.AppLog.log("online", "empty: $status")
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(32.dp),
    ) {
        Text("🌐 Онлайн · Filmix + HDRezka", color = Color(0xFFF5B942), fontSize = 16.sp)
        Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        // ---------- Выбор карточки, если автоподбор не сработал ----------
        if (chosen == null && cards.isNotEmpty()) {
            Text(status, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(cards) { _, c ->
                    ListRow(
                        title = listOf(c.title, c.year).filter { it.isNotBlank() }.joinToString(" · "),
                        hint = listOf(c.originalTitle, c.sourceLabel).filter { it.isNotBlank() }.joinToString(" · "),
                        onClick = { chosen = c },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onBack) { Text("← Назад") }
            return@Column
        }

        if (chosen == null) {
            Text(status, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.weight(1f))
            Button(onClick = onBack) { Text("← Назад") }
            return@Column
        }

        // ---------- HDRezka-сериал: сезоны → серии → качества ----------
        val c = chosen!!
        if (c.source == "rezka" && c.isSerial && rzEpisodes.isNotEmpty()) {
            val pendingEps = rzEpisodeLinks
            if (pendingEps == null) {
                Row(Modifier.padding(top = 12.dp)) {
                    LazyRow {
                        itemsIndexed(rzSeasons) { _, s ->
                            SeasonChipLite("Сезон $s", s == rzSeason) { rzSeason = s }
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    val eps = rzEpisodes[rzSeason] ?: emptyList()
                    itemsIndexed(eps) { _, e ->
                        ListRow(
                            title = "E%02d".format(e),
                            hint = "HDRezka",
                            onClick = {
                                status = "Получаю ссылки…"
                                links = emptyList()
                                // загружаем ссылки эпизода в фоне
                                loadRezkaEpisode(c.rz!!, rzSeason, e) { got ->
                                    if (got.size == 1) onPlay(got.first())
                                    else {
                                        rzEpisodeLinks = got
                                        if (got.isEmpty()) status = "HDRezka: ссылки не получены"
                                    }
                                }
                            },
                        )
                    }
                }
            } else {
                Text(
                    "S%02d — выберите качество:".format(rzSeason),
                    color = Color(0xFFF5B942), fontSize = 16.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(pendingEps) { _, l ->
                        ListRow(title = l.label, hint = l.url, onClick = { onPlay(l) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { rzEpisodeLinks = null }) { Text("← К списку серий") }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = { chosen = null; links = emptyList(); rzEpisodes = emptyMap() }) { Text("Другая карточка") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onBack) { Text("← Назад") }
            return@Column
        }

        if (links.isEmpty()) {
            Text(status, color = Color(0xFFFFD54F), fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.weight(1f))
            Button(onClick = onBack) { Text("← Назад") }
            return@Column
        }

        // ---------- Фильм: варианты качества/перевода ----------
        val movieLinks = links.filter { it.season == 0 }
        if (movieLinks.isNotEmpty()) {
            Text(
                "Выберите перевод и качество:",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(movieLinks) { _, l ->
                    ListRow(title = l.label.ifBlank { "Смотреть" }, hint = l.url, onClick = { onPlay(l) })
                }
            }
        } else {
            // ---------- Сериал: сезоны → серии → переводы ----------
            val seasons = links.map { it.season }.distinct().sorted()
            Row(Modifier.padding(top = 12.dp)) {
                LazyRow {
                    itemsIndexed(seasons) { _, s ->
                        SeasonChipLite("Сезон $s", s == selectedSeason) { selectedSeason = s }
                    }
                }
            }

            val ep = selectedEpisode
            if (ep == null) {
                val eps = links.filter { it.season == selectedSeason }
                    .groupBy { it.episode }
                    .toSortedMap()
                LazyColumn(Modifier.weight(1f)) {
                    for ((num, variants) in eps) {
                        item {
                            ListRow(
                                title = "E%02d".format(num),
                                hint = variants.groupBy { it.translation }.keys.joinToString(", ").ifBlank { "Смотреть" },
                                onClick = { selectedEpisode = variants.first() },
                            )
                        }
                    }
                }
            } else {
                val variants = links.filter {
                    it.season == ep.season && it.episode == ep.episode
                }
                Text(
                    "S%02dE%02d — выберите перевод:".format(ep.season, ep.episode),
                    color = Color(0xFFF5B942),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(variants) { _, l ->
                        ListRow(title = l.label, hint = l.url, onClick = { onPlay(l) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { selectedEpisode = null }) { Text("← К списку серий") }
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(onClick = { chosen = null; links = emptyList() }) { Text("Другая карточка") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack) { Text("← Назад") }
    }
}

@Composable
private fun ListRow(title: String, hint: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
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
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hint.isNotBlank() && hint != title) {
                Text(
                    hint,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SeasonChipLite(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .padding(end = 8.dp)
            .mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFFF5B942) else Color(0xFF1F2430),
            contentColor = if (selected) Color(0xFF0E1116) else Color.White,
            focusedContainerColor = Color(0xFFFFD07A),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Плеер онлайн-потока: Media3 + заголовки Filmix (Referer/User-Agent).
 *  Кнопки поверх: выбор качества (ограничение трек-селекшна) и внешний плеер. */
@Composable
fun OnlinePlayerScreen(link: OnlineService.OnlineLink, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var showQuality by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var maxQuality by remember { mutableStateOf<Int?>(null) } // null = авто
    var seekInfo by remember { mutableStateOf("") }           // «⏩ +10 сек» при перемотке пультом

    val player = remember(link.url) {
        // CDN проверяет Referer: у Filmix — filmix.ac, у HDRezka — её зеркало.
        // TorrServe (label="torrserve") — локальный стрим, Referer не нужен.
        val referer = if (link.label == "torrserve") "" else link.referer.ifBlank { "https://filmix.ac/" }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .apply { if (referer.isNotBlank()) setDefaultRequestProperties(mapOf("Referer" to referer)) }
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(if (link.label == "torrserve") 60_000 else 15_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                // Русский звук по умолчанию (дубляж), если дорожки размечены
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage("rus")
                    .build()
                // .m3u8 → HLS-источник, остальное — прогрессивный стрим
                setMediaItem(
                    ExoMediaItem.Builder()
                        .setUri(link.url)
                        .setMimeType(
                            if (link.url.contains(".m3u8", ignoreCase = true))
                                androidx.media3.common.MimeTypes.APPLICATION_M3U8
                            else null
                        )
                        .build()
                )
                prepare()
                playWhenReady = true
            }
    }

    // Ограничение качества видео (для HLS-мастера с вариантами)
    LaunchedEffect(player, maxQuality) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, maxQuality ?: Int.MAX_VALUE)
            .build()
    }

    DisposableEffect(player) { onDispose { player.release() } }

    BackHandler(enabled = showQuality) { showQuality = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler { onBack() }

    // Галочка «Всегда внешний плеер»: сразу открываем во внешнем плеере и выходим
    val storePrefs = remember { com.torrenttv.app.data.LocalStore(context) }
    var extMsg by remember { mutableStateOf("") }
    fun openExternal(auto: Boolean = false) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(
                android.net.Uri.parse(link.url),
                if (link.url.contains(".m3u8", true)) "application/x-mpegURL" else "video/*",
            )
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) {
            extMsg = "Внешний плеер не найден — установите VLC или MX Player, либо выключите «Всегда внешний плеер» в настройках"
        } else if (auto) {
            onBack()
        }
    }
    LaunchedEffect(Unit) {
        if (storePrefs.useExternalPlayer()) openExternal(auto = true)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 10_000
                    // Пульт: влево/вправо — перемотка ±10 сек (с индикатором),
                    // обычное поведение контроллера (фокус-навигация) не подходит ТВ
                    setOnKeyListener { _, keyCode, event ->
                        // Влево/вправо — перемотка ±10 сек
                        if (event.action == android.view.KeyEvent.ACTION_UP &&
                            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                                keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                        ) {
                            val delta = if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                            val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                            player.seekTo((player.currentPosition + delta).coerceIn(0L, dur))
                            seekInfo = if (delta > 0) "⏩ +10 сек" else "⏪ −10 сек"
                            true
                        } else if (event.action == android.view.KeyEvent.ACTION_UP &&
                            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                                keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        ) {
                            // ОК на пульте — пауза/продолжить
                            if (player.isPlaying) player.pause() else player.play()
                            seekInfo = if (player.isPlaying) "▶ Продолжаем" else "⏸ Пауза"
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            "$title · ${link.label}",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )

        // Индикатор перемотки пультом
        if (seekInfo.isNotBlank()) {
            LaunchedEffect(seekInfo) {
                kotlinx.coroutines.delay(900)
                seekInfo = ""
            }
            Text(
                seekInfo,
                color = Color(0xFFF5B942),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE0151B26), RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        // Шестерёнка: качество и внешний плеер — внутри меню
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            PlayerChip(if (showSettings) "✕" else "⚙") { showSettings = !showSettings }
        }

        // Меню шестерёнки
        if (showSettings) {
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 16.dp)
                    .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                PlayerChip("Качество: ${maxQuality?.let { "${it}p" } ?: "авто"}") {
                    showSettings = false
                    showQuality = true
                }
                Spacer(Modifier.height(6.dp))
                PlayerChip("⤴ Внешний плеер") {
                    showSettings = false
                    openExternal()
                }
            }
        }

        if (extMsg.isNotBlank()) {
            Text(
                extMsg,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xF0151B26), RoundedCornerShape(12.dp))
                    .padding(16.dp),
            )
        }

        if (showQuality) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Качество видео",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                val options = listOf(null to "Авто (по каналу)", 2160 to "2160p · 4K", 1080 to "1080p", 720 to "720p", 480 to "480p")
                for ((q, label) in options) {
                    androidx.tv.material3.Surface(
                        onClick = { maxQuality = q; showQuality = false },
                        modifier = Modifier
                            .width(220.dp)
                            .padding(vertical = 3.dp)
                            .mouseClick { maxQuality = q; showQuality = false },
                        shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (maxQuality == q) Color(0xFFF5B942) else Color(0xFF1F2430),
                            contentColor = if (maxQuality == q) Color(0xFF0E1116) else Color.White,
                            focusedContainerColor = Color(0xFFFFD07A),
                            focusedContentColor = Color(0xFF0E1116),
                            pressedContainerColor = Color(0xFFE0A82E),
                            pressedContentColor = Color(0xFF0E1116),
                        ),
                    ) {
                        Text(
                            label,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Небольшая кнопка поверх видео. */
@Composable
private fun PlayerChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x990E1116),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFF5B942),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
