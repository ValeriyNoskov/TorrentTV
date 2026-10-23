package com.torrenttv.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.torrenttv.app.data.OnlineService
import com.torrenttv.app.data.RezkaService
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

    data class Online(val link: OnlineService.OnlineLink, override val label: String) : SmartChoice() {
        override val sub: String = link.quality
    }

    data class Serial(val source: String) : SmartChoice() {
        override val label: String = "🌐 $source — сериал"
        override val sub: String = "выбор серий и переводов"
    }

    data class Torrent(val r: TorrentResult) : SmartChoice() {
        override val label: String = "🧲 ${r.name}"
        override val sub: String = "${r.provider} · ${r.seeds} сидов · ${r.sizeLabel()}"
    }
}

/**
 * Экран выбора источника просмотра: собирает ВСЕ доступные ссылки
 * (онлайн Filmix, HDRezka, торренты со всех трекеров) параллельно и
 * показывает единым списком — пользователь выбирает сам, что смотреть.
 */
@Composable
fun SmartPlayScreen(
    item: MediaItem,
    onBack: () -> Unit,
    /** Нашли онлайн-фильм: открыть плеер с этой ссылкой. */
    onPlayOnline: (OnlineService.OnlineLink) -> Unit,
    /** Нашли онлайн-сериал: открыть экран выбора серий. */
    onOpenOnline: () -> Unit,
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

    LaunchedEffect(item) {
        val query = item.originalTitle.ifBlank { item.title }
        val altQuery = item.title.takeIf { it.isNotBlank() && !it.equals(query, true) }
        val isTv = item.type != "movie"
        com.torrenttv.app.data.AppLog.log("smart", "sources: \"$query\" isTv=$isTv")

        val online = mutableListOf<SmartChoice>()
        var fxIsSerial = false

        // ---- Онлайн-источники параллельно ----
        coroutineScope {
            val fx = async(Dispatchers.IO) {
                runCatching {
                    val card = (OnlineService.search(store.filmixToken(), query) +
                        (altQuery?.let { OnlineService.search(store.filmixToken(), it) } ?: emptyList()))
                        .firstOrNull { m ->
                            (m.originalTitle.equals(item.originalTitle, true) || m.title.equals(item.title, true)) &&
                                (m.year.isBlank() || item.year.isBlank() || m.year == item.year)
                        } ?: return@runCatching emptyList<OnlineService.OnlineLink>()
                    if (card.isSerial) {
                        fxIsSerial = true
                        emptyList()
                    } else {
                        OnlineService.playerData(store.filmixToken(), card.filmixId)
                    }
                }.getOrDefault(emptyList())
            }

            val rz = async(Dispatchers.IO) {
                runCatching {
                    if (isTv) return@runCatching emptyList<OnlineService.OnlineLink>()
                    val card = (RezkaService.search(query) + (altQuery?.let { RezkaService.search(it) } ?: emptyList()))
                        .firstOrNull { c ->
                            (c.originalTitle.equals(item.originalTitle, true) || c.title.equals(item.title, true)) &&
                                (c.year.isBlank() || item.year.isBlank() || c.year == item.year)
                        } ?: return@runCatching emptyList<OnlineService.OnlineLink>()
                    if (card.isSerial) emptyList() else RezkaService.streams(card)
                }.getOrDefault(emptyList())
            }

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

            // Ждём онлайн и показываем; торренты подгрузятся отдельным блоком
            fx.await().sortedByDescending { it.quality }.forEach { l ->
                val who = "🌐 Filmix"
                val tr = if (l.translation.isNotBlank()) " · ${l.translation}" else ""
                val q = if (l.quality.isNotBlank()) " · ${l.quality}" else ""
                val ep = if (l.episode > 0) " · S${l.season}E${l.episode}" else ""
                online += SmartChoice.Online(l, "$who$tr$q$ep")
            }
            rz.await().sortedByDescending { it.quality }.forEach { l ->
                val tr = if (l.translation.isNotBlank()) " · ${l.translation}" else ""
                val q = if (l.quality.isNotBlank()) " · ${l.quality}" else ""
                online += SmartChoice.Online(l, "🌐 HDRezka$tr$q")
            }
            if (fxIsSerial) online += SmartChoice.Serial("Filmix")
            choices = online
            done = online.isNotEmpty()
            fxSerial = fxIsSerial
        }

        // ---- Торренты (вторым блоком, список уже можно листать) ----
        status = "Ищу торренты…"
        val found = withContext(Dispatchers.IO) {
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
        com.torrenttv.app.data.AppLog.log("smart", "sources: online=${choices.size} torrents=${found.size}")

        val torrentChoices = found
            .sortedByDescending { it.seeds }
            .take(10)
            .map { SmartChoice.Torrent(it) }

        choices = choices + torrentChoices
        done = choices.isNotEmpty()
        failed = choices.isEmpty()
        status = if (failed) {
            "Ничего не нашлось: онлайн и торренты недоступны. Нажмите «Отправить лог» — я увижу причину"
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
                                    val url = com.torrenttv.app.data.AppLog.sendToPastebin()
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
                                    is SmartChoice.Online -> onPlayOnline(c.link)
                                    is SmartChoice.Serial -> onOpenOnline()
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
