package com.torrenttv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.data.AppLog
import com.torrenttv.app.data.Episode
import com.torrenttv.app.data.KinopoiskClient
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.MediaApi
import com.torrenttv.app.data.Season
import com.torrenttv.app.data.TorrServeService
import com.torrenttv.app.data.TorrentSearch
import com.torrenttv.app.data.TorrentResult
import com.torrenttv.app.torrent.TorrentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Сезоны и эпизоды сериала: выбор сезона (чипы), список серий с названием.
 * У каждой серии свой источник (magnet или .torrent) и свой прогресс —
 * смотреть сразу или искать раздачу под эпизод.
 */
@Composable
fun EpisodesScreen(
    item: MediaItem,
    onPlay: (Episode) -> Unit,
    onFind: (Episode) -> Unit,
    onBack: () -> Unit,
    initialSeason: Int? = null,
) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Int?>(null) } // null = первый сезон
    var tick by remember { mutableStateOf(0) } // перерисовка статусов после поиска источника

    LaunchedEffect(item) {
        seasons = withContext(Dispatchers.IO) { MediaApi.seasons(item) }
        // Главный источник при торрентовом показе — сама раздача: файлы в ней и
        // есть серии. КП/TMDB тут только украшение (названия серий), когда доступны.
        if (seasons.isEmpty()) {
            seasons = withContext(Dispatchers.IO) { seasonsFromTorrents(item, store, context) }
            if (seasons.isNotEmpty()) {
                AppLog.log("episodes", "«${item.title}»: сезоны из раздачи — ${seasons.sumOf { it.episodes.size }} серий")
            }
        }
        loading = false
        AppLog.log("episodes", "«${item.title}»: загружено сезонов ${seasons.size} (id=${item.id})")
        if (seasons.isNotEmpty() && selected == null) {
            selected = initialSeason?.takeIf { s -> seasons.any { it.number == s } }
                ?: seasons.first().number
        }
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(32.dp),
    ) {
        Text("🎬 Эпизоды", color = Color(0xFFF5B942), fontSize = 16.sp)
        Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        when {
            loading -> Text(
                "Загружаю сезоны…",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp),
            )

            seasons.isEmpty() -> Text(
                // Коротко без объяснений причин — детали в логе
                if (KinopoiskClient.lastFailed) "Эпизоды временно недоступны" else "Сезоны не найдены",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // ---------- Чипы сезонов ----------
        if (!loading && seasons.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp)) {
                LazyRow {
                    itemsIndexed(seasons) { _, s ->
                        SeasonChip("Сезон ${s.number}", s.number == (selected ?: seasons.first().number)) {
                            selected = s.number
                        }
                    }
                }
            }

            // ---------- Список эпизодов ----------
            val season = seasons.first { it.number == (selected ?: seasons.first().number) }
            LazyColumn(Modifier.padding(top = 10.dp).weight(1f)) {
                itemsIndexed(season.episodes) { _, ep ->
                    val hasSource = remember(tick) { store.getMagnet(item, ep.sourceSuffix) != null }
                    Surface(
                        onClick = { if (hasSource) onPlay(ep) else onFind(ep) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .mouseClick { if (hasSource) onPlay(ep) else onFind(ep) },
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
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                             Text(
                                 text = ep.code,
                                 color = Color(0xFFF5B942),
                                 fontSize = 15.sp,
                                 fontWeight = FontWeight.Bold,
                                 modifier = Modifier.width(78.dp),
                             )
                             // ✓ — серия просмотрена (кнопкой в плеере или досмотрена ≥95%)
                             @Suppress("UNUSED_EXPRESSION") tick
                             if (store.isWatchedKey(item.storeKey + ":online" + ep.sourceSuffix)) {
                                 Text(
                                     text = "✓",
                                     color = Color(0xFF9CCC65),
                                     fontSize = 17.sp,
                                     fontWeight = FontWeight.Bold,
                                     modifier = Modifier.width(24.dp),
                                 )
                             }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = ep.title.ifBlank { "Эпизод ${ep.episode}" },
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = if (hasSource) "▶ Смотреть" else "🔍 Найти",
                                color = if (hasSource) Color(0xFF9CCC65) else Color(0xFF64B5F6),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.padding(top = 10.dp))
        Button(onClick = onBack) { Text("← Назад") }
    }
}

/**
 * Сезоны из раздачи (главный источник при торрентовом показе): TorrServe
 * подхватывает лучшую раздачу, серии — видео-файлы (S01E05…). Магнит раздачи
 * сохраняется за каждой серией — «Смотреть» работает сразу, без поиска.
 */
private suspend fun seasonsFromTorrents(
    item: MediaItem,
    store: LocalStore,
    context: android.content.Context,
): List<Season> {
    val host = store.torrServeHost()
    if (host.isBlank() || !TorrServeService.ensureRunning(context, host)) return emptyList()
    val found: List<TorrentResult> = try {
        // Полный набор параметров, как в SmartPlayScreen: jackett/приватные
        // трекеры/исключения — иначе поиск сужен и «Спецы» не находятся
        TorrentSearch.searchDetailed(
            query = item.title,
            isTvShow = item.type != "movie",
            imdbId = item.imdbId,
            jackett = store.jackettConfig(),
            privateTrackers = store.privateTrackers(),
            excludedKeywords = store.excludedKeywords().toSet(),
        ).first
    } catch (e: Exception) {
        // Раньше падение глоталось молча — в логе «найдено 0» без причины
        AppLog.log("episodes", "поиск раздач FAIL: ${e.message}")
        emptyList()
    }
    AppLog.log("episodes", "«${item.title}»: раздач для эпизодов найдено ${found.size}")
    for (r in found.sortedByDescending { it.seeds }.take(3)) {
        val t = runCatching {
            TorrServeService.addMagnet(host, TorrentSearch.toMagnet(r), item.title, 45_000)
        }.getOrNull() ?: continue
        val video = t.files.filter {
            it.path.substringAfterLast('.', "").lowercase() in TorrentEngine.VIDEO_EXT
        }
        val bySeason = sortedMapOf<Int, MutableList<Episode>>()
        for (f in video) {
            val (s, e) = Episode.fileNumbers(f.path) ?: continue
            bySeason.getOrPut(s) { mutableListOf() }.add(
                Episode(
                    season = s,
                    episode = e,
                    title = f.path.substringAfterLast('/').substringBeforeLast('.'),
                ),
            )
        }
        if (bySeason.isEmpty()) continue
        val magnet = TorrentSearch.toMagnet(r)
        for (eps in bySeason.values) for (ep in eps) store.saveMagnet(item, magnet, ep.sourceSuffix)
        return bySeason.map { (num, eps) -> Season(num, eps.sortedBy { it.episode }) }.sortedBy { it.number }
    }
    return emptyList()
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
            disabledContainerColor = Color(0xFF1F2430),
            disabledContentColor = Color.White,
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
