package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.tv.material3.Button
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import com.torrenttv.app.data.Genre
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.MainActivity
import com.torrenttv.app.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Единая форма для всех состояний кликабельного Surface (tv-material 1.0.0 требует обёртку). */
@Composable
fun clickableSurfaceShape(shape: Shape): ClickableSurfaceShape =
    ClickableSurfaceDefaults.shape(shape, shape, shape, shape)

/**
 * Touch-обработка для tv-material Surface: тот кликается только с пульта (D-pad),
 * мышь в TV-режиме он игнорирует. Модификатор добавляет обработку клика мышью —
 * удобно для проверки интерфейса в эмуляторе.
 */
fun Modifier.mouseClick(onClick: () -> Unit): Modifier =
    pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }

/** Hero-баннер: бэкдроп первой карточки трендов + быстрый переход. */
@Composable
fun HeroBanner(item: MediaItem, onOpenDetails: (MediaItem) -> Unit) {
    Surface(
        onClick = { onOpenDetails(item) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 8.dp)
            .height(340.dp)
            .mouseClick { onOpenDetails(item) },
        shape = clickableSurfaceShape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1F2430),
            focusedContainerColor = Color(0xFF1F2430),
        ),
    ) {
        Box {
            if (item.backdropUrl.isNotBlank()) {
                AsyncImage(
                    model = item.backdropUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                    contentScale = ContentScale.Crop,
                )
            } else if (item.posterUrl.isNotBlank()) {
                AsyncImage(
                    model = item.posterUrl,
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
                    modifier = Modifier.mouseClick { onOpenDetails(item) },
                    shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFFF5B942),
                        contentColor = Color(0xFF04141C),
                        focusedContainerColor = Color(0xFFFFD07A),
                        focusedContentColor = Color(0xFF04141C),
                        pressedContainerColor = Color(0xFFE0A82E),
                        pressedContentColor = Color(0xFF04141C),
                        disabledContainerColor = Color(0xFFF5B942),
                        disabledContentColor = Color(0xFF04141C),
                    ),
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

/** Текущая ширина карточки постера (dp) — меняется в Настройках («Размер карточек»). */
var cardWidthDp by mutableStateOf(170)

/** Карточка с постером — фокус берётся с пульта, по ОК открывается карточка. */
@Composable
fun PosterCard(item: MediaItem, onClick: () -> Unit) {
    val w = cardWidthDp
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(w.dp)
            .padding(6.dp)
            .mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1F2430),
            focusedContainerColor = Color(0xFF2A3140),
        ),
    ) {
        Column {
            Box {
                if (item.posterUrl.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((w * 250 / 170).dp)
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
                            .height((w * 250 / 170).dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                // Заметный бейдж рейтинга поверх постера
                if (item.rating > 0.0) {
                    Text(
                        text = "★ ${"%.1f".format(item.rating)}",
                        color = Color(0xFF0E1116),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color(0xFFF5B942), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
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

/**
 * Лента «Продолжить просмотр»: карточка с постером, полосой прогресса
 * (как в HD VideoBox / Lampa) и меткой эпизода.
 */
@Composable
fun ContinueRow(
    title: String,
    entries: List<com.torrenttv.app.data.WatchEntry>,
    onClick: (com.torrenttv.app.data.MediaItem) -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 28.dp)) {
            items(entries.size) { i ->
                val e = entries[i]
                val frac = if (e.durationSec > 0)
                    (e.positionSec.toFloat() / e.durationSec).coerceIn(0.02f, 1f) else 0f
                Surface(
                    onClick = { onClick(e.item) },
                    modifier = Modifier
                        .width(190.dp)
                        .padding(6.dp),
                    shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1F2430),
                        focusedContainerColor = Color(0xFF2A3140),
                    ),
                ) {
                    Column {
                        Box {
                            if (e.item.posterUrl.isBlank()) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                        .background(Color(0xFF1F2430)),
                                    contentAlignment = Alignment.Center,
                                ) { Text("нет\nпостера", color = Color.White.copy(alpha = 0.4f), textAlign = TextAlign.Center) }
                            } else {
                                AsyncImage(
                                    model = e.item.posterUrl,
                                    contentDescription = e.item.title,
                                    modifier = Modifier.fillMaxWidth().height(120.dp),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            if (e.episodeLabel.isNotBlank()) {
                                Text(
                                    e.episodeLabel,
                                    color = Color.Black,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                        .background(Color(0xFFF5B942), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        // Прогресс-полоса: досмотрено — жёлтым
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .height(5.dp)
                                .background(Color(0xFF3A4250), RoundedCornerShape(3.dp)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(frac)
                                    .fillMaxHeight()
                                    .background(Color(0xFFF5B942), RoundedCornerShape(3.dp)),
                            )
                        }
                        Text(
                            e.item.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(8.dp, 0.dp, 8.dp, 0.dp),
                        )
                        val left = (e.durationSec - e.positionSec).coerceAtLeast(0)
                        Text(
                            if (left > 60) "осталось ${left / 60} мин" else "почти закончился",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp, 2.dp, 8.dp, 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Горизонтальная полоса каталога. */
@Composable
fun MediaRow(    title: String,
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
    // Первая загрузка не удалась (сеть/лимит API) — показать «Повторить» вместо вечного «Загрузка…»
    var failed by remember(keyTag) { mutableStateOf(false) }
    val busy = remember(keyTag) { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun requestNext() {
        if (busy.get() || exhausted) return
        busy.set(true)
        failed = false
        scope.launch {
            try {
                // Грузим страницы, пока не наберётся видимый контент после фильтра
                var attempts = 0
                while (attempts < 5 && !exhausted) {
                    val next = withContext(Dispatchers.IO) { loader(page + 1) }
                    if (next.isEmpty()) {
                        // Пустой ответ на ПЕРВОЙ странице = источник недоступен/лимит,
                        // а не «контент закончился»
                        if (page == 0) failed = true
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

    fun retry() {
        items = emptyList()
        page = 0
        exhausted = false
        failed = false
        requestNext()
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
            if (failed) {
                Text(
                    text = "Не удалось загрузить: источник недоступен или исчерпан лимит API.\nПроверьте сеть в настройках и повторите.",
                    color = Color(0xFFFFD54F),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Button(
                    onClick = { retry() },
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                ) { Text("🔄 Повторить") }
            } else {
                Text(
                    text = "Загрузка…",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
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

/** Левый навигационный бар: логотип + вертикальные кнопки (иконка + подпись). */
@Composable
fun SideNav(active: Screen, onTab: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .width(104.dp)
            .fillMaxHeight()
            .background(Color(0xFF12161F))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "ВНос\nКино",
            color = Color(0xFFF5B942),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        SideBarButton("🏠", "Главная", Screen.Home, active, onTab)
        SideBarButton("🎬", "Фильмы", Screen.Movies, active, onTab)
        SideBarButton("📺", "Сериалы", Screen.TvShows, active, onTab)
        SideBarButton("🎪", "Шоу", Screen.Shows, active, onTab)
        SideBarButton("⭐", "Избранное", Screen.Favorites, active, onTab)
        SideBarButton("🔍", "Поиск", Screen.Search, active, onTab)
        SideBarButton("⚙", "Настройки", Screen.Settings, active, onTab)
    }
}

@Composable
fun SideBarButton(
    icon: String,
    label: String,
    target: Screen,
    active: Screen,
    onTab: (Screen) -> Unit,
) {
    val isActive = active == target
    Surface(
        onClick = { onTab(target) },
        modifier = Modifier
            .width(92.dp)
            .mouseClick { onTab(target) },
        shape = clickableSurfaceShape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isActive) Color(0xFFF5B942) else Color.Transparent,
            contentColor = if (isActive) Color(0xFF0E1116) else Color.White,
            focusedContainerColor = Color(0xFFFFD07A),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(text = icon, fontSize = 22.sp)
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/** Экран избранного: сетка карточек. */
@Composable
fun FavoritesScreen(onOpenDetails: (MediaItem) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalStore(context) }
    var favs by remember { mutableStateOf(store.favorites()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { favs = store.favorites() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        Text(
            text = "⭐ Избранное",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp),
        )
        if (favs.isEmpty()) {
            Text(
                "Пока пусто — добавляйте карточки кнопкой «☆ В избранное» в меню «⋯ Ещё»",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        } else {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(6),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 28.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(favs.size) { i ->
                    PosterCard(favs[i]) { onOpenDetails(favs[i]) }
                }
            }
        }
    }
}

/**
 * Чип-галочка для фильтра стран: ✓ и золотая заливка если отмечено,
 * приглушённый стиль если нет. Клик/ОК переключает.
 */
@Composable
fun CheckChip(label: String, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.padding(4.dp).mouseClick { onToggle() },
        shape = clickableSurfaceShape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (checked) Color(0xFFF5B942) else Color(0xFF1F2430),
            contentColor = if (checked) Color(0xFF0E1116) else Color.White.copy(alpha = 0.75f),
            focusedContainerColor = Color(0xFFFFD07A),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
            disabledContainerColor = Color(0xFF1F2430),
            disabledContentColor = Color.White,
        ),
    ) {
        Text(
            text = if (checked) "☑ $label" else "☐ $label",
            color = if (checked) Color(0xFF0E1116) else Color.White.copy(alpha = 0.75f),
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GenreChip(label: String, isSelected: Boolean, onClick: () -> Unit) {    Surface(
        onClick = onClick,
        modifier = Modifier.padding(4.dp).mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Color(0xFFF5B942) else Color(0xFF1F2430),
            contentColor = if (isSelected) Color(0xFF0E1116) else Color.White,
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
            color = if (isSelected) Color.Black else Color.White,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
