package com.torrenttv.app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Прямая ссылка на поток (сейчас — только HTTP-стрим TorrServe).
 * Раньше здесь жили и онлайн-источники Filmix/HDRezka — вырезаны.
 */
data class OnlineLink(
    val url: String,
    val label: String,
    val season: Int = 0,
    val episode: Int = 0,
    val quality: String = "",
    val translation: String = "",
    /** Referer для CDN-плеера (нужен для онлайн-источников, TorrServe — без). */
    val referer: String = "",
)

@Composable
fun OnlinePlayerScreen(link: OnlineLink, item: MediaItem, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { com.torrenttv.app.data.LocalStore(context) }
    // Ключ источника: своя позиция на каждую ссылку (и серию) онлайн-просмотра
    val onlineKey = item.storeKey + ":online" +
        (if (link.season > 0) ":s%02de%02d".format(link.season, link.episode) else "")
    // С какой секунды продолжать («Продолжить просмотр»)
    val resumeSec = remember {
        store.continueWatching().firstOrNull { it.sourceKey == onlineKey }?.positionSec ?: 0
    }
    var showQuality by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var maxQuality by remember { mutableStateOf<Int?>(null) } // null = авто
    var seekInfo by remember { mutableStateOf("") }           // «⏩ +10 сек» при перемотке пультом
    // Панель контролов при действии пульта (счётчик действий — триггер показа)
    var panelTick by remember { mutableStateOf(0) }
    var showPanel by remember { mutableStateOf(false) }
    val gearFocus = remember { FocusRequester() }
    LaunchedEffect(panelTick) {
        if (panelTick > 0 && !showSettings && !showQuality) {
            showPanel = true
            kotlinx.coroutines.delay(4000)
            if (!showSettings && !showQuality) showPanel = false
        }
    }
    // При открытой панели фокус сразу на шестерёнке — пульт её находит
    LaunchedEffect(showPanel) { if (showPanel) gearFocus.requestFocus() }

    val player = remember(link.url) {
        // CDN проверяет Referer: у Filmix — filmix.ac, у HDRezka — её зеркало.
        // TorrServe (label="torrserve") — локальный стрим, Referer не нужен.
        val referer = if (link.label == "torrserve") "" else link.referer.ifBlank { "https://filmix.ac/" }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .apply { if (referer.isNotBlank()) setDefaultRequestProperties(mapOf("Referer" to referer)) }
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(if (link.label == "torrserve") 60_000 else 15_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                // Русский звук по умолчанию (дубляж), если дорожки размечены
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage("rus")
                    .build()
                // .m3u8 → HLS-источник, остальное — прогрессивный стрим
                setMediaItem(
                    ExoMediaItem.Builder()
                        .setUri(link.url)
                        .setMimeType(
                            if (link.url.contains(".m3u8", ignoreCase = true))
                                androidx.media3.common.MimeTypes.APPLICATION_M3U8
                            else null
                        )
                        .build()
                )
                prepare()
                // Продолжаем с сохранённого места (если смотрели раньше)
                if (resumeSec > 30) {
                    seekTo(resumeSec * 1000L)
                    seekInfo = "▶ Продолжаем с %d:%02d".format(resumeSec / 60, resumeSec % 60)
                }
                playWhenReady = true
            }
    }

    // Запоминание позиции: каждые 5 секунд + при выходе из плеера
    LaunchedEffect(player) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            val pos = (player.currentPosition / 1000).toInt()
            val dur = (player.duration / 1000).toInt()
            if (pos > 10 && dur > 0) {
                // Досмотрено до конца — удаляем из «Продолжить просмотр»
                if (pos >= dur * 95 / 100) store.removeProgress(item)
                else store.saveProgress(item, pos, dur, onlineKey)
            }
        }
    }
    DisposableEffect(player) {
        onDispose {
            val pos = (player.currentPosition / 1000).toInt()
            val dur = (player.duration / 1000).toInt()
            if (pos > 10 && dur > 0 && pos < dur * 95 / 100) {
                store.saveProgress(item, pos, dur, onlineKey)
            }
            player.release()
        }
    }

    // Ограничение качества видео (для HLS-мастера с вариантами)
    LaunchedEffect(player, maxQuality) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, maxQuality ?: Int.MAX_VALUE)
            .build()
    }

    BackHandler(enabled = showQuality) { showQuality = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showPanel && !showSettings && !showQuality) { showPanel = false }
    BackHandler { onBack() }

    // Галочка «Всегда внешний плеер»: сразу открываем во внешнем плеере и выходим
    val storePrefs = remember { com.torrenttv.app.data.LocalStore(context) }
    var extMsg by remember { mutableStateOf("") }
    fun openExternal(auto: Boolean = false) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(
                android.net.Uri.parse(link.url),
                if (link.url.contains(".m3u8", true)) "application/x-mpegURL" else "video/*",
            )
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) {
            extMsg = "Внешний плеер не найден — установите VLC или MX Player, либо выключите «Всегда внешний плеер» в настройках"
        } else if (auto) {
            onBack()
        }
    }
    LaunchedEffect(Unit) {
        if (storePrefs.useExternalPlayer()) openExternal(auto = true)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    // Нативные контролы выключены: перехватывают пульт и мешают
                    // дойти до нашей панели (⚙ Качество). Панель — своя, Compose.
                    useController = false
                    // Пульт: влево/вправо — перемотка ±10 сек (с индикатором),
                    // ОК — пауза, MENU — шестерёнка
                    setOnKeyListener { _, keyCode, event ->
                        // Влево/вправо — перемотка ±10 сек
                        if (event.action == android.view.KeyEvent.ACTION_UP &&
                            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                                keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                        ) {
                            val delta = if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                            val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                            player.seekTo((player.currentPosition + delta).coerceIn(0L, dur))
                            seekInfo = if (delta > 0) "⏩ +10 сек" else "⏪ −10 сек"
                            panelTick++
                            true
                        } else if (event.action == android.view.KeyEvent.ACTION_UP &&
                            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                                keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        ) {
                            // ОК на пульте — пауза/продолжить
                            if (player.isPlaying) player.pause() else player.play()
                            seekInfo = if (player.isPlaying) "▶ Продолжаем" else "⏸ Пауза"
                            panelTick++
                            true
                        } else if (event.action == android.view.KeyEvent.ACTION_UP &&
                            keyCode == android.view.KeyEvent.KEYCODE_MENU
                        ) {
                            showSettings = !showSettings
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            "$title · ${link.label}",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )

        // Индикатор перемотки пультом: скрыт при открытых меню (не наезжает)
        if (seekInfo.isNotBlank() && !showQuality && !showSettings) {
            LaunchedEffect(seekInfo) {
                kotlinx.coroutines.delay(900)
                seekInfo = ""
            }
            Text(
                seekInfo,
                color = Color(0xFFF5B942),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE0151B26), RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        // Панель контролов «как MX»: появляется при действии пульта,
        // исчезает через 4 сек; шестерёнка ⚙ — качество и внешний плеер
        if (showPanel || showSettings || showQuality) {
            var posText by remember { mutableStateOf("") }
            LaunchedEffect(panelTick) {
                while (true) {
                    val p = player.currentPosition / 1000
                    val d = (player.duration / 1000).takeIf { it > 0 }
                    posText = "%d:%02d / %s".format(p / 60, p % 60, d?.let { "${it / 60}:${"%02d".format(it % 60)}" } ?: "—")
                    kotlinx.coroutines.delay(500)
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(posText, color = Color.White, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerChip("⏸") { if (player.isPlaying) player.pause() else player.play() }
                    Spacer(Modifier.width(8.dp))
                    PlayerChip("⏪10с") {
                        val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                        player.seekTo((player.currentPosition - 10_000L).coerceIn(0L, dur))
                        seekInfo = "⏪ −10 сек"
                        panelTick++
                    }
                    Spacer(Modifier.width(8.dp))
                    PlayerChip("⏩10с") {
                        val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                        player.seekTo((player.currentPosition + 10_000L).coerceIn(0L, dur))
                        seekInfo = "⏩ +10 сек"
                        panelTick++
                    }
                    Spacer(Modifier.width(8.dp))
                    PlayerChip(if (showSettings) "✕" else "⚙", focusRequester = gearFocus) { showSettings = !showSettings }
                }
            }
        }

        // Меню шестерёнки — раскрывается вверх от кнопки
        if (showSettings) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 76.dp, end = 24.dp)
                    .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                PlayerChip("Качество: ${maxQuality?.let { "${it}p" } ?: "авто"}") {
                    showSettings = false
                    showQuality = true
                }
                Spacer(Modifier.height(6.dp))
                PlayerChip("⤴ Внешний плеер") {
                    showSettings = false
                    openExternal()
                }
            }
        }

        if (extMsg.isNotBlank() && !showQuality && !showSettings) {
            Text(
                extMsg,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xF0151B26), RoundedCornerShape(12.dp))
                    .padding(16.dp),
            )
        }

        if (showQuality) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Качество видео",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                val options = listOf(null to "Авто (по каналу)", 2160 to "2160p · 4K", 1080 to "1080p", 720 to "720p", 480 to "480p")
                for ((q, label) in options) {
                    androidx.tv.material3.Surface(
                        onClick = { maxQuality = q; showQuality = false },
                        modifier = Modifier
                            .width(220.dp)
                            .padding(vertical = 3.dp)
                            .mouseClick { maxQuality = q; showQuality = false },
                        shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (maxQuality == q) Color(0xFFF5B942) else Color(0xFF1F2430),
                            contentColor = if (maxQuality == q) Color(0xFF0E1116) else Color.White,
                            focusedContainerColor = Color(0xFFFFD07A),
                            focusedContentColor = Color(0xFF0E1116),
                            pressedContainerColor = Color(0xFFE0A82E),
                            pressedContentColor = Color(0xFF0E1116),
                        ),
                    ) {
                        Text(
                            label,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Небольшая кнопка поверх видео. */
@Composable
private fun PlayerChip(label: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x990E1116),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFF5B942),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
