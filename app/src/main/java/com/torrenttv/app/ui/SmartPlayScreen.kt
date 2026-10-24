package com.torrenttv.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.TorrServeService
import com.torrenttv.app.data.TorrentResult
import com.torrenttv.app.data.TorrentSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Одна строка списка выбора источника. */
sealed class SmartChoice {
    abstract val label: String
    abstract val sub: String

    data class Torrent(val r: TorrentResult) : SmartChoice() {
        override val label: String = "🧲 ${r.name}"
        override val sub: String = "${r.provider} · ${r.seeds} сидов · ${r.sizeLabel()}"
    }

    /** «Играть сразу»: лучшая раздача, выбранная автоматически. */
    data class Instant(val r: TorrentResult) : SmartChoice() {
        override val label: String = "⚡ Играть сразу — ${r.name}"
        override val sub: String = "лучшая раздача · ${r.seeds} сидов · ${r.sizeLabel()}"
    }

    /** Продолжить сериал с последней серии (раздача уже сохранена). */
    data object Resume : SmartChoice() {
        override val label: String = "⚡ Продолжить просмотр сериала"
        override val sub: String = "с последней серии, мгновенно"
    }
}

/**
 * Экран выбора источника просмотра: собирает ВСЕ доступные ссылки
 * (торренты со всех трекеров) параллельно и
 * показывает единым списком — пользователь выбирает сам, что смотреть.
 */
@Composable
fun SmartPlayScreen(
    item: MediaItem,
    onBack: () -> Unit,
    /** Выбран торрент: magnet сохранён — открыть плеер. */
    onPlayTorrent: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalStore(context) }

    var status by remember { mutableStateOf("Собираю все источники…") }
    var done by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var uploadUrl by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }

    var choices by remember { mutableStateOf<List<SmartChoice>>(emptyList()) }
    var fxSerial by remember { mutableStateOf(false) }
    var torrentsFound: List<com.torrenttv.app.data.TorrentResult> = emptyList()

    LaunchedEffect(item) {
        // Служебный адрес страницы Рутрекера (@rt:...) не должен уходить в поиск
        val rtPage = item.originalTitle.takeIf { it.startsWith("@rt:") }
        val query = rtPage?.let { item.title } ?: item.originalTitle.ifBlank { item.title }
        val altQuery = item.title.takeIf { it.isNotBlank() && !it.equals(query, true) }
        val isTv = item.type != "movie"
        com.torrenttv.app.data.AppLog.log("smart", "sources: \"$query\" isTv=$isTv")

        val online = mutableListOf<SmartChoice>()

        // ---- Торренты (со всех трекеров) ----
        coroutineScope {
            val torrents = async(Dispatchers.IO) {
                runCatching {
                    TorrentSearch.searchDetailed(
                        query = query,
                        altQuery = altQuery,
                        isTvShow = isTv,
                        imdbId = item.imdbId,
                        jackett = store.jackettConfig(),
                        privateTrackers = store.privateTrackers(),
                        excludedKeywords = store.excludedKeywords().toSet(),
                    ).first
                }.getOrDefault(emptyList())
            }

            // Торренты искались параллельно — забираем готовый результат
            torrentsFound = torrents.await()
        }
        status = "Ищу торренты…"
        var found = torrentsFound
        // Домашние сети часто режут трекеры: если пусто — один повтор через 8 сек
        if (found.isEmpty()) {
            status = "Источник не ответил, повторяю поиск…"
            kotlinx.coroutines.delay(8000)
            found = runCatching {
                TorrentSearch.searchDetailed(
                    query = query,
                    altQuery = altQuery,
                    isTvShow = isTv,
                    imdbId = item.imdbId,
                    jackett = store.jackettConfig(),
                    privateTrackers = store.privateTrackers(),
                    excludedKeywords = store.excludedKeywords().toSet(),
                ).first
            }.getOrDefault(emptyList())
            torrentsFound = found
        }
        val statsText = TorrentSearch.lastStats.joinToString(" ") { it.label() }
        com.torrenttv.app.data.AppLog.log("smart", "sources: online=0 torrents=${found.size} [$statsText]")

        // Лучшая раздача для «Играть сразу»: сиды × бонус качества.
        // Для сериалов — бонус раздачам с сезоном/сериями в названии
        // (пак «S01» или «S01E05» релевантнее полного сборника)
        fun seasonBonus(name: String): Int = when {
            Regex("S\\d{2}E\\d{2}", RegexOption.IGNORE_CASE).containsMatchIn(name) -> 2
            Regex("S\\d{2}|\\d+\\s+[сС]езон|Сезон\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(name) -> 3
            else -> 1
        }
        val best = found.maxByOrNull { r ->
            r.seeds * (when (r.resolution) {
                "1080p" -> 3
                "2160p" -> 2
                "720p" -> 2
                else -> 1
            }) * (if (isTv) seasonBonus(r.name) else 1)
        }
        val torrentChoices = found
            .sortedByDescending { it.seeds }
            .take(10)
            .map { SmartChoice.Torrent(it) }

        choices = (best?.let { listOf(SmartChoice.Instant(it)) } ?: emptyList()) +
            choices + torrentChoices

        // Сериалы: продолжение с последней серии — самая верхняя строка
        if (isTv && store.getMagnet(item) != null) {
            choices = listOf(SmartChoice.Resume) + choices
        }
        done = choices.isNotEmpty()
        failed = choices.isEmpty()
        status = if (failed) {
            "Ничего не нашлось. Источники: " + TorrentSearch.lastStats.joinToString(" ") { it.label() } +
                " — нажмите «Отправить лог», я увижу причину"
        } else {
            "Выберите, где смотреть:"
        }
    }

    fun playTorrent(r: TorrentResult) {
        store.saveMagnet(item, TorrentSearch.toMagnet(r), "")
        onPlayTorrent()
    }

    BackHandler { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(48.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(status, color = if (failed) Color(0xFFFFD54F) else Color(0xFFF5B942), fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))

            if (failed) {
                Row {
                    Button(onClick = onBack) { Text("← Назад") }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = {
                            if (!uploading) {
                                uploading = true
                                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                    val url = com.torrenttv.app.data.AppLog.sendToPastebin(store.githubToken().ifBlank { null })
                                    com.torrenttv.app.data.AppLog.log("upload", if (url.isNotBlank()) "sent -> $url" else "send FAILED")
                                    uploadUrl = url
                                    uploading = false
                                }
                            }
                        },
                        enabled = !uploading,
                    ) { Text(if (uploading) "⏳ Отправляю…" else "📤 Отправить лог") }
                }
                if (uploadUrl.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Назовите разработчику этот адрес:  " + uploadUrl.trim(),
                        color = Color(0xFF8AB4F8), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            } else if (choices.isEmpty()) {
                val alpha = rememberInfiniteTransition().animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(700),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    ),
                ).value
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().height(90.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⏳ " + status, color = Color(0xFFF5B942).copy(alpha = alpha), fontSize = 17.sp)
                }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    items(choices.size) { i ->
                        val c = choices[i]
                        androidx.tv.material3.Surface(
                            onClick = {
                                when (c) {
                                    is SmartChoice.Resume -> onPlayTorrent()
                                    is SmartChoice.Instant -> playTorrent(c.r)
                                    is SmartChoice.Torrent -> playTorrent(c.r)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                                shape = RoundedCornerShape(10.dp),
                            ),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF1F2430),
                                contentColor = Color.White,
                                focusedContainerColor = Color(0xFF31435A),
                                focusedContentColor = Color.White,
                            ),
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    c.label,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    maxLines = 2,
                                )
                                if (c.sub.isNotBlank()) {
                                    Text(
                                        c.sub,
                                        color = Color.White.copy(alpha = 0.55f),
                                        fontSize = 13.sp,
                                    )
                                }
                                // Бейджи качества (Zona-стиль): источник релиза + перевод
                                if (c is SmartChoice.Torrent || c is SmartChoice.Instant) {
                                    val r = (c as? SmartChoice.Torrent)?.r ?: (c as SmartChoice.Instant).r
                                    val badges = releaseBadges(r.name)
                                    val res = r.resolution
                                    if (badges.isNotEmpty() || res.isNotBlank()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (res.isNotBlank()) {
                                                Text(res, color = Color(0xFF7EE2A8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                            badges.forEach { (label, color) ->
                                                Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Text(
                                                "👤 ${r.seeds}",
                                                color = seedColor(r.seeds),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onBack) { Text("← Назад к карточке") }
            }
        }
    }
}
