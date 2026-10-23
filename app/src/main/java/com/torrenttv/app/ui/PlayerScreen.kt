package com.torrenttv.app.ui

import android.view.KeyEvent
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
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.torrent.TorrentDataSource
import com.torrenttv.app.torrent.TorrentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Экран просмотра: стриминг торрента по кускам (последовательная загрузка),
 * буферизация первых мегабайт, затем ExoPlayer поверх недокачанного файла.
 * Позиция просмотра сохраняется каждые 5 секунд в «Продолжить просмотр».
 */
@Composable
fun PlayerScreen(item: MediaItem, episode: com.torrenttv.app.data.Episode? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    // Источник эпизода хранится с суффиксом (":s01e05") — у каждой серии свой
    val sourceSuffix = episode?.sourceSuffix ?: ""
    val sourceKey = item.storeKey + sourceSuffix
    val magnet = remember(episode) { store.getMagnet(item, sourceSuffix) }

    var status by remember { mutableStateOf(if (magnet == null) "Нет magnet-ссылки" else "Подготовка…") }
    var bufferedPct by remember { mutableStateOf(0) }
    var netInfo by remember { mutableStateOf("") }
    var stream by remember { mutableStateOf<TorrentEngine.StreamSession?>(null) }

    LaunchedEffect(magnet) {
        if (magnet == null) return@LaunchedEffect
        val st = withContext(Dispatchers.IO) {
            TorrentEngine.startStream(
                source = magnet,
                saveDir = File(context.filesDir, "downloads/" + item.storeKey),
                onStatus = { status = it },
                // В пачке сезона включаем файл нужной серии
                fileMatcher = episode?.let { ep ->
                    { name: String -> com.torrenttv.app.data.Episode.fileMatches(name, ep.season, ep.episode) }
                },
            )
        }
        if (st == null) {
            status = "Не удалось начать стриминг"
            return@LaunchedEffect
        }
        stream = st

        // Перемотка в середину: поднимаем приоритет кусков вокруг позиции продолжения
        val watching = withContext(Dispatchers.IO) {
            store.continueWatching().firstOrNull { it.sourceKey == sourceKey }
        }
        val resumeSec = watching?.positionSec ?: 0
        val durationSec = watching?.durationSec ?: 0
        if (resumeSec > 30 && durationSec > 0) {
            withContext(Dispatchers.IO) {
                st.boostPiecesAround(st.fileOffset + st.fileSize * resumeSec / durationSec)
            }
        }

        // Порог старта: 4 МБ или ~3% файла (чтобы плеер прочитал заголовки/индекс)
        val threshold = maxOf(4L * 1024 * 1024, st.fileSize / 33)
        // Диагностика и таймаут: если за 3 минуты не скачалось — говорим прямо,
        // вместо вечного «Буферизация»
        val deadline = System.currentTimeMillis() + 180_000
        while (st.headBytes() < threshold) {
            bufferedPct = ((st.headBytes() * 100) / threshold).toInt().coerceIn(0, 100)
            val mb = st.headBytes() / 1024 / 1024
            val speed = st.speedBps() / 1024
            netInfo = if (st.peers() > 0) {
                "Пиры: ${st.peers()} · Скачано: $mb МБ · ${speed} КБ/с"
            } else {
                "Пиры не найдены — трекеры/DHT недоступны из этой сети"
            }
            if (System.currentTimeMillis() > deadline) {
                status = "Раздача не отвечает: пиры недоступны из вашей сети. " +
                    "Попробуйте другую раздачу, VPN или Jackett"
                return@LaunchedEffect
            }
            delay(500)
        }
        bufferedPct = 100
    }

    val st = stream
    if (st != null && bufferedPct >= 100) {
        VideoPlayer(stream = st, item = item, episode = episode, store = store, onBack = onBack)
    } else {
        // Экран буферизации
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
            if (episode != null) {
                Spacer(Modifier.height(6.dp))
                Text(episode.code, color = Color(0xFFF5B942), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Text(status, color = Color(0xFFF5B942), fontSize = 18.sp)
            if (netInfo.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(netInfo, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            }

            if (bufferedPct > 0 && bufferedPct < 100) {
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
                            .fillMaxWidth(bufferedPct / 100f)
                            .height(10.dp)
                            .background(Color(0xFFF5B942)),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Буферизация: $bufferedPct%", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
            }

            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack) { Text("← Назад") }
        }
    }
}

@Composable
private fun VideoPlayer(
    stream: TorrentEngine.StreamSession,
    item: MediaItem,
    episode: com.torrenttv.app.data.Episode?,
    store: LocalStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sourceKey = item.storeKey + (episode?.sourceSuffix ?: "")

    val resumeSec = remember {
        store.continueWatching().firstOrNull { it.sourceKey == sourceKey }?.positionSec ?: 0
    }

    val player = remember(stream) {
        ExoPlayer.Builder(context)
            // Перемотка стрелками пульта: ±10 секунд
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .apply {
                // Стриминг: ExoPlayer читает недокачанный файл через TorrentDataSource,
                // который дожидается нужных кусков последовательной загрузки
                val dataSourceFactory = DataSource.Factory { TorrentDataSource(stream) }
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(ExoMediaItem.fromUri(Uri.parse("torrent://stream/" + item.storeKey)))
                setMediaSource(mediaSource)
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
                    sourceKey = sourceKey,
                    episodeLabel = episode?.code ?: "",
                )
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
            // Политика хранения: удаляем скачанное после просмотра и гасим торрент
            stream.stop()
            runCatching { stream.saveDir.deleteRecursively() }
        }
    }

    BackHandler { onBack() }

    var showTrackMenu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    // Контролы скрываются через 10 секунд бездействия пульта
                    controllerShowTimeoutMs = 10_000
                    // MENU пульта — оверлей выбора аудиодорожек/субтитров
                    // (встроенный контроллер Media3 аудио выбирать не умеет)
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU) {
                            showTrackMenu = !showTrackMenu
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (showTrackMenu) {
            TrackMenuOverlay(player = player, onDismiss = { showTrackMenu = false })
        }
    }
}
