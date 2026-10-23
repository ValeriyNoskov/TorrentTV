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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Онлайн-просмотр через Filmix: поиск карточки по названию, выбор
 * сезона/серии/перевода, воспроизведение прямой ссылки в Media3.
 */
@Composable
fun OnlineScreen(item: MediaItem, onBack: () -> Unit, onPlay: (OnlineService.OnlineLink) -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var status by remember { mutableStateOf("Ищу в Filmix…") }
    var matches by remember { mutableStateOf<List<OnlineService.OnlineMatch>>(emptyList()) }
    var chosen by remember { mutableStateOf<OnlineService.OnlineMatch?>(null) }
    var links by remember { mutableStateOf<List<OnlineService.OnlineLink>>(emptyList()) }

    var selectedSeason by remember { mutableStateOf(0) }
    var selectedEpisode by remember { mutableStateOf<OnlineService.OnlineLink?>(null) }

    val token = remember { store.filmixToken() }

    // Шаг 1: поиск карточки (основной запрос по оригиналу + русское название)
    LaunchedEffect(item) {
        val query1 = item.originalTitle.ifBlank { item.title }
        val found = withContext(Dispatchers.IO) {
            val a = OnlineService.search(token, query1)
            if (item.title.isNotBlank() && !item.title.equals(query1, ignoreCase = true)) {
                a + OnlineService.search(token, item.title)
            } else a
        }
        val unique = found.distinctBy { it.filmixId }
        matches = unique
        // Автовыбор: совпадение по оригинальному/русскому названию и году
        chosen = unique.firstOrNull { m ->
            (m.originalTitle.equals(item.originalTitle, true) || m.title.equals(item.title, true)) &&
                (m.year.isBlank() || item.year.isBlank() || m.year == item.year)
        } ?: unique.firstOrNull()
        if (chosen == null) {
            status = if (unique.isEmpty()) "Не найдено в Filmix — проверьте токен в настройках"
            else "Уточните выбор:"
        }
    }

    // Шаг 2: ссылки для выбранной карточки; если пусто — перебираем другие совпадения
    LaunchedEffect(chosen, matches) {
        val m = chosen ?: return@LaunchedEffect
        status = "Получаю ссылки…"
        var lastBlocked = false
        var lastNoField = false

        val queue = listOf(m) + matches.filter { it.filmixId != m.filmixId }.take(4)
        for (cand in queue) {
            val info = withContext(Dispatchers.IO) { OnlineService.playerInfo(token, cand.filmixId) }
            if (info.links.isNotEmpty()) {
                links = info.links
                selectedSeason = info.links.filter { it.season > 0 }.minOfOrNull { it.season } ?: 0
                return@LaunchedEffect
            }
            lastBlocked = lastBlocked || info.allBlocked
            lastNoField = lastNoField || !info.hasPlayerLinks
        }

        links = emptyList()
        status = when {
            token.isBlank() ->
                "Filmix не подключён — Настройки → 🌐 Filmix (код устройства)"
            withContext(Dispatchers.IO) { OnlineService.profile(token) } == null ->
                "Токен Filmix не активен — переподключите в настройках (🌐 Filmix)"
            lastBlocked ->
                "Контент заблокирован правообладателем даже с аккаунтом Filmix — смотрите через торренты"
            lastNoField ->
                "У найденных карточек нет онлайн-ссылок — смотрите через торренты"
            else ->
                "Не удалось получить ссылки — проверьте сеть или используйте торренты"
        }
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(32.dp),
    ) {
        Text("🌐 Онлайн · Filmix", color = Color(0xFFF5B942), fontSize = 16.sp)
        Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        // ---------- Выбор карточки, если автоподбор не сработал ----------
        if (chosen == null && matches.isNotEmpty()) {
            Text(status, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(matches) { _, m ->
                    ListRow(
                        title = listOf(m.title, m.year).filter { it.isNotBlank() }.joinToString(" · "),
                        hint = m.originalTitle,
                        onClick = { chosen = m },
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
    var maxQuality by remember { mutableStateOf<Int?>(null) } // null = авто

    val player = remember(link.url) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to "https://filmix.ac/"))
            .setConnectTimeoutMs(15_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
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
    BackHandler { onBack() }

    fun openExternal() {
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
        runCatching { context.startActivity(intent) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 10_000
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

        // Кнопки поверх видео
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            PlayerChip("⚙ Качество: ${maxQuality?.let { "${it}p" } ?: "авто"}") { showQuality = true }
            Spacer(Modifier.height(8.dp))
            PlayerChip("⤴ Внешний плеер") { openExternal() }
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
