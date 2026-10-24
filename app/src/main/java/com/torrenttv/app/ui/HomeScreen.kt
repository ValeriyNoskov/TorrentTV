package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Вкладка «Главная»: hero-баннер, «Продолжить», «Избранное», тренды. */
@Composable
fun HomeScreen(
    currentVersion: String,
    onOpenDetails: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var favorites by remember { mutableStateOf(store.favorites()) }
    var watchLater by remember { mutableStateOf(store.watchLater()) }
    var progress by remember { mutableStateOf(store.continueWatching()) }
    var heroItem by remember { mutableStateOf<MediaItem?>(null) }
    var newReleases by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf("") }

    // Обновление качается в фоне сразу после обнаружения —
    // установка по кнопке «Обновить» будет мгновенной
    LaunchedEffect(updateInfo) {
        updateInfo?.let { UpdateManager.startBackgroundDownload(context, it) }
    }

    LaunchedEffect(Unit) {
        UpdateManager.checkForUpdate(currentVersion) { updateInfo = it }
        heroItem = withContext(Dispatchers.IO) {
            // Hero — новинка месяца с широким кадром; фолбэк — тренды
            val fresh = MediaApi.newReleases().filter { store.isAllowed(it) }
            newReleases = fresh.take(20)
            fresh.firstOrNull { it.backdropUrl.isNotBlank() } ?: fresh.firstOrNull()
                ?: MediaApi.trending(1).filter { store.isAllowed(it) }.firstOrNull()
        }
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
                        text = "⚠ Метаданные не настроены. Получите бесплатный ключ на kinopoiskapiunofficial.tech и укажите kpApiKey= в gradle.properties.",
                        color = Color(0xFFFFD54F),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
            heroItem?.let { hero ->
                item { HeroBanner(hero, onOpenDetails) }
            }
            if (newReleases.isNotEmpty()) {
                item {
                    MediaRow(
                        title = "🆕 Новинки",
                        items = newReleases,
                        onClick = onOpenDetails,
                    )
                }
            }
            if (progress.isNotEmpty()) {
                item {
                    // Карточки с прогресс-барами: досмотрено, серия, «осталось N мин»
                    ContinueRow(
                        title = "Продолжить просмотр",
                        entries = progress.distinctBy { it.sourceKey },
                        onClick = onOpenDetails,
                        onRemove = { e ->
                            store.clearWatched(e.item)
                            runCatching {
                                java.io.File(context.filesDir, "downloads/" + e.item.storeKey).deleteRecursively()
                            }
                            progress = store.continueWatching()
                        },
                    )
                }
            }
            item {
                MediaRow(
                    title = "Смотреть позже",
                    items = watchLater,
                    onClick = onOpenDetails,
                    emptyText = "Пока пусто — отмечайте фильмы кнопкой «＋ Смотреть позже» в карточке",
                )
            }
            // Лента из статусов «Буду смотреть» (кнопка 🔖 в карточке)
            if (store.itemsWithStatus("want").isNotEmpty()) {
                item {
                    MediaRow(
                        title = "📋 Буду смотреть",
                        items = store.itemsWithStatus("want"),
                        onClick = onOpenDetails,
                    )
                }
            }
            item {
                MediaRow(
                    title = "Избранное",
                    items = favorites,
                    onClick = onOpenDetails,
                    emptyText = "Пока пусто — добавьте фильмы из карточки (☆)",
                )
            }
            item {
                PagedMediaRow(
                    title = "🔥 Сейчас в тренде",
                    loader = { page -> MediaApi.trending(page) },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) },
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
            // Фокус сразу на кнопке «Обновить»: пультом достаточно нажать OK
            val updateFocus = remember { FocusRequester() }
            LaunchedEffect(info) { updateFocus.requestFocus() }
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
                            Text(updateStatus, color = Color(0xFFFFD54F), fontSize = 13.sp)
                        }
                    }
                    Button(
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
