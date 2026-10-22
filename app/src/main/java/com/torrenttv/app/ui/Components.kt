package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.torrenttv.app.data.Genre
import com.torrenttv.app.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Hero-баннер: бэкдроп первой карточки трендов + быстрый переход. */
@Composable
fun HeroBanner(item: MediaItem, onOpenDetails: (MediaItem) -> Unit) {
    Surface(
        onClick = { onOpenDetails(item) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 8.dp)
            .height(340.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Box {
            if (item.backdropUrl.isNotBlank()) {
                AsyncImage(
                    model = item.backdropUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxWidth().height(340.dp).background(Color(0xFF274B63)))
            }
            // Градиент затемнения слева, чтобы текст читался
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0f to Color(0xE60E1116),
                            0.6f to Color(0x550E1116),
                            1f to Color(0x000E1116),
                        ),
                    ),
            )
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 40.dp)
                    .width(460.dp),
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        if (item.rating > 0.0) append("★ ${"%.1f".format(item.rating)}  ·  ")
                        if (item.year.isNotBlank()) append("${item.year}  ·  ")
                        append(item.typeLabel)
                    },
                    color = Color(0xFFF5B942),
                    fontSize = 17.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Surface(
                    onClick = { onOpenDetails(item) },
                    color = Color(0xFFF5B942),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "▶  Подробнее",
                        color = Color(0xFF04141C),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** Карточка с постером — фокус берётся с пульта, по ОК открывается карточка. */
@Composable
fun PosterCard(item: MediaItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(170.dp)
            .padding(6.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column {
            if (item.posterUrl.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(Color(0xFF1F2430)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("нет\nпостера", color = Color.White.copy(alpha = 0.4f), textAlign = TextAlign.Center)
                }
            } else {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp, 6.dp, 8.dp, 0.dp),
            )
            if (item.year.isNotBlank() || item.rating > 0.0) {
                Text(
                    text = buildString {
                        if (item.rating > 0.0) append("★ ${"%.1f".format(item.rating)}")
                        if (item.year.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(item.year)
                        }
                    },
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp, 2.dp, 8.dp, 8.dp),
                )
            }
        }
    }
}

/** Горизонтальная полоса каталога. */
@Composable
fun MediaRow(
    title: String,
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    emptyText: String? = null,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
        )
        if (items.isEmpty() && emptyText != null) {
            Text(
                text = emptyText,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 28.dp)) {
                items(items.size) { i ->
                    PosterCard(items[i]) { onClick(items[i]) }
                }
            }
        }
    }
}

/**
 * Полоса каталога с бесконечной подгрузкой страниц.
 * Долистали до конца (3 карточки до края) — автоматически грузится следующая страница.
 *
 * @param loader блокирующая функция загрузки страницы (вызывается в IO-потоке)
 * @param keyTag смена значения сбрасывает ленту (например, при смене жанра)
 */
@Composable
fun PagedMediaRow(
    title: String,
    loader: suspend (Int) -> List<MediaItem>,
    onOpen: (MediaItem) -> Unit,
    keyTag: Any? = null,
    filterFn: (MediaItem) -> Boolean = { true },
) {
    var items by remember(keyTag) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var page by remember(keyTag) { mutableIntStateOf(0) }
    var exhausted by remember(keyTag) { mutableStateOf(false) }
    val busy = remember(keyTag) { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun requestNext() {
        if (busy.get() || exhausted) return
        busy.set(true)
        scope.launch {
            try {
                // Грузим страницы, пока не наберётся видимый контент после фильтра
                var attempts = 0
                while (attempts < 5 && !exhausted) {
                    val next = withContext(Dispatchers.IO) { loader(page + 1) }
                    if (next.isEmpty()) {
                        exhausted = true
                        break
                    }
                    page += 1
                    val filtered = next.filter(filterFn)
                    if (filtered.isNotEmpty()) {
                        items = items + filtered
                        break
                    }
                    attempts += 1
                }
            } finally {
                busy.set(false)
            }
        }
    }

    // Сброс и первая страница
    LaunchedEffect(keyTag) {
        items = emptyList()
        page = 0
        exhausted = false
        requestNext()
    }

    // Долистали до конца — грузим следующую страницу
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (last != null && items.isNotEmpty() && last >= items.size - 3) {
                    requestNext()
                }
            }
    }

    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
        )
        if (items.isEmpty()) {
            Text(
                text = "Загрузка…",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        } else {
            LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 28.dp)) {
                items(items.size) { i ->
                    PosterCard(items[i]) { onOpen(items[i]) }
                }
                if (!exhausted) {
                    item {
                        Box(
                            Modifier.width(70.dp).height(250.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("…", color = Color.White.copy(alpha = 0.5f), fontSize = 26.sp) }
                    }
                }
            }
        }
    }
}

/**
 * Полоса жанровых фильтров. */
@Composable
fun GenreChips(
    genres: List<Genre>,
    selected: Genre?,
    onSelect: (Genre?) -> Unit,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 28.dp)) {
        item {
            GenreChip("Все жанры", selected == null) { onSelect(null) }
        }
        items(genres.size) { i ->
            val g = genres[i]
            GenreChip(g.name, selected?.id == g.id) { onSelect(g) }
        }
    }
}

/** Мульти-выбор жанров (для настроек: выделено = исключено). */
@Composable
fun GenreChipsMulti(
    genres: List<Genre>,
    selectedIds: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    LazyRow {
        items(genres.size) { i ->
            val g = genres[i]
            GenreChip(g.name, g.id in selectedIds) { onToggle(g.id) }
        }
    }
}

@Composable
private fun GenreChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(4.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) Color(0xFFF5B942) else Color(0xFF1F2430),
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
