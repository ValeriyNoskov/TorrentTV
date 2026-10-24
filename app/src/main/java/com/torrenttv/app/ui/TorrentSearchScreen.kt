package com.torrenttv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.PrivateTrackers
import com.torrenttv.app.data.ReleaseQuality
import com.torrenttv.app.data.TorrentResult
import com.torrenttv.app.data.TorrentSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


/**
 * Автопоиск торрентов по названию карточки («название + год») через все
 * доступные провайдеры (apibay, Torrentio, Jackett). Скраббер: чипы
 * фильтра по качеству (2160p/1080p/720p/SD), сортировка по сидам.
 * Выбор результата привязывает magnet к карточке.
 *
 * Zona-режим (autoStart=true): лучшая раздача подбирается автоматически
 * и сразу начинается просмотр; список остаётся для ручной замены.
 */
@Composable
fun TorrentSearchScreen(
    item: MediaItem,
    episode: com.torrenttv.app.data.Episode? = null,
    autoStart: Boolean = true,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var status by remember { mutableStateOf("Ищу торренты…") }
    var results by remember { mutableStateOf<List<TorrentResult>>(emptyList()) }
    var stats by remember { mutableStateOf<List<TorrentSearch.ProviderStat>>(emptyList()) }
    var quality by remember { mutableStateOf<String?>(null) } // null = все
    // Выбранная частная раздача: идёт скачивание .torrent
    var selecting by remember { mutableStateOf<TorrentResult?>(null) }

    // Запросы: оригинальное (основной) и русское название (альтернативный);
    // для эпизода сериала — суффикс S01E05 вместо года
    val epCode = episode?.code ?: ""
    val query = remember(item, episode) {
        val base = item.originalTitle.ifBlank { item.title }
        when {
            episode != null -> "$base $epCode"
            item.year.isNotBlank() -> "$base ${item.year}"
            else -> base
        }
    }
    val altQuery = remember(item, episode) {
        val ru = item.title
        if (ru.isBlank() || ru.equals(item.originalTitle, ignoreCase = true)) null
        else if (episode != null) "$ru $epCode" else ru
    }

    // Выбор раздачи: магнит сохраняется и сразу открывается плеер;
    // частная раздача — сначала скачивание .torrent (см. LaunchedEffect(selecting))
    fun select(r: TorrentResult) {
        val suffix = episode?.sourceSuffix ?: ""
        if (r.isPrivate) {
            selecting = r
        } else {
            store.saveMagnet(item, TorrentSearch.toMagnet(r), suffix)
            onDone()
        }
    }

    LaunchedEffect(item) {
        status = if (autoStart) "Подбираю лучшую раздачу…" else "Ищу торренты…"
        val (found, st) = TorrentSearch.searchDetailed(
            query = query,
            altQuery = altQuery,
            isTvShow = item.type != "movie",
            imdbId = item.imdbId,
            season = episode?.season ?: 0,
            episode = episode?.episode ?: 0,
            jackett = store.jackettConfig(),
            privateTrackers = store.privateTrackers(),
            excludedKeywords = store.excludedKeywords().toSet(),
        )
        results = found
        stats = st
        if (autoStart && found.isNotEmpty()) {
            select(bestTorrent(found))
            return@LaunchedEffect
        }
        status = if (found.isEmpty()) {
            "Ничего не найдено — попробуйте «Указать magnet» вручную"
        } else {
            "Найдено: ${found.size}. Выберите раздачу (больше сидов — быстрее)"
        }
    }

    val availableQualities = remember(results) {
        ReleaseQuality.KNOWN.filter { res -> results.any { it.resolution == res } }
    }
    val shown = remember(results, quality) {
        if (quality == null) results else results.filter { it.resolution == quality }
    }

    // Выбор частной раздачи: .torrent с куками → info_hash → файл-источник
    LaunchedEffect(selecting) {
        val r = selecting ?: return@LaunchedEffect
        status = "Получаю .torrent с ${r.provider}…"
        val path = withContext(Dispatchers.IO) {
            PrivateTrackers.downloadTorrent(
                r,
                auth = store.trackerAuth(r.provider),
                destDir = File(context.filesDir, "torrents"),
            )
        }
        if (path != null) {
            store.saveMagnet(item, path, episode?.sourceSuffix ?: "")
            onDone()
        } else {
            status = "Не удалось скачать .torrent"
            selecting = null
        }
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(32.dp),
    ) {
        Text(if (autoStart) "🚀 Просмотр" else "🔍 Автопоиск", color = Color(0xFFF5B942), fontSize = 16.sp)
        Text(
            if (episode != null) "${item.title} · ${episode.code}"
            else item.title,
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
        )
        Text(status, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        // Диагностика: сколько нашёл каждый провайдер («apibay:20 · torrentio:35»)
        if (stats.isNotEmpty()) {
            Text(
                stats.joinToString(" · ") { it.label() },
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // ---------- Чипы качества (скраббер) ----------
        if (availableQualities.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp)) {
                QualityChip(label = "Все", selected = quality == null) { quality = null }
                for (res in availableQualities) {
                    Spacer(Modifier.width(8.dp))
                    QualityChip(label = res, selected = quality == res) { quality = res }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(shown) { r ->
                Surface(
                    onClick = { select(r) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .mouseClick { select(r) },
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
                        Text(r.name, color = Color.White, fontSize = 17.sp, maxLines = 2)
                        Row(Modifier.padding(top = 4.dp)) {
                            Text(
                                text = "↑ ${r.seeds}",
                                color = seedColor(r.seeds),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = r.sizeLabel(),
                                color = Color(0xFFF5B942),
                                fontSize = 14.sp,
                            )
                            if (r.resolution.isNotBlank()) {
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    text = r.resolution,
                                    color = Color(0xFF64B5F6),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            // Источник релиза и перевод — бейджи из названия
                            releaseBadges(r.name).forEach { (label, color) ->
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = label,
                                    color = color,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = r.provider,
                                color = Color.White.copy(alpha = 0.35f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row {
            androidx.tv.material3.Button(onClick = onBack) { Text("← Назад") }
        }
    }
}

/** Чип выбора качества. */
@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.mouseClick(onClick),
        shape = clickableSurfaceShape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFFF5B942) else Color(0xFF1F2430),
            contentColor = if (selected) Color(0xFF0E1116) else Color.White,
            focusedContainerColor = Color(0xFF31435A),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0xFF273548),
            pressedContentColor = Color.White,
            disabledContainerColor = Color(0xFF1F2430),
            disabledContentColor = Color.White,
        ),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/**
 * Лучшая раздача для авто-старта (как в Zona): предпочитаем HD (2160/1080/720),
 * если у HD прилично сидов (≥10); иначе просто максимум сидов.
 */
internal fun bestTorrent(list: List<TorrentResult>): TorrentResult {
    if (list.size == 1) return list[0]
    val hd = list.filter { it.resolution in setOf("2160p", "1080p", "720p") }
    val pool = if (hd.any { it.seeds >= 10 }) hd else list
    return pool.maxByOrNull { it.seeds } ?: list.first()
}
