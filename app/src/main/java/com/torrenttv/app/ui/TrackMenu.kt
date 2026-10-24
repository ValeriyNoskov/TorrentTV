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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import java.util.Locale

/**
 * Оверлей выбора аудиодорожек и субтитров (встроенных в контейнер, например mkv).
 * Открывается кнопкой MENU пульта в экране просмотра; выбор применяется через
 * TrackSelectionOverride, «Выключены» для субтитров гасит TRACK_TYPE_TEXT.
 */
private data class TrackRow(
    val group: Tracks.Group,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean,
)

@Composable
fun TrackMenuOverlay(
    player: Player,
    onDismiss: () -> Unit,
    /** Выбрана аудиодорожка — подпись для запоминания между запусками. */
    onAudioPicked: (String) -> Unit = {},
    /** Выбраны субтитры («off» — выключены) — запоминание между сериями. */
    onSubPicked: (String) -> Unit = {},
    /** Текущий сдвиг субтитров, мс. */
    subShiftMs: Int = 0,
    /** Изменить сдвиг субтитров на delta мс. */
    onSubShift: (Int) -> Unit = {},
) {
    // rev — счётчик изменений, чтобы пересобрать списки после смены дорожки
    var rev by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    var audioRows by remember { mutableStateOf(emptyList<TrackRow>()) }
    var subRows by remember { mutableStateOf(emptyList<TrackRow>()) }
    var subsDisabled by remember { mutableStateOf(false) }

    @Suppress("UNUSED_EXPRESSION")
    remember(rev) {
        val groups = player.currentTracks.groups
        val audio = mutableListOf<TrackRow>()
        val subs = mutableListOf<TrackRow>()
        for (g in 0 until groups.size) {
            val group = groups[g]
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val row = TrackRow(
                    group = group,
                    trackIndex = i,
                    label = trackLabel(group.getTrackFormat(i), i, group.type),
                    selected = group.isTrackSelected(i),
                )
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio += row
                    C.TRACK_TYPE_TEXT -> subs += row
                }
            }
        }
        audioRows = audio
        subRows = subs
        subsDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        true
    }

    // Back закрывает меню, не выходя из плеера (перекрывает BackHandler экрана)
    BackHandler { onDismiss() }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .mouseClick { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = Color(0xFF1F2430),
                contentColor = Color.White,
            ),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .mouseClick { }, // клик по панели не закрывает оверлей
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Дорожки и субтитры", fontSize = 20.sp, fontWeight = FontWeight.Bold)

                if (audioRows.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SectionTitle("Аудио")
                    Spacer(Modifier.height(6.dp))
                    TrackItemList(
                        rows = audioRows,
                        isApplied = { it?.selected == true },
                        focusRequester = focusRequester,
                        onClick = { row ->
                            if (row != null) {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .addOverride(TrackSelectionOverride(row.group.mediaTrackGroup, row.trackIndex))
                                    .build()
                                // Запомнить дорожку (только осмысленные подписи —
                                // «Дорожка 2» без меты в другой раз не совпадёт)
                                val fmt = row.group.getTrackFormat(row.trackIndex)
                                if (!fmt.label.isNullOrBlank() || !fmt.language.isNullOrBlank()) {
                                    onAudioPicked(fmt.label ?: fmt.language ?: "")
                                }
                                rev++
                            }
                        },
                    )
                }

                if (subRows.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SectionTitle("Субтитры")
                    Spacer(Modifier.height(6.dp))
                    // Сдвиг субтитров ±0.5с (как в Kodi): субы отстают/спешат
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сдвиг: ", color = Color(0xFFB8C0CC), fontSize = 14.sp)
                        Surface(onClick = { onSubShift(-500) }, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)), colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1F2430), contentColor = Color.White, focusedContainerColor = Color(0xFFF5B942), focusedContentColor = Color.Black)) {
                            Text("−0.5с", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                        Text(
                            "%+.1fс".format(subShiftMs / 1000f),
                            color = if (subShiftMs == 0) Color(0xFFB8C0CC) else Color(0xFFF5B942),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                        Surface(onClick = { onSubShift(+500) }, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)), colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1F2430), contentColor = Color.White, focusedContainerColor = Color(0xFFF5B942), focusedContentColor = Color.Black)) {
                            Text("+0.5с", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // null = «Выключены»
                    TrackItemList(
                        rows = listOf<TrackRow?>(null) + subRows,
                        isApplied = { row ->
                            if (row == null) subsDisabled && subRows.none { it.selected } else row.selected
                        },
                        focusRequester = if (audioRows.isEmpty()) focusRequester else null,
                        onClick = { row ->
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, row == null)
                                .apply {
                                    if (row != null) {
                                        addOverride(TrackSelectionOverride(row.group.mediaTrackGroup, row.trackIndex))
                                    }
                                }
                                .build()
                            // Запомнить выбор субтитров между сериями (ROADMAP №5):
                            // «off» — выключены, иначе подпись/язык трека
                            if (row == null) onSubPicked("off") else {
                                val fmt = row.group.getTrackFormat(row.trackIndex)
                                if (!fmt.label.isNullOrBlank() || !fmt.language.isNullOrBlank()) {
                                    onSubPicked(fmt.label ?: fmt.language ?: "")
                                }
                            }
                            rev++
                        },
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "MENU или назад — закрыть",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 14.sp, color = Color(0xFFF5B942), fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TrackItemList(
    rows: List<TrackRow?>,
    isApplied: (TrackRow?) -> Boolean,
    focusRequester: FocusRequester?,
    onClick: (TrackRow?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { index, row ->
            val label = row?.label ?: "Выключены"
            Surface(
                onClick = { onClick(row) },
                modifier = Modifier
                    .fillMaxWidth()
                    .mouseClick { onClick(row) }
                    .then(if (index == 0 && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF141821),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFF5B942),
                    focusedContentColor = Color(0xFF0E1116),
                    pressedContainerColor = Color(0xFFE0A82E),
                    pressedContentColor = Color(0xFF0E1116),
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isApplied(row)) "✓" else "",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(text = label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun trackLabel(format: androidx.media3.common.Format, index: Int, type: Int): String {
    val base = format.label
        ?: languageName(format.language)
        ?: defaultName(type, index)
    val extra = when (type) {
        C.TRACK_TYPE_AUDIO -> when (format.channelCount) {
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            in 2..5 -> "${format.channelCount} кан."
            else -> null
        }
        else -> null
    }
    return listOfNotNull(base, extra).joinToString(" • ")
}

private fun languageName(code: String?): String? {
    if (code.isNullOrBlank()) return null
    return runCatching {
        Locale(code).displayLanguage.replaceFirstChar { it.uppercase() }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun defaultName(type: Int, index: Int): String = when (type) {
    C.TRACK_TYPE_AUDIO -> "Дорожка ${index + 1}"
    C.TRACK_TYPE_TEXT -> "Субтитры ${index + 1}"
    else -> "Трек ${index + 1}"
}
