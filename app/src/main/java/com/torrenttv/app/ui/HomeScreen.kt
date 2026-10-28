package com.torrenttv.app.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.torrenttv.app.BuildConfig
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.MediaApi
import com.torrenttv.app.update.UpdateManager

/** Вкладка «Главная»: тренды первыми, затем новинки фильмов/сериалов/шоу с переходом в каталог. */
@Composable
fun HomeScreen(
    currentVersion: String,
    onOpenDetails: (MediaItem) -> Unit,
    onOpenCatalog: (String) -> Unit = {},
    onOpenTv: () -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    // Возврат из ТВ: перечитываем избранное (звёздочки могли поменять)
    var favTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { favTick++ }

    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf("") }

    // Обновление качается в фоне сразу после обнаружения —
    // установка по кнопке «Обновить» будет мгновенной
    LaunchedEffect(updateInfo) {
        updateInfo?.let { UpdateManager.startBackgroundDownload(context, it) }
    }

    // Проверка обновлений при запуске (вернули после рефакторинга v0.7.67 —
    // тогда автопроверка выпала из главной, плашка перестала появляться)
    LaunchedEffect(Unit) {
        // Подрезка кеша при старте: раньше лимит применялся только перед новым
        // стримом — без запуска раздач кеш рос неограниченно (жалоба: 12 ГБ)
        withContext(Dispatchers.IO) {
            runCatching {
                val root = File(context.filesDir, "downloads")
                val size = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                if (size > 0) {
                    com.torrenttv.app.data.AppLog.log(
                        "cache",
                        "кеш при старте: ${size / 1024 / 1024} МБ",
                    )
                    com.torrenttv.app.torrent.TorrentCache.enforceLimit(
                        rootDir = root,
                        retentionMs = store.retentionMs(),
                    )
                    val after = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    if (after != size) {
                        com.torrenttv.app.data.AppLog.log(
                            "cache",
                            "кеш после подрезки: ${after / 1024 / 1024} МБ (освобождено ${(size - after) / 1024 / 1024} МБ)",
                        )
                    }
                }
            }
        }
        UpdateManager.checkForUpdate(
            currentVersion,
            UpdateManager.ghToken(context),
            { updateInfo = it },
            { err -> com.torrenttv.app.data.AppLog.log("update", "автопроверка при запуске FAIL: $err") },
            source = store.updateSource(),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (!MediaApi.isConfigured) {
                item {
                    Text(
                        text = "⚠ Метаданные не настроены — задайте ключ базы фильмов в настройках.",
                        color = Color(0xFFBFE3F7),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
            // Продолжить смотреть — первым (как в стримингах):
            // один OK — и ты на месте остановки. «🎲 Случайный фильм» —
            // маленькой кнопкой в заголовке, а не первой строкой экрана
            item {
                var randomTick by remember { mutableStateOf(0) }
                LaunchedEffect(randomTick) {
                    if (randomTick == 0) return@LaunchedEffect
                    val pick = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        MediaApi.randomItem { store.isAllowed(it) }
                    }
                    if (pick != null) {
                        com.torrenttv.app.data.AppLog.log("home", "случайный фильм: ${pick.title}")
                        onOpenDetails(pick)
                    }
                }
                val continueEntries = remember(favTick) { store.continueWatching() }
                if (continueEntries.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "▶️ Продолжить смотреть",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(12.dp))
                        AppButton(
                            onClick = {
                                com.torrenttv.app.data.AppLog.log("ui", "нажал: «Случайный фильм»")
                                randomTick++
                            },
                        ) { Text("🎲") }
                    }
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 32.dp, vertical = 6.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        items(continueEntries.size) { i ->
                            val e = continueEntries[i]
                            Column {
                                PosterCard(e.item) { onOpenDetails(e.item) }
                                // Прогресс-полоска под постером — как в «Продолжить»
                                if (e.durationSec > 0) {
                                    Box(
                                        Modifier
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .background(Color(0xFF2A3648)),
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth(
                                                    (e.positionSec.toFloat() / e.durationSec).coerceIn(0f, 1f),
                                                )
                                                .height(4.dp)
                                                .background(Color(0xFFA9D7F5)),
                                        )
                                    }
                                    Text(
                                        text = buildString {
                                            if (e.episodeLabel.isNotBlank()) append(e.episodeLabel + " · ")
                                            val left = (e.durationSec - e.positionSec) / 60
                                            if (left > 0) append("осталось ~$left мин") else append("почти досмотрено")
                                        },
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Живое ТВ — сразу с главной: каналы открываются одним нажатием.
            // Каналы берём из встроенного плейлиста (без сети), полный список — в разделе «📡 ТВ»
            item {
                // На Главной — только ИЗБРАННЫЕ каналы: их отмечают звёздочкой
                // в разделе «📡 ТВ». Пусто — блок не показываем, чтобы не
                // засорять главный экран случайными каналами.
                val favsNow = remember(favTick) { store.tvFavorites() }
                val tvPreview = remember(favsNow) {
                    val all = com.torrenttv.app.data.Iptv.builtIn(context)
                    all.filter { favsNow.contains(it.url) }
                }
                if (tvPreview.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "📡 Избранные каналы",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.padding(horizontal = 8.dp))
                        AppButton(onClick = onOpenTv) { Text("Все каналы →") }
                    }
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 32.dp, vertical = 6.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        items(tvPreview.size) { i ->
                            val ch = tvPreview[i]
                            androidx.tv.material3.Surface(
                                onClick = onOpenTv,
                                shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                    containerColor = Color(0xFF1A1F29),
                                    contentColor = Color.White,
                                    focusedContainerColor = Color(0xFF2B3648),
                                    focusedContentColor = Color.White,
                                ),
                                modifier = Modifier.width(150.dp),
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    Text(
                                        "● ${ch.group}",
                                        color = Color(0xFFA9D7F5),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                    )
                                    Text(
                                        ch.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Три полки вместо шести (v0.9.30): сериалы/шоу легко доступны
            // из каталога по клику на заголовок — Главная не перегружена
            item {
                PagedMediaRow(
                    title = "🇷🇺 Русские фильмы",
                    loader = { page -> MediaApi.russianMovies(page) },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) },
                    onTitleClick = { onOpenCatalog("movies") },
                )
            }
            // Тренды — витрина мира
            item {
                PagedMediaRow(
                    title = "🔥 Сейчас в тренде",
                    loader = { page -> MediaApi.trending(page) },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) },
                )
            }
            // Новинки с переходом в каталог (клик по заголовку)
            item {
                PagedMediaRow(
                    title = "🆕 Новинки фильмов",
                    loader = { page -> MediaApi.newMovies(page) },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) },
                    onTitleClick = { onOpenCatalog("movies") },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        // Версия в углу: скрыта при активной плашке обновления (не пересекаются)
        if (updateInfo == null) {
            Text(
                text = "v$currentVersion",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            )
        }

        // ---------- Оверлей обновления ----------
        updateInfo?.let { info ->
            // Фокус сразу на кнопке «Обновить»: пультом достаточно нажать OK.
            // Ретраи: узел не готов мгновенно; runCatching только внутри
            val updateFocus = remember { FocusRequester() }
            LaunchedEffect(info) {
                repeat(5) {
                    runCatching { updateFocus.requestFocus() }
                    kotlinx.coroutines.delay(60)
                }
            }
            androidx.tv.material3.Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                colors = androidx.tv.material3.SurfaceDefaults.colors(
                    containerColor = Color(0xFF1F2430),
                    contentColor = Color.White,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp, 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Доступно обновление v${info.version}",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (updateStatus.isNotBlank()) {
                            Text(updateStatus, color = Color(0xFFBFE3F7), fontSize = 13.sp)
                        }
                    }
                    AppButton(
                        onClick = {
                            UpdateManager.downloadAndInstall(
                                context,
                                info,
                                onProgress = { updateStatus = it },
                                onError = { updateStatus = it },
                            )
                        },
                        modifier = Modifier.focusRequester(updateFocus),
                    ) {
                        Text("Обновить")
                    }
                }
            }
        }
    }
}
