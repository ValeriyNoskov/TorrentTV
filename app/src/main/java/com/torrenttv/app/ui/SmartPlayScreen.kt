package com.torrenttv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.torrenttv.app.data.TorrentResult
import com.torrenttv.app.data.TorrentSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Умный просмотр в духе Zona: одна кнопка «Смотреть».
 * Приложение само: 1) ищет онлайн (Filmix + HDRezka) и играет лучшую ссылку;
 * 2) если онлайн пуст — подбирает лучшую раздачу и включает стрим.
 * Пользователь видит, что происходит, и может вернуться.
 */
@Composable
fun SmartPlayScreen(
    item: MediaItem,
    onBack: () -> Unit,
    /** Нашли онлайн-фильм: открыть плеер с этой ссылкой. */
    onPlayOnline: (OnlineService.OnlineLink) -> Unit,
    /** Нашли онлайн-сериал: открыть экран выбора серий. */
    onOpenOnline: () -> Unit,
    /** Онлайн недоступен: торрент подобран и magnet сохранён — открыть плеер. */
    onPlayTorrent: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalStore(context) }

    var status by remember { mutableStateOf("Ищу онлайн (Filmix · HDRezka)…") }
    var detail by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(item) {
        if (done) return@LaunchedEffect
        val query = item.originalTitle.ifBlank { item.title }
        com.torrenttv.app.data.AppLog.log("smart", "start: \"${item.title}\" ($query, ${item.type}, imdb=${item.imdbId})")

        // ---- Шаг 1: онлайн (HDRezka) ----
        val cards = withContext(Dispatchers.IO) {
            coroutineScope {
                val f = async {
                    runCatching { RezkaService.search(query) }.getOrDefault(emptyList())
                }
                val r = async {
                    runCatching { RezkaService.search(item.title) }.getOrDefault(emptyList())
                }
                f.await() + r.await()
            }
        }

        val fxCard = withContext(Dispatchers.IO) {
            runCatching {
                val a = OnlineService.search(store.filmixToken(), query)
                val b = if (item.title.isNotBlank() && !item.title.equals(query, true))
                    OnlineService.search(store.filmixToken(), item.title) else emptyList()
                (a + b).firstOrNull { m ->
                    (m.originalTitle.equals(item.originalTitle, true) || m.title.equals(item.title, true)) &&
                        (m.year.isBlank() || item.year.isBlank() || m.year == item.year)
                }
            }.getOrNull()
        }

        if (fxCard != null) {
            com.torrenttv.app.data.AppLog.log("smart", "filmix: card ${fxCard.filmixId} \"${fxCard.title}\"")
            status = "Нашёл в Filmix — получаю ссылки…"
            val links = withContext(Dispatchers.IO) {
                runCatching { OnlineService.playerData(store.filmixToken(), fxCard.filmixId) }.getOrDefault(emptyList())
            }
            com.torrenttv.app.data.AppLog.log("smart", "filmix: links=${links.size}")
            if (links.isNotEmpty()) {
                done = true
                onPlayOnline(OnlineService.bestOf(links) ?: links.first())
                return@LaunchedEffect
            }
            detail = "Filmix: ссылок нет"
        }

        // ---- Шаг 1b: HDRezka ----
        val rzCard = cards.firstOrNull { c ->
            (c.originalTitle.equals(item.originalTitle, true) || c.title.equals(item.title, true)) &&
                (c.year.isBlank() || item.year.isBlank() || c.year == item.year)
        } ?: cards.firstOrNull()

        if (rzCard != null) {
            com.torrenttv.app.data.AppLog.log("smart", "rezka: card \"${rzCard.title}\" serial=${rzCard.isSerial}")
            status = "Пробую HDRezka…"
            if (!rzCard.isSerial) {
                val links = withContext(Dispatchers.IO) {
                    runCatching { RezkaService.streams(rzCard) }.getOrDefault(emptyList())
                }
                com.torrenttv.app.data.AppLog.log("smart", "rezka: links=${links.size}")
                if (links.isNotEmpty()) {
                    done = true
                    onPlayOnline(OnlineService.bestOf(links) ?: links.first())
                    return@LaunchedEffect
                }
                detail = (if (detail.isNotBlank()) "$detail; " else "") + "HDRezka: ссылок нет"
            } else {
                // сериал: серию выбрать автоматически нельзя — экран выбора серий
                done = true
                onOpenOnline()
                return@LaunchedEffect
            }
        }

        // ---- Шаг 2: торрент ----
        status = "Онлайн недоступен — подбираю торрент…"
        val (found, stats) = withContext(Dispatchers.IO) {
            runCatching {
                TorrentSearch.searchDetailed(
                    query = query,
                    altQuery = item.title.takeIf { it.isNotBlank() && !it.equals(query, true) },
                    isTvShow = item.type != "movie",
                    imdbId = item.imdbId,
                    jackett = store.jackettConfig(),
                    privateTrackers = store.privateTrackers(),
                    excludedKeywords = store.excludedKeywords().toSet(),
                )
            }.getOrDefault(emptyList<TorrentResult>() to emptyList())
        }
        detail = "Онлайн: ${if (fxCard == null && cards.isEmpty()) "не найдено" else "нет ссылок"} · " +
            stats.joinToString(" · ") { it.label() }
        com.torrenttv.app.data.AppLog.log("smart", "torrent: found=${found.size} $detail")

        if (found.isEmpty()) {
            status = "Ничего не нашлось: онлайн и торренты недоступны. " +
                "Проверьте сеть или выберите раздачу вручную"
            return@LaunchedEffect
        }
        val best = bestTorrent(found)
        com.torrenttv.app.data.AppLog.log("smart", "torrent best: \"${best.name}\" seeds=${best.seeds}")
        status = "Раздача: ${best.name.take(60)} (${best.seeds} сидов) — запускаю…"
        store.saveMagnet(item, TorrentSearch.toMagnet(best), "")
        done = true
        onPlayTorrent()
    }

    BackHandler { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(48.dp),
    ) {
        Column(Modifier.align(Alignment.Center)) {
            Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(status, color = Color(0xFFF5B942), fontSize = 18.sp)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(detail, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack) { Text("← Отмена") }
        }
    }
}
