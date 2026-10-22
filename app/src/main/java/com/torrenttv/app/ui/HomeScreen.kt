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
import com.torrenttv.app.data.TmdbClient
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
    var progress by remember { mutableStateOf(store.continueWatching()) }
    var heroItem by remember { mutableStateOf<MediaItem?>(null) }
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        UpdateManager.checkForUpdate(currentVersion) { updateInfo = it }
        heroItem = withContext(Dispatchers.IO) {
            TmdbClient.trending(1).firstOrNull { it.backdropUrl.isNotBlank() && store.isAllowed(it) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (!TmdbClient.isConfigured) {
                item {
                    Text(
                        text = "⚠ Ключ TMDB не задан: каталоги пусты. Добавьте tmdbApiKey в gradle.properties.",
                        color = Color(0xFFFFD54F),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
            heroItem?.let { hero ->
                item { HeroBanner(hero, onOpenDetails) }
            }
            if (progress.isNotEmpty()) {
                item {
                    MediaRow(
                        title = "Продолжить просмотр",
                        items = progress.map { it.item },
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
                    loader = { page -> TmdbClient.trending(page) },
                    onOpen = onOpenDetails,
                    filterFn = { store.isAllowed(it) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        // Версия в углу
        Text(
            text = "v$currentVersion",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        )

        // ---------- Оверлей обновления ----------
        updateInfo?.let { info ->
            androidx.tv.material3.Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                color = Color(0xFF1F2430),
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
                    Button(onClick = {
                        UpdateManager.downloadAndInstall(
                            context,
                            info,
                            onProgress = { updateStatus = it },
                            onError = { updateStatus = it },
                        )
                    }) {
                        Text("Обновить")
                    }
                }
            }
        }
    }
}
