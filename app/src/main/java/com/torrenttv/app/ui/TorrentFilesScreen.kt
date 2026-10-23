package com.torrenttv.app.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.TorrentHttp
import com.torrenttv.app.torrent.TorrentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Экран списка раздач по запросу: все раздачи, отсортированы «лучшая
 * сверху» (качество → сиды → пиры), полная инфа в каждой строке.
 */
@Composable
fun TorrentResultsScreen(
    item: MediaItem,
    query: String,
    results: List<com.torrenttv.app.data.TorrentResult>,
    poster: String,
    /** Постеры раздач со страниц Рутрекера: ключ — название раздачи. */
    posterMap: Map<String, String> = emptyMap(),
    onPickTorrent: (com.torrenttv.app.data.TorrentResult) -> Unit,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.activity.compose.BackHandler { onBack() }

    // Лучшая раздача сверху: максимум сидов → пиров → качество
    // (быстрее всего стартует; самая большая по размеру ≠ лучшая)
    val qOrder = mapOf("2160p" to 4, "1080p" to 3, "720p" to 2, "SD" to 1)
    val sorted = remember(results) {
        results.sortedWith(
            compareByDescending<com.torrenttv.app.data.TorrentResult> { it.seeds }
                .thenByDescending { it.peers }
                .thenByDescending { qOrder[it.resolution] ?: 0 },
        )
    }
    val best = sorted.firstOrNull { it.seeds > 0 }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(28.dp),
    ) {
        Text("🧲 Раздачи: $query", color = Color(0xFFF5B942), fontSize = 15.sp)
        Text(
            item.title,
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            "Всего раздач: ${sorted.size} · лучшая сверху: ${best?.let { "${it.resolution.ifBlank { "auto" }}, ${it.seeds} сидов" } ?: "—"}",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(sorted) { i, r ->
                Surface(
                    onClick = { onPickTorrent(r) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF161C26),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFF5B942),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val rowPoster = posterMap[r.name]?.takeIf { it.isNotBlank() } ?: poster
                        if (rowPoster.isNotBlank()) {
                            coil.compose.AsyncImage(
                                model = rowPoster,
                                contentDescription = null,
                                modifier = Modifier.width(64.dp).height(92.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (i == 0) {
                                    Text(
                                        "★ ЛУЧШАЯ  ",
                                        color = Color(0xFFF5B942),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    r.resolution.ifBlank { "auto" },
                                    color = Color(0xFF8BD17C),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                r.name,
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                "🧲 ${r.seeds} сидов · 👥 ${r.peers} пиров · ${r.sizeLabel()} · ${r.provider}",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onBack) { Text("← Назад") }
    }
}

/**
 * Экран файлов раздачи: показывает все видеофайлы (серии) с размерами
 * ДО запуска плеера — видно, сколько серий в раздаче. Клик — в плеер.
 */@Composable
fun TorrentFilesScreen(
    item: MediaItem,
    magnet: String,
    onPick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("Получаю состав раздачи…") }
    var loading by remember { mutableStateOf(true) }
    // (индекс файла, имя, размер)
    var files by remember { mutableStateOf<List<Triple<Int, String, Long>>>(emptyList()) }
    var magnetTitle by remember { mutableStateOf(item.title) }

    androidx.activity.compose.BackHandler { onBack() }

    LaunchedEffect(magnet) {
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val saveDir = File(context.filesDir, "downloads/" + item.storeKey)
                val ti = TorrentEngine.fetchMetadata(magnet, saveDir) { s -> status = s }
                    ?: return@runCatching emptyList<Triple<Int, String, Long>>()
                if (magnetTitle.isBlank() || magnetTitle == "—") {
                    ti.name().let { if (it.isNotBlank()) magnetTitle = it }
                }
                val fs = ti.files()
                (0 until fs.numFiles()).mapNotNull { i ->
                    val name = fs.fileName(i)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in TorrentEngine.VIDEO_EXT) Triple(i, name, fs.fileSize(i))
                    else null
                }
            }.getOrDefault(emptyList())
        }
        files = res
        loading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(28.dp),
    ) {
        Text("🧲 Раздача", color = Color(0xFFF5B942), fontSize = 15.sp)
        Text(
            item.title,
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
        )
        Text(
            magnetTitle,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(14.dp))

        if (loading) {
            Text(status, color = Color(0xFFFFD54F), fontSize = 15.sp)
            Text(
                "Состав раздачи берётся у пиров — обычно 10–30 секунд",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (files.isEmpty()) {
            Text(
                if (status.startsWith("Метаданные")) status else "Видеофайлов в раздаче не найдено",
                color = Color(0xFFFFD54F),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack) { Text("← Назад") }
        } else {
            Text(
                "Файлов: ${files.size} — выберите серию или фильм:",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(files) { _, (idx, name, size) ->
                    Surface(
                        onClick = { onPick(idx) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF161C26),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFF5B942),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "▶",
                                color = Color(0xFFF5B942),
                                fontSize = 14.sp,
                                modifier = Modifier.width(24.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name.substringAfterLast('/'),
                                    fontSize = 15.sp,
                                    maxLines = 2,
                                )
                            }
                            Text(
                                android.text.format.Formatter.formatShortFileSize(context, size),
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onBack) { Text("← Назад") }
        }
    }
}
