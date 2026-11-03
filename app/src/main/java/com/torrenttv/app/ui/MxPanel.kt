package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults

/**
 * Единая панель плеера «как MX Player» для всех трёх плееров
 * (торрент / онлайн / ТВ): сикбар с растущим шагом перемотки
 * (10с → 30с → 1мин → 5мин при удержании) и нижний ряд кнопок:
 * ⏱ · скорость · [⏪ ▶/⏸ ⏩] · 🎵 Дорожки · ⛶ Масштаб · 🔒.
 * Опциональные кнопки включаются передачей колбэков, слоты
 * extraStart/extraEnd — для плеерных кнопок (Серии, ⚙, Качество).
 */
@Composable
fun MxPanel(
    player: androidx.media3.exoplayer.ExoPlayer,
    title: String,
    panelTick: Int,
    playFocus: FocusRequester,
    onInfo: (String) -> Unit,
    onAction: () -> Unit = {},
    onJump: (() -> Unit)? = null,
    speedLabel: String? = null,
    onSpeed: (() -> Unit)? = null,
    onTracks: (() -> Unit)? = null,
    scaleLabel: String? = null,
    onScale: (() -> Unit)? = null,
    onLock: (() -> Unit)? = null,
    extraStart: @Composable RowScope.() -> Unit = {},
    extraEnd: @Composable RowScope.() -> Unit = {},
) {
    var posText by remember { mutableStateOf("") }
    var posFrac by remember { mutableStateOf(0f) }
    var bufFrac by remember { mutableStateOf(0f) }
    LaunchedEffect(panelTick) {
        while (true) {
            val dur = player.duration
            val p = player.currentPosition
            posText = "%d:%02d / %s".format(
                p / 60000, (p / 1000) % 60,
                if (dur > 0) "%d:%02d".format(dur / 60000, (dur / 1000) % 60) else "—",
            )
            if (dur > 0) posFrac = (p.toFloat() / dur).coerceIn(0f, 1f)
            bufFrac = player.bufferedPercentage / 100f
            kotlinx.coroutines.delay(400)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xCC0A0E14))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Заголовок — как в MX: название и серия над сикбаром
        Text(
            title,
            color = Color(0xFFB8C0CC),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        // Сикбар как в MX: в фокусе ←/→ листают позицию с растущим шагом,
        // OK — прыжок
        var jumpTarget by remember { mutableStateOf<Long?>(null) }
        var seekSteps by remember { mutableStateOf(0) }
        fun mxStep(): Long = when {
            seekSteps < 3 -> 10_000L
            seekSteps < 6 -> 30_000L
            seekSteps < 12 -> 60_000L
            else -> 300_000L
        }
        androidx.tv.material3.Surface(
            onClick = {
                jumpTarget?.let { player.seekTo(it) }
                jumpTarget = null
                seekSteps = 0
                onInfo("⏱ Перемотка")
                onAction()
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusable()
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyUp) { seekSteps = 0; return@onKeyEvent false }
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val base = jumpTarget ?: player.currentPosition
                    val dur = if (player.duration > 0) player.duration else 0L
                    when (e.key) {
                        Key.DirectionLeft -> {
                            seekSteps++
                            jumpTarget = (base - mxStep()).coerceIn(0L, dur)
                            onAction()
                            true
                        }
                        Key.DirectionRight -> {
                            seekSteps++
                            jumpTarget = if (dur > 0) (base + mxStep()).coerceIn(0L, dur) else base
                            onAction()
                            true
                        }
                        else -> false
                    }
                },
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = Color(0x33FFFFFF),
                focusedContentColor = Color.White,
            ),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp),
                ) {
                    val shownFrac = jumpTarget?.let { jt ->
                        val dur = player.duration
                        if (dur > 0) (jt.toFloat() / dur).coerceIn(0f, 1f) else posFrac
                    } ?: posFrac
                    Box(
                        Modifier
                            .fillMaxWidth(bufFrac.coerceAtLeast(shownFrac))
                            .height(5.dp)
                            .align(Alignment.CenterStart)
                            .background(Color(0xFF4A5568), RoundedCornerShape(3.dp)),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(shownFrac)
                            .height(8.dp)
                            .align(Alignment.CenterStart)
                            .background(if (jumpTarget != null) Color.White else Color(0xB3FFFFFF), RoundedCornerShape(4.dp)),
                    )
                    Text(
                        posText,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
                if (jumpTarget != null) {
                    val jt = jumpTarget ?: 0L
                    Text(
                        (if (jt >= 3_600_000L)
                            "⏱ %d:%02d:%02d — OK чтобы перейти".format(jt / 3600000, (jt / 60000) % 60, (jt / 1000) % 60)
                        else
                            "⏱ %d:%02d — OK чтобы перейти".format(jt / 60000, (jt / 1000) % 60)),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onJump != null) {
                androidx.tv.material3.Surface(
                    onClick = onJump,
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF2A2A2A),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("⏱", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                Spacer(Modifier.width(8.dp))
            }
            if (onSpeed != null && speedLabel != null) {
                androidx.tv.material3.Surface(
                    onClick = onSpeed,
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF2A2A2A),
                        contentColor = Color(0xB3FFFFFF),
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text(speedLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            }
            extraStart()
            Spacer(Modifier.weight(1f))
            // Транспортная тройка по центру панели — раскладка как в
            // MX Player: крупные ⏪ ▶/⏸ ⏩ под сикбаром. Focused —
            // контрастный (видно с дивана)
            androidx.tv.material3.Surface(
                onClick = {
                    val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                    player.seekTo((player.currentPosition - 10_000L).coerceIn(0L, dur))
                    onInfo("⏪︎ −10 сек"); onAction()
                },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF2A2A2A),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFE8ECF4),
                    focusedContentColor = Color.Black,
                ),
            ) { Text("⏪︎", fontSize = 24.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            Spacer(Modifier.width(10.dp))
            androidx.tv.material3.Surface(
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                    onAction()
                },
                modifier = Modifier.focusRequester(playFocus),
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF2A2A2A),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFE8ECF4),
                    focusedContentColor = Color.Black,
                ),
            ) {
                Text(
                    if (player.isPlaying) "▮▮" else "▶︎",
                    fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            androidx.tv.material3.Surface(
                onClick = {
                    val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 10_000L).coerceIn(0L, dur))
                    onInfo("⏩︎ +10 сек"); onAction()
                },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF2A2A2A),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFE8ECF4),
                    focusedContentColor = Color.Black,
                ),
            ) { Text("⏩︎", fontSize = 24.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            Spacer(Modifier.weight(1f))
            if (onTracks != null) {
                androidx.tv.material3.Surface(
                    onClick = onTracks,
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF2A2A2A),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("🎵 Дорожки", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                Spacer(Modifier.width(8.dp))
            }
            if (onScale != null && scaleLabel != null) {
                androidx.tv.material3.Surface(
                    onClick = onScale,
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF2A2A2A),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("⛶ $scaleLabel", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                Spacer(Modifier.width(8.dp))
            }
            if (onLock != null) {
                androidx.tv.material3.Surface(
                    onClick = onLock,
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF2A2A2A),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("🔒", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
            }
            extraEnd()
        }
    }
}
