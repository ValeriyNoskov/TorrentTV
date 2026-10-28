package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.torrenttv.app.data.AppLog
import com.torrenttv.app.data.Epg
import com.torrenttv.app.data.Iptv
import com.torrenttv.app.data.IptvChannel
import coil.compose.AsyncImage
import com.torrenttv.app.data.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Канал + что идёт сейчас (из телепрограммы). */
private data class TvEntry(
    val channel: IptvChannel,
    val now: Epg.Program?,
    val next: Epg.Program?,
)

/**
 * Раздел «📡 ТВ»: живые каналы по HLS + телепрограмма.
 *
 * Состав каналов — только то, что реально открывается из Москвы (встроенный
 * плейлист проверен: манифест 200 + первый сегмент 206), плюс свой плейлист
 * из настроек. Ace Stream и NoxBit не используются — это не HLS.
 *
 * Программа: teleguide.info (XMLTV), большой файл качается один раз в 12 ч
 * и кэшируется; в плитке видно «Сейчас …», в плеере — «Сейчас/Далее».
 */
@Composable
fun TvScreen(onPlay: (IptvChannel, List<IptvChannel>) -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var channels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
    var group by remember { mutableStateOf("Все") }
    var loading by remember { mutableStateOf(true) }
    var epgLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf<Map<String, List<Epg.Program>>>(emptyMap()) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var refreshTick by remember { mutableStateOf(0) }
    // Избранные каналы: ключ — адрес потока (имена в плейлистах различаются)
    var favs by remember { mutableStateOf(store.tvFavorites()) }

    // Каналы: свой плейлист (если задан) + встроенный
    LaunchedEffect(refreshTick) {
        loading = true
        val list = withContext(Dispatchers.IO) {
            runCatching {
                Iptv.load(context, store.iptvPlaylistUrl(), store.iptvUseOwnPlaylist())
            }.getOrElse {
                error = "${it.javaClass.simpleName}: ${it.message ?: "ошибка плейлиста"}"
                emptyList()
            }
        }
        channels = list
        loading = false
        AppLog.log("tv", "каналов загружено: ${list.size}, групп: ${Iptv.groups(list).size}")
    }

    // Телепрограмма: из кэша сразу, обновление — в фоне (не блокирует каналы)
    LaunchedEffect(channels, refreshTick) {
        if (channels.isEmpty()) return@LaunchedEffect
        epgLoading = true
        val names = channels.map { it.name }
        epg = withContext(Dispatchers.IO) { Epg.load(context, names) }
        epgLoading = false
    }

    // Раз в минуту пересчитываем «сейчас идёт»
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            clock = System.currentTimeMillis()
        }
    }

    val groups = remember(channels) { Iptv.groups(channels) }
    val shownChannels = remember(channels, group, favs) {
        when (group) {
            "Все" -> channels
            "★︎ Избранные" -> channels.filter { favs.contains(it.url) }
            else -> channels.filter { it.group == group }
        }
    }
    val shown = remember(shownChannels, epg, clock) {
        shownChannels.map { ch ->
            val list = Epg.forChannel(epg, ch.name)
            TvEntry(
                channel = ch,
                now = list.firstOrNull { it.isNow(clock) },
                next = list.firstOrNull { it.start > clock },
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0E1116))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            ) {
                Text(
                    "📡 ТВ",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    when {
                        loading -> "Загружаю каналы…"
                        channels.isEmpty() -> "Каналы не загрузились"
                        else -> buildString {
                            append("${shown.size} каналов · ${groups.size} групп")
                            if (epg.isNotEmpty()) append(" · программа есть")
                            if (epgLoading) append(" · обновляю программу…")
                        }
                    },
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                TvPill("↻ Обновить") {
                    refreshTick++
                }
            }

            // Фильтр по группам: «Все» + группы плейлиста
            LazyRow(
                contentPadding = PaddingValues(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    TvPill(if (group == "Все") "✓ Все" else "Все", active = group == "Все") { group = "Все" }
                }
                item {
                    TvPill(
                        text = (if (group == "★︎ Избранные") "✓ ★ Избранные" else "★︎ Избранные") +
                            if (favs.isNotEmpty()) " · ${favs.size}" else "",
                        active = group == "★︎ Избранные",
                    ) { group = "★︎ Избранные" }
                }
                items(groups) { (name, count) ->
                    TvPill(
                        text = if (group == name) "✓ $name · $count" else "$name · $count",
                        active = group == name,
                    ) { group = name }
                }
            }

            if (error.isNotBlank()) {
                Text(
                    "⚠ $error",
                    color = Color(0xFFA9D7F5),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(210.dp),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.channel.name + it.channel.url }) { entry ->
                    ChannelTile(
                        entry = entry,
                        isFav = favs.contains(entry.channel.url),
                        onToggleFav = {
                            val added = store.toggleTvFavorite(entry.channel.url)
                            favs = store.tvFavorites()
                            AppLog.log(
                                "tv",
                                if (added) "в избранное: «${entry.channel.name}»" else "из избранного: «${entry.channel.name}»",
                            )
                        },
                    ) { onPlay(entry.channel, shownChannels) }
                }
            }
        }
    }
}

/** Компактная кнопка-«пилюля» (группы, обновление). */
@Composable
private fun TvPill(text: String, active: Boolean = false, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (active) Color(0xFF1E3A4D) else Color(0xFF1F2430),
            contentColor = if (active) Color(0xFFA9D7F5) else Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color(0xFFA9D7F5),
            focusedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Text(text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

/** Инициалы для плашки вместо логотипа: «Первый канал» → «ПК». */
private fun initials(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
        .map { it.first().uppercaseChar() }.joinToString("")

/** Плитка канала: логотип, имя, «сейчас идёт», кнопка избранного. */
@Composable
private fun ChannelTile(
    entry: TvEntry,
    isFav: Boolean,
    onToggleFav: () -> Unit,
    onClick: () -> Unit,
) {
    val channel = entry.channel
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = clickableSurfaceShape(RoundedCornerShape(12.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1A1F29),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF2B3648),
            focusedContentColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Логотип канала. Внешние хосты логотипов (imgur, wikimedia…)
                // часто мертвы или пусты в плейлисте — тогда под картинкой
                // видна плашка с инициалами: чёрных квадратов больше нет.
                // Инициалы рисуем только пока логотип не загрузился: PNG с
                // прозрачным фоном просвечивает, и буквы «перекрывали» логотип.
                Box(
                    Modifier
                        .height(30.dp)
                        .width(52.dp)
                        .background(Color(0xFF10141B), RoundedCornerShape(6.dp))
                        .padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    var logoLoaded by remember(channel.logo) { mutableStateOf(false) }
                    if (channel.logo.isNotBlank()) {
                        AsyncImage(
                            model = channel.logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            onSuccess = { logoLoaded = true },
                            onError = { logoLoaded = false },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (!logoLoaded) {
                        Text(
                            initials(channel.name),
                            color = Color(0xFFA9D7F5),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "● ${channel.group}" + if (channel.quality.isNotBlank()) " · ${channel.quality}" else "",
                    color = Color(0xFFA9D7F5),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Избранное: отдельная кнопка, не мешает открытию канала
                androidx.tv.material3.Surface(
                    onClick = onToggleFav,
                    shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = if (isFav) Color(0xFF1E3A4D) else Color(0xFF232936),
                        contentColor = if (isFav) Color(0xFFA9D7F5) else Color.White.copy(alpha = 0.6f),
                        focusedContainerColor = Color(0xFFA9D7F5),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) {
                    Text(
                        if (isFav) "★" else "☆",
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Column {
                Text(
                    channel.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val now = entry.now
                if (now != null) {
                    Text(
                        "Сейчас: ${now.title}",
                        color = Color(0xFF9FB0C0),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Полоска прогресса передачи: видно, сколько осталось
                    val total = (now.end - now.start).coerceAtLeast(1)
                    val passed = (System.currentTimeMillis() - now.start).coerceIn(0, total)
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color(0xFF2B3648), RoundedCornerShape(2.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(passed.toFloat() / total)
                                .height(3.dp)
                                .background(Color(0xFFA9D7F5), RoundedCornerShape(2.dp)),
                        )
                    }
                } else {
                    Text(
                        "прямой эфир",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
