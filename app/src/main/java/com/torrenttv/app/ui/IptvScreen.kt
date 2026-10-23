package com.torrenttv.app.ui

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
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.torrenttv.app.data.EpgService
import com.torrenttv.app.data.IptvChannel
import com.torrenttv.app.data.IptvService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Телеканалы: список каналов слева (компактные строки: номер, логотип, название,
 * «сейчас в эфире»), справа — карточка канала под фокусом: логотип, программа,
 * кнопка «Смотреть». ОК на строке — включить канал.
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
    var focused by remember { mutableStateOf<IptvChannel?>(null) } // канал под фокусом — для панели справа
    var epgTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { IptvService.channels(allGroups = store.iptvAllGroups()) }
        channels = loaded
        loading = false
        error = loaded.isEmpty()
        focused = focused ?: loaded.firstOrNull()
    }
    // EPG для строк «сейчас в эфире»
    LaunchedEffect(channels) {
        if (channels.isNotEmpty()) {
            withContext(Dispatchers.IO) { EpgService.load(context, channels) }
            epgTick++
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            epgTick++
        }
    }

    val favs = remember(favTick) { store.favChannels() }
    val favKeys = remember(favTick) { favs.map { it.key }.toSet() }

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
            when {
                loading -> "Загружаю плейлист…"
                error -> "Не удалось загрузить плейлист — проверьте сеть"
                else -> "Бесплатный IPTV · ${channels.size} каналов. ОК на канале — смотреть"
            },
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
        )

        if (loading || error) return@Column

        Row(Modifier.weight(1f).padding(top = 8.dp)) {

            // ---------- Список каналов (слева) ----------
            Column(Modifier.width(500.dp).fillMaxSize()) {
                // Фильтр по группам
                val groups = remember(channels) { IptvService.groups(channels) }
                LazyRow(Modifier.padding(bottom = 6.dp)) {
                    item {
                        GroupChip("Все", selectedGroup == null) { selectedGroup = null }
                    }
                    items(groups.size) { i ->
                        val (g, label) = groups[i]
                        Box(Modifier.padding(start = 8.dp)) {
                            GroupChip(label, selectedGroup == g) { selectedGroup = g }
                        }
                    }
                }

                val visible = remember(channels, selectedGroup) {
                    if (selectedGroup == null) channels
                    else channels.filter { it.group == selectedGroup }
                }
                val byGroup = remember(visible) { visible.groupBy { it.group } }
                val orderedGroups = remember(byGroup) {
                    val priority = listOf(IptvService.FEDERAL_GROUP, "News", "General")
                    byGroup.keys.sortedBy { g ->
                        val i = priority.indexOf(g)
                        if (i >= 0) i else priority.size
                    }
                }

                LazyColumn(Modifier.weight(1f)) {
                    if (selectedGroup == null && favs.isNotEmpty()) {
                        item(key = "hdr_fav") { ListHeader("★ Избранные") }
                        items(favs, key = { "row_fav_${it.key}" }) { ch ->
                            ChannelListRow(ch, fav = true, epgTick, onPlay) { focused = ch }
                        }
                    }
                    for (g in orderedGroups) {
                        val list = byGroup[g].orEmpty()
                        // ключ с группой: один канал может входить в несколько групп
                        item(key = "hdr_${g}_${list.size}") { ListHeader(IptvService.groupLabel(g, list.size)) }
                        items(list, key = { "row_${g}_${it.key}" }) { ch ->
                            ChannelListRow(ch, fav = ch.key in favKeys, epgTick, onPlay) { focused = ch }
                        }
                    }
                }
            }

            Spacer(Modifier.width(24.dp))

            // ---------- Карточка канала (справа) ----------
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ChannelDetail(focused, favKeys.contains(focused?.key), epgTick, onPlay)
            }
        }
    }
}

/** Компактная строка канала в списке слева. */
@Composable
private fun ChannelListRow(
    ch: IptvChannel,
    fav: Boolean,
    epgTick: Int,
    onPlay: (IptvChannel) -> Unit,
    onFocus: (IptvChannel) -> Unit,
) {
    var rowFocused by remember { mutableStateOf(false) }
    androidx.tv.material3.Surface(
        onClick = { onPlay(ch) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .onFocusEvent {
                rowFocused = it.isFocused
                if (it.isFocused) onFocus(ch)
            }
            .mouseClick { onPlay(ch) },
        shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0x001F2430),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFF5B942),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
            disabledContainerColor = Color(0x001F2430),
            disabledContentColor = Color.White,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ch.tvgNo > 0) {
                Text(
                    "%2d".format(ch.tvgNo),
                    color = if (rowFocused) Color(0xFF0E1116).copy(alpha = 0.7f) else Color(0xFFF5B942),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(30.dp),
                )
            }
            // Мини-логотип
            Box(
                Modifier
                    .size(34.dp)
                    .background(
                        if (rowFocused) Color(0x220E1116) else Color(0xFF0E1116),
                        RoundedCornerShape(6.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (ch.logoUrl.isNotBlank()) {
                    AsyncImage(
                        model = ch.logoUrl,
                        contentDescription = ch.name,
                        modifier = Modifier.size(28.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("📺", fontSize = 16.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = ch.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val now = remember(epgTick, ch.key) { EpgService.now(ch) }
                if (now != null) {
                    Text(
                        text = "Сейчас: ${now.title}",
                        color = if (rowFocused) Color(0xFF0E1116).copy(alpha = 0.65f)
                        else Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (fav) {
                Text(
                    "★",
                    color = if (rowFocused) Color(0xFF0E1116) else Color(0xFFF5B942),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** Карточка канала под фокусом (справа): логотип, программа, кнопка «Смотреть». */
@Composable
private fun ChannelDetail(
    ch: IptvChannel?,
    fav: Boolean,
    epgTick: Int,
    onPlay: (IptvChannel) -> Unit,
) {
    if (ch == null) {
        Text(
            "Выберите канал списком слева",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 120.dp),
        )
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Логотип крупно
        Box(
            Modifier
                .padding(top = 20.dp)
                .size(260.dp)
                .background(Color(0xFF1F2430), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (ch.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = ch.logoUrl,
                    contentDescription = ch.name,
                    modifier = Modifier.size(200.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("📺", fontSize = 80.sp)
            }
        }
        Text(
            text = (if (ch.tvgNo > 0) "№ ${ch.tvgNo} · " else "") + ch.name,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = IptvService.groupLabel(ch.group, 0).substringBeforeLast(" (") +
                (if (fav) "  ·  ★ в избранном" else ""),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 2.dp),
        )

        // Телепрограмма
        val now = remember(epgTick, ch.key) { EpgService.now(ch) }
        val next = remember(epgTick, ch.key) { EpgService.next(ch) }
        if (now != null || next != null) {
            Column(
                Modifier
                    .padding(top = 14.dp)
                    .background(Color(0xFF1F2430), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                now?.let {
                    Text(
                        "Сейчас: ${it.title} · ${fmt(it.startMs)}–${fmt(it.stopMs)}",
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(320.dp),
                    )
                }
                next?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Далее: ${it.title} · ${fmt(it.startMs)}",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }

        androidx.tv.material3.Surface(
            onClick = { onPlay(ch) },
            modifier = Modifier
                .padding(top = 18.dp)
                .mouseClick { onPlay(ch) },
            shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFFF5B942),
                contentColor = Color(0xFF0E1116),
                focusedContainerColor = Color(0xFFFFD07A),
                focusedContentColor = Color(0xFF0E1116),
                pressedContainerColor = Color(0xFFE0A82E),
                pressedContentColor = Color(0xFF0E1116),
                disabledContainerColor = Color(0xFF1F2430),
                disabledContentColor = Color.White,
            ),
        ) {
            Text(
                "▶  Смотреть",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}

private fun fmt(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

/** Заголовок-разделитель группы в списке. */
@Composable
private fun ListHeader(text: String) {
    Text(
        text = text,
        color = Color(0xFFF5B942),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
    )
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
