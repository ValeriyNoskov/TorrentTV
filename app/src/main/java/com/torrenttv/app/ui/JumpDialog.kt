package com.torrenttv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Диалог «Перейти к…» — быстрая перемотка как в MX/VLC:
 * пресеты процентов от длительности (10/25/50/75/90%).
 * 50% — «середина фильма». Используется в обоих плеерах.
 */
@Composable
fun JumpToDialog(
    durationMs: Long,
    title: String,
    onJump: (Long) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(340.dp)
                .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                .padding(16.dp),
        ) {
            androidx.tv.material3.Text(
                "⏱ Перейти к…",
                color = Color(0xFFF5B942),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            androidx.tv.material3.Text(
                title,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            val firstFocus = remember { FocusRequester() }
            runCatching { LaunchedEffect(Unit) { firstFocus.requestFocus() } }
            val presets = listOf(
                "10% — начало" to 0.10f,
                "25%" to 0.25f,
                "50% — середина" to 0.50f,
                "75%" to 0.75f,
                "90% — развязка" to 0.90f,
            )
            presets.forEachIndexed { i, (label, frac) ->
                if (i > 0) Spacer(Modifier.height(6.dp))
                SettingsMenuItem(
                    label = label,
                    focusRequester = if (i == 0) firstFocus else null,
                ) {
                    if (durationMs > 0) onJump((durationMs * frac).toLong())
                    onClose()
                }
            }
        }
    }
}
