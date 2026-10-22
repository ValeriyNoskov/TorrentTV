package com.torrenttv.app.ui

import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.torrent.TorrentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Экран просмотра: скачивание торрента с прогрессом, затем ExoPlayer.
 * Позиция просмотра сохраняется каждые 5 секунд в «Продолжить просмотр».
 */
@Composable
fun PlayerScreen(item: MediaItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    val magnet = remember { store.getMagnet(item) }

    var status by remember { mutableStateOf(if (magnet == null) "Нет magnet-ссылки" else "Подготовка…") }
    var progress by remember { mutableStateOf(0) }
    var videoFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(magnet) {
        if (magnet != null && videoFile == null) {
            val f = withContext(Dispatchers.IO) {
                TorrentEngine.downloadLargestVideo(
                    source = magnet,
                    saveDir = File(context.filesDir, "downloads/" + item.storeKey),
                    onStatus = { status = it },
                    onProgress = { progress = it },
                )
            }
            videoFile = f
            if (f == null) status = "Не удалось загрузить видео"
        }
    }

    val file = videoFile
    if (file != null) {
        VideoPlayer(file = file, item = item, store = store, onBack = onBack)
    } else {
        // Экран загрузки
        BackHandler { onBack() }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0E1116))
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(status, color = Color(0xFFF5B942), fontSize = 18.sp)

            if (progress > 0 && progress < 100) {
                Spacer(Modifier.height(16.dp))
                // Прогресс-бар (D-pad friendly, без Material-зависимостей)
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(10.dp)
                        .background(Color(0xFF1F2430)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress / 100f)
                            .height(10.dp)
                            .background(Color(0xFFF5B942)),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("$progress%", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
            }

            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack) { Text("← Назад") }
        }
    }
}

@Composable
private fun VideoPlayer(file: File, item: MediaItem, store: LocalStore, onBack: () -> Unit) {
    val context = LocalContext.current

    val resumeSec = remember {
        store.continueWatching().firstOrNull { it.item.storeKey == item.storeKey }?.positionSec ?: 0
    }

    val player = remember {
        ExoPlayer.Builder(context)
            // Перемотка стрелками пульта: ±10 секунд
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .apply {
                setMediaItem(ExoMediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                playWhenReady = true
                if (resumeSec > 5) seekTo(resumeSec * 1000L)
            }
    }

    // Конец фильма: автозакрытие плеера (файл удалится при выходе)
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    player.seekTo(player.duration)
                    onBack()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Автосохранение позиции каждые 5 секунд
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.duration > 0) {
                store.saveProgress(
                    item,
                    positionSec = (player.currentPosition / 1000).toInt(),
                    durationSec = (player.duration / 1000).toInt(),
                )
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
            // Политика хранения: удаляем скачанное после просмотра
            runCatching { file.parentFile?.deleteRecursively() }
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    // Контролы скрываются через 10 секунд бездействия пульта
                    controllerShowTimeoutMs = 10_000
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
