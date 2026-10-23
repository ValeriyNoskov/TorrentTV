package com.torrenttv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.torrenttv.app.data.IptvChannel

/**
 * Плеер телеканала: поток из плейлиста (HLS/прогрессивный) без торрента.
 * Лив — без перемотки и сохранения позиции.
 */
@Composable
fun IptvPlayerScreen(channel: IptvChannel, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { com.torrenttv.app.data.LocalStore(context) }
    var status by remember { mutableStateOf("Подключаюсь к каналу…") }
    var playing by remember { mutableStateOf(false) }
    var fav by remember { mutableStateOf(store.isFavChannel(channel)) }

    val focusRequester = remember { FocusRequester() }

    val player = remember(channel.url) {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                setMediaItem(ExoMediaItem.fromUri(channel.url))
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        playing = state == Player.STATE_READY || state == Player.STATE_BUFFERING
                        if (state == Player.STATE_READY) status = ""
                        if (state == Player.STATE_BUFFERING) status = "Буферизация…"
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        status = "Поток недоступен — канал мог отключиться. Попробуйте другой."
                        playing = false
                    }
                })
            }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    // Контроллер Media3 рассчитан на тач и перехватывает D-pad пульта —
                    // управление делаем своей фокусируемой панелью ниже.
                    useController = false
                    isFocusable = false
                    isClickable = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Панель канала поверх видео: избранное и возврат. Начальный фокус — на «Избранное».
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.tv.material3.Surface(
                onClick = { fav = store.toggleFavChannel(channel) },
                modifier = Modifier
                    .mouseClick { fav = store.toggleFavChannel(channel) }
                    .focusRequester(focusRequester),
                shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color(0x991F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFF5B942),
                    focusedContentColor = Color(0xFF0E1116),
                    pressedContainerColor = Color(0xFFE0A82E),
                    pressedContentColor = Color(0xFF0E1116),
                    disabledContainerColor = Color(0x991F2430),
                    disabledContentColor = Color.White,
                ),
            ) {
                Text(
                    text = if (fav) "★ В избранном" else "☆ В избранное",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            androidx.tv.material3.Surface(
                onClick = onBack,
                modifier = Modifier.mouseClick { onBack() },
                shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color(0x991F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFF5B942),
                    focusedContentColor = Color(0xFF0E1116),
                    pressedContainerColor = Color(0xFFE0A82E),
                    pressedContentColor = Color(0xFF0E1116),
                    disabledContainerColor = Color(0x991F2430),
                    disabledContentColor = Color.White,
                ),
            ) {
                Text(
                    text = "← К списку каналов",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (!playing && status.isNotBlank()) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(channel.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(status, color = Color(0xFFF5B942), fontSize = 17.sp)
            }
        }
    }
}
