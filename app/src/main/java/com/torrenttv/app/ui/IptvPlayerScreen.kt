package com.torrenttv.app.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.torrenttv.app.data.IptvService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Плеер телеканала: поток из плейлиста (HLS/прогрессивный) без торрента.
 * Лив — без перемотки и сохранения позиции.
 * Боковая панель слева — переключение каналов; скрывается кнопкой MENU
 * пульта или кнопкой «Каналы», Back закрывает панель.
 */
@Composable
fun IptvPlayerScreen(channel: IptvChannel, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { com.torrenttv.app.data.LocalStore(context) }

    // Текущий канал — состояние: переключение из боковой панели без выхода из плеера
    var current by remember { mutableStateOf(channel) }
    var status by remember { mutableStateOf("Подключаюсь к каналу…") }
    var playing by remember { mutableStateOf(false) }
    var fav by remember(current) { mutableStateOf(store.isFavChannel(current)) }

    // Список каналов для боковой панели (из кэша сервиса)
    var channels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
    var showSidebar by remember { mutableStateOf(false) }
    var favTick by remember { mutableStateOf(0) } // пересборка панели после смены избранного

    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        channels = withContext(Dispatchers.IO) { IptvService.channels() }
    }

    // Избранные каналы: сверху панели, ★
    val favs = remember(favTick) { store.favChannels() }

    // При открытии панели: скролл и фокус на строку текущего канала;
    // при закрытии — фокус возвращается на кнопку «Каналы»
    LaunchedEffect(showSidebar) {
        if (!showSidebar) runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(showSidebar, channels, favTick) {
        if (showSidebar) {
            val items = sidebarItems(channels, favs)
            val idx = items.indexOfFirst { it.first.key == current.key }
            if (idx > 0) listState.scrollToItem(idx)
            runCatching { focusRequester.requestFocus() }
        }
    }

    val player = remember(current.url) {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                setMediaItem(ExoMediaItem.fromUri(current.url))
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

    BackHandler(enabled = showSidebar) { showSidebar = false }
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
                    // MENU пульта — боковая панель каналов
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU) {
                            showSidebar = !showSidebar
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Панель канала поверх видео: каналы, избранное и возврат.
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SidebarButton(
                text = "📺 Каналы",
                focused = false,
                focusRequester = focusRequester,
                onClick = { showSidebar = !showSidebar },
            )
            SidebarButton(
                text = if (fav) "★ В избранном" else "☆ В избранное",
                focused = false,
                focusRequester = null,
                onClick = {
                    fav = store.toggleFavChannel(current)
                    favTick++
                },
            )
            SidebarButton(
                text = "← К списку каналов",
                focused = false,
                focusRequester = null,
                onClick = onBack,
            )
        }
        if (!playing && status.isNotBlank()) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(current.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(status, color = Color(0xFFF5B942), fontSize = 17.sp)
            }
        }

        // ---------- Боковая панель каналов (слева, скрывается) ----------
        if (showSidebar) {
            Row(Modifier.fillMaxSize().background(Color(0x66000000))) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .width(330.dp)
                        .background(Color(0xF20E1116))
                        .padding(vertical = 14.dp),
                ) {
                    Text(
                        "Телеканалы · ${channels.size}",
                        color = Color(0xFFF5B942),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        val items = sidebarItems(channels, favs)
                        val focusIdx = items.indexOfFirst { it.first.key == current.key }.coerceAtLeast(0)
                        itemsIndexed(items, key = { _, (ch, _) -> ch.key }) { index, (ch, isFav) ->
                            SidebarButton(
                                text = (if (isFav) "★ " else "") + ch.name,
                                focused = ch.key == current.key,
                                focusRequester = if (index == focusIdx) focusRequester else null,
                                onClick = {
                                    if (ch.key != current.key) current = ch
                                    showSidebar = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                // Клик по затемнению закрывает панель
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .mouseClick { showSidebar = false },
                )
            }
        }
    }
}

/** Строки боковой панели: избранные сверху, далее все каналы. */
private fun sidebarItems(
    channels: List<IptvChannel>,
    favs: List<IptvChannel>,
): List<Pair<IptvChannel, Boolean>> {
    val favIds = favs.map { it.key }.toSet()
    val rest = channels.filter { it.key !in favIds }
    return (favs + rest).map { it to (it.key in favIds) }
}

@Composable
private fun SidebarButton(
    text: String,
    focused: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .mouseClick { onClick() }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0x991F2430),
            contentColor = Color.White,
            focusedContainerColor = if (focused) Color(0xFFF5B942) else Color(0xFF31435A),
            focusedContentColor = if (focused) Color(0xFF0E1116) else Color.White,
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
            disabledContainerColor = Color(0x991F2430),
            disabledContentColor = Color.White,
        ),
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
