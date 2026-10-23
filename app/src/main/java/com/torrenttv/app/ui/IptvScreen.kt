package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.torrenttv.app.data.IptvChannel
import com.torrenttv.app.data.IptvService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Телеканалы: бесплатный IPTV (плейлист iptv-org, Россия — федеральные и
 * московские/подмосковные каналы). Группы по жанрам, клик — включить канал.
 */
@Composable
fun IptvScreen(onPlay: (IptvChannel) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { com.torrenttv.app.data.LocalStore(context) }
    var channels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<String?>(null) } // null = все
    var favTick by remember { mutableStateOf(0) } // перерисовка звёздочек после изменения избранного

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { IptvService.channels() }
        channels = loaded
        loading = false
        error = loaded.isEmpty()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
    ) {
        Text(
            "📡 Телеканалы",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
        Text(
            if (loading) "Загружаю плейлист…"
            else if (error) "Не удалось загрузить плейлист — проверьте сеть"
            else "Бесплатный IPTV · iptv-org · ${channels.size} каналов. ОК на канале — включить",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
        )

        if (loading || error) return@Column

        // ---------- Группы ----------
        val groups = remember(channels) { IptvService.groups(channels) }
        Row(Modifier.padding(top = 8.dp)) {
            LazyRow {
                item {
                    GroupChip("Все", selectedGroup == null) { selectedGroup = null }
                }
                items(groups.size) { i ->
                    val (g, label) = groups[i]
                    androidx.compose.foundation.layout.Box(Modifier.padding(start = 8.dp)) {
                        GroupChip(label, selectedGroup == g) { selectedGroup = g }
                    }
                }
            }
        }

        // ---------- Каналы ----------
        val visible = remember(channels, selectedGroup) {
            if (selectedGroup == null) channels else channels.filter { it.group == selectedGroup }
        }
        // Избранные пересчитываются по favTick (меняются из плеера канала)
        val favs = remember(favTick) { store.favChannels() }
        val favKeys = remember(favTick) { favs.map { it.key }.toSet() }
        LazyColumn(Modifier.padding(top = 8.dp)) {
            if (favs.isNotEmpty()) {
                item {
                    ChannelRow(
                        "__favorites",
                        favs,
                        onPlay,
                        titleOverride = "★ Избранные",
                        isFav = { true },
                    )
                }
            }
            val byGroup = visible.groupBy { it.group }
            // Новости и универсальные (федеральные + московские) — первыми рядами
            val priority = listOf("News", "General")
            val orderedGroups = byGroup.keys.sortedBy { g ->
                val i = priority.indexOf(g)
                if (i >= 0) i else priority.size
            }
            items(orderedGroups) { g ->
                ChannelRow(g, byGroup[g].orEmpty(), onPlay, isFav = { it.key in favKeys })
            }
        }
    }
}

@Composable
private fun ChannelRow(
    group: String,
    list: List<IptvChannel>,
    onPlay: (IptvChannel) -> Unit,
    titleOverride: String? = null,
    isFav: (IptvChannel) -> Boolean = { false },
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            titleOverride ?: IptvService.groupLabel(group, list.size),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 0.dp)) {
            items(list.size) { i ->
                ChannelCard(list[i], isFav(list[i]), onPlay)
            }
        }
    }
}

@Composable
private fun ChannelCard(ch: IptvChannel, isFav: Boolean, onPlay: (IptvChannel) -> Unit) {
    androidx.tv.material3.Surface(
        onClick = { onPlay(ch) },
        modifier = Modifier
            .width(210.dp)
            .padding(6.dp)
            .mouseClick { onPlay(ch) },
        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1F2430),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF2A3140),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0xFF273548),
            pressedContentColor = Color.White,
            disabledContainerColor = Color(0xFF1F2430),
            disabledContentColor = Color.White,
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            // Логотип в плашке 16:9 — как экран ТВ
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(105.dp)
                    .background(Color(0xFF0E1116), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (ch.logoUrl.isNotBlank()) {
                    var logoOk by remember(ch.logoUrl) { mutableStateOf(false) }
                    if (logoOk) {
                        Box(
                            Modifier
                                .background(Color.White, RoundedCornerShape(8.dp))
                                .padding(6.dp)
                                .size(88.dp),
                        )
                    }
                    AsyncImage(
                        model = ch.logoUrl,
                        contentDescription = ch.name,
                        modifier = Modifier
                            .background(Color(0xFF0E1116), RoundedCornerShape(8.dp))
                            .padding(6.dp)
                            .size(88.dp),
                        contentScale = ContentScale.Fit,
                        onSuccess = { logoOk = true },
                        onError = { logoOk = false },
                    )
                } else {
                    Text("📺", fontSize = 34.sp)
                }
                if (isFav) {
                    Text(
                        "★",
                        color = Color(0xFFF5B942),
                        fontSize = 18.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
            }
            Text(
                text = ch.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "▶ Смотреть",
                color = Color(0xFFF5B942),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = Modifier.mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(20.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFFF5B942) else Color(0xFF1F2430),
            contentColor = if (selected) Color(0xFF0E1116) else Color.White,
            focusedContainerColor = Color(0xFFFFD07A),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
            disabledContainerColor = Color(0xFF1F2430),
            disabledContentColor = Color.White,
        ),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
