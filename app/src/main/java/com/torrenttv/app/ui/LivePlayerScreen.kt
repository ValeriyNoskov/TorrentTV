package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.data.AppLog
import com.torrenttv.app.data.Epg
import com.torrenttv.app.data.IptvChannel
import com.torrenttv.app.data.LocalStore
import kotlinx.coroutines.delay

/**
 * Плеер живого ТВ (HLS).
 *
 * Панель как в остальных плеерах: название канала и передача («Сейчас … ·
 * Далее …») видны ТОЛЬКО пока панель открыта — по просьбе владельца
 * заголовок скрывается вместе с меню.
 *
 * Управление: OK — панель/пауза, ←/→ — канал, ↑↓ — громкость не трогаем
 * (её делает ТВ), BACK — панель, второй BACK — выход.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LivePlayerScreen(
    channel: IptvChannel,
    channels: List<IptvChannel>,
    onBack: () -> Unit,
    onSwitch: (IptvChannel) -> Unit,
) {
    // Экран не гаснет, пока играет фильм
    KeepScreenOn()

    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var current by remember { mutableStateOf(channel) }
    var showPanel by remember { mutableStateOf(true) }
    // ⚙ Меню: перезапуск, избранное, выход (владелец: панель минимальная)
    var showGear by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var panelTick by remember { mutableStateOf(0) }
    var programNow by remember { mutableStateOf<Epg.Program?>(null) }
    var programNext by remember { mutableStateOf<Epg.Program?>(null) }
    var showedAt by remember { mutableStateOf(0L) }

    val player = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    // Живой поток: держим небольшой буфер, чтобы не отставать от эфира
                    .setBufferDurationsMs(10_000, 30_000, 2_000, 5_000)
                    .build(),
            )
            .build()
    }

    // Смена канала: перезапуск источника на том же плеере (быстро, без мигания)
    LaunchedEffect(current.url) {
        error = ""
        playing = true
        showedAt = System.currentTimeMillis()
        AppLog.log("tv", "эфир: «${current.name}» (${current.group}) ${current.url}")
        runCatching {
            player.setMediaItem(MediaItem.fromUri(current.url))
            player.prepare()
            player.playWhenReady = true
        }.onFailure {
            error = "${it.javaClass.simpleName}: ${it.message ?: "не удалось открыть поток"}"
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "${e.errorCodeName} (код ${e.errorCode})"
                AppLog.log("tv", "эфир ошибка: ${e.errorCodeName} код=${e.errorCode} канал=«${current.name}»")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Программа канала: берём из кэша EPG (сеть не дёргаем на каждое переключение)
    LaunchedEffect(current.name) {
        val map = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Epg.load(context, listOf(current.name))
        }
        val list = Epg.forChannel(map, current.name)
        val now = System.currentTimeMillis()
        programNow = list.firstOrNull { it.isNow(now) }
        programNext = list.firstOrNull { it.start > now }
    }

    // Автоскрытие панели (как в остальных плеерах)
    LaunchedEffect(panelTick, showPanel) {
        if (showPanel) {
            delay(8_000)
            showPanel = false
            AppLog.log("tv", "панель: скрыта автоматически (8 с)")
        }
    }

    fun step(delta: Int) {
        if (channels.isEmpty()) return
        val idx = channels.indexOfFirst { it.name == current.name && it.url == current.url }
            .let { if (it < 0) 0 else it }
        val next = ((idx + delta) % channels.size + channels.size) % channels.size
        current = channels[next]
        panelTick++
    }

    androidx.activity.compose.BackHandler {
        if (showPanel) showPanel = false else onBack()
    }

    // Фокус на контейнере плеера: без него пульт «не видит» экран и ОК
    // после автоскрытия панели ничего не возвращал — меню пропадало навсегда.
    val keysFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val panelFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Панель открыта — фокус на её первой кнопке (иначе пульт «не видит» кнопки),
    // панель скрыта — фокус на контейнере плеера (ОК возвращает панель)
    LaunchedEffect(showPanel) {
        kotlinx.coroutines.delay(60)
        runCatching {
            if (showPanel) panelFocus.requestFocus() else keysFocus.requestFocus()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(keysFocus)
            // Перехватываем пульт только когда панель скрыта: иначе кнопки
            // внутри не получают фокус (жалоба «кнопки не работают»)
            .focusable(enabled = !showPanel)
            .onKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    // ОК / Enter: скрытую панель показываем, открытую — ставим на паузу
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        if (!showPanel) {
                            showPanel = true
                            panelTick++
                            AppLog.log("tv", "панель: показана кнопкой ОК")
                        } else {
                            if (player.isPlaying) player.pause() else player.play()
                            panelTick++
                        }
                        true
                    }

                    // Канал листаем стрелками, когда панель скрыта: экран «чистый»
                    Key.DirectionLeft -> {
                        if (showPanel) false else {
                            step(-1)
                            true
                        }
                    }

                    Key.DirectionRight -> {
                        if (showPanel) false else {
                            step(1)
                            true
                        }
                    }

                    // Вверх/вниз без панели — тоже показываем её (как в обычных плеерах)
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (!showPanel) {
                            showPanel = true
                            panelTick++
                            true
                        } else {
                            panelTick++
                            false
                        }
                    }

                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (error.isNotBlank()) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE0151B26), RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⚠ Канал не открылся", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(error, color = Color(0xFFE8ECF4), fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row {
                    androidx.tv.material3.Button(onClick = {
                        error = ""
                        runCatching {
                            player.setMediaItem(MediaItem.fromUri(current.url))
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }) { Text("↻ Повторить") }
                    Spacer(Modifier.width(10.dp))
                    androidx.tv.material3.Button(onClick = { step(1) }) { Text("⏭︎ Следующий канал") }
                }
            }
        }

        // Панель: заголовок (канал + передача) и кнопки. Скрывается целиком.
        if (showPanel) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                // Название канала и передача — только здесь, вместе с меню
                Text(
                    "📡 ${current.name}" + if (current.quality.isNotBlank()) " · ${current.quality}" else "",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                programNow?.let { p ->
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Сейчас: ${p.title}",
                            color = Color(0xFFB8C0CC),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            "  ${hhmm(p.start)}–${hhmm(p.end)}",
                            color = Color(0xFFE8ECF4),
                            fontSize = 14.sp,
                        )
                    }
                }
                programNext?.let { p ->
                    Text(
                        "Далее: ${p.title} · ${hhmm(p.start)}",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (programNow == null && programNext == null) {
                    Text(
                        "Программа передач для этого канала недоступна",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${channels.size} каналов",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    // Единственная кнопка панели — шестерёнка (единый стиль плееров)
                    androidx.tv.material3.Surface(
                        onClick = { showGear = !showGear; panelTick++ },
                        shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                    ) { Text(if (showGear) "✕" else "⚙", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
                }
            }
        }

        // Центр: полупрозрачные ⏮︎/▮▮/⏭︎ — переключение каналов, пауза
        if (showPanel) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.tv.material3.Surface(
                    onClick = { step(-1) },
                    shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x99151B26),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) { Text("⏮︎", fontSize = 30.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 15.dp)) }
                androidx.tv.material3.Surface(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                        panelTick++
                    },
                    modifier = Modifier.focusRequester(panelFocus),
                    shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x99151B26),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) {
                    Text(
                        if (playing) "▮▮" else "▶︎",
                        fontSize = 34.sp,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                }
                androidx.tv.material3.Surface(
                    onClick = { step(1) },
                    shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x99151B26),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) { Text("⏭︎", fontSize = 30.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 15.dp)) }
            }
        }

        // ⚙ Меню: перезапуск, избранное, выход
        if (showGear) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 120.dp)
                    .background(Color(0xF2151B26), RoundedCornerShape(14.dp))
                    .padding(10.dp),
            ) {
                @Composable fun GItem(label: String, action: () -> Unit) {
                    androidx.tv.material3.Surface(
                        onClick = { showGear = false; action() },
                        modifier = Modifier
                            .width(260.dp)
                            .padding(vertical = 2.dp),
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                    ) { Text(label, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                }
                GItem("↻ Перезапустить поток") {
                    error = ""
                    runCatching {
                        player.setMediaItem(MediaItem.fromUri(current.url))
                        player.prepare()
                        player.playWhenReady = true
                    }
                }
                GItem(
                    if (store.isTvFavorite(current.url)) "★︎ Убрать из избранного" else "☆ В избранное",
                ) {
                    val added = store.toggleTvFavorite(current.url)
                    AppLog.log("tv", if (added) "в избранное: «${current.name}»" else "из избранного: «${current.name}»")
                    panelTick++
                }
                GItem("✕ Выйти") { onBack() }
            }
        }
    }
}
private fun hhmm(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
