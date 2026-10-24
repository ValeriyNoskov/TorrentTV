package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    onRemove: ((com.torrenttv.app.data.WatchEntry) -> Unit)? = null,
) {
    // Зажать OK на карточке → предложение убрать из просмотра
    var removeTarget by remember { mutableStateOf<com.torrenttv.app.data.WatchEntry?>(null) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            text = title + if (onRemove != null) "   (зажмите OK — убрать из просмотра)" else "",
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
                    onLongClick = { if (onRemove != null) removeTarget = e },
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

    // Диалог удаления: появился при зажатии OK на карточке.
    // Фокус сразу на «Убрать» — чтобы OK не ушёл в карточку под модалкой
    removeTarget?.let { target ->
        BackHandler { removeTarget = null }
        val removeFocus = remember { FocusRequester() }
        LaunchedEffect(target) { runCatching { removeFocus.requestFocus() } }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(420.dp)
                    .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                    .padding(18.dp),
            ) {
                Text(
                    "Убрать из просмотра?",
                    color = Color(0xFFF5B942),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    target.item.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                )
                Row {
                    Button(
                        onClick = {
                            removeTarget = null
                            onRemove?.invoke(target)
                        },
                        modifier = Modifier.focusRequester(removeFocus),
                    ) { Text("✕ Убрать") }
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { removeTarget = null }) { Text("Отмена") }
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
    onTitleClick: (() -> Unit)? = null,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        if (onTitleClick != null) {
            // Кликабельный заголовок: переход в каталог с этим типом
            Surface(
                onClick = onTitleClick,
                modifier = Modifier.padding(start = 24.dp),
                shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFF2A3648),
                    focusedContentColor = Color(0xFFF5B942),
                    pressedContainerColor = Color(0xFF223047),
                    pressedContentColor = Color(0xFFF5B942),
                ),
            ) {
                Text(
                    text = "$title  ›",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        } else {
            Text(
                text = title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
            )
        }
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
    onTitleClick: (() -> Unit)? = null,
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
        if (onTitleClick != null) {
            Surface(
                onClick = onTitleClick,
                modifier = Modifier.padding(start = 24.dp),
                shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFF2A3648),
                    focusedContentColor = Color(0xFFF5B942),
                    pressedContainerColor = Color(0xFF223047),
                    pressedContentColor = Color(0xFFF5B942),
                ),
            ) {
                Text(
                    text = "$title  ›",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        } else {
            Text(
                text = title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
            )
        }
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

/** Левый навигационный бар. Свёрнут — только ярлыки (иконки);
 *  фокус переходит в бар — раскрывается с подписями, ушёл — сворачивается. */
@Composable
fun SideNav(active: Screen, onTab: (Screen) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) 208.dp else 76.dp,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "sidenav",
    )
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(Color(0xFF12161F))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (expanded) {
            Text(
                text = "ВНосКино",
                color = Color(0xFFF5B942),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        } else {
            Text(
                text = "ВК",
                color = Color(0xFFF5B942),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        SideBarButton("🏠", "Главная", Screen.Home, active, onTab, expanded) { expanded = it }
        SideBarButton("▶", "Продолжить", Screen.Continue, active, onTab, expanded) { expanded = it }
        SideBarButton("⭐", "Избранное", Screen.Favorites, active, onTab, expanded) { expanded = it }
        SideBarButton("🕐", "Смотреть позже", Screen.WatchLater, active, onTab, expanded) { expanded = it }
        SideBarButton("🎬", "Фильмы", Screen.Movies, active, onTab, expanded) { expanded = it }
        SideBarButton("📺", "Сериалы", Screen.TvShows, active, onTab, expanded) { expanded = it }
        SideBarButton("🎪", "Шоу", Screen.Shows, active, onTab, expanded) { expanded = it }
        SideBarButton("🧲", "Торренты", Screen.Torrents, active, onTab, expanded) { expanded = it }
        SideBarButton("🔍", "Поиск", Screen.Search, active, onTab, expanded) { expanded = it }
        SideBarButton("⚙", "Настройки", Screen.Settings, active, onTab, expanded) { expanded = it }
        // Сразу после настроек — всегда видна, никаких прыжков вниз
        SideBarExitButton(expanded) { expanded = it }
    }
}

@Composable
fun SideBarButton(
    icon: String,
    label: String,
    target: Screen,
    active: Screen,
    onTab: (Screen) -> Unit,
    expanded: Boolean = true,
    onFocusInBar: (Boolean) -> Unit = {},
) {
    val isActive = active == target
    Surface(
        onClick = { onTab(target) },
        modifier = Modifier
            .width(if (expanded) 188.dp else 60.dp)
            .mouseClick { onTab(target) }
            .onFocusChanged { onFocusInBar(it.hasFocus) },
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = if (expanded) 12.dp else 0.dp),
        ) {
            Text(
                text = icon,
                fontSize = 21.sp,
                modifier = if (expanded) Modifier.padding(end = 10.dp) else Modifier,
            )
            if (expanded) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Left,
                )
            }
        }
    }
}

/** Кнопка «Выход» внизу левого меню — закрывает приложение. */
@Composable
fun SideBarExitButton(expanded: Boolean = true, onFocusInBar: (Boolean) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    androidx.tv.material3.Surface(
        onClick = { confirm = true },
        modifier = Modifier
            .width(if (expanded) 188.dp else 60.dp)
            .onFocusChanged { onFocusInBar(it.hasFocus) },
        shape = clickableSurfaceShape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color(0xFFE05B4D),
            focusedContainerColor = Color(0xFFE05B4D),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0xFFC44C3F),
            pressedContentColor = Color.White,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (expanded) 12.dp else 0.dp),
            ) {
                Text(text = "⛔", fontSize = 21.sp, modifier = if (expanded) Modifier.padding(end = 10.dp) else Modifier)
                if (expanded) {
                    Text(
                        text = "Выход",
                        fontSize = 14.sp,
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Left,
                    )
                }
            }
        }
    }

    // Подтверждение: случайно нажать «Выход» не страшно — фокус на «Отмене»
    if (confirm) {
        // Dialog — поверх ВСЕГО экрана. Обычный Box здесь сжимался
        // в ширину сайдбара (104dp): текст по букве, кнопки обрезаны
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { confirm = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val cancelFocus = remember { FocusRequester() }
            // Небольшая задержка: без неё requestFocus срабатывает до
            // фактической отрисовки Dialog и фокус остаётся на «Выйти»
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(150)
                runCatching { cancelFocus.requestFocus() }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .width(420.dp)
                        .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                        .padding(18.dp),
                ) {
                    Text(
                        "Выйти из приложения?",
                        color = Color(0xFFF5B942),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Нажали случайно? Выберите «Отмена».",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                    )
                    Row {
                        androidx.tv.material3.Button(
                            onClick = { confirm = false },
                            modifier = Modifier.focusRequester(cancelFocus),
                        ) { Text("Отмена") }
                        Spacer(Modifier.width(10.dp))
                        androidx.tv.material3.Button(onClick = {
                            com.torrenttv.app.data.AppLog.log("app", "выход через боковое меню (подтверждён)")
                            (context as? android.app.Activity)?.finishAffinity()
                        }) { Text("Выйти") }
                    }
                }
            }
        }
    }
}

/** Экран «Продолжить просмотр»: все незавершённые карточки с прогрессом. */
@Composable
fun ContinueScreen(onOpenDetails: (MediaItem) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalStore(context) }
    var entries by remember { mutableStateOf(store.continueWatching()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { entries = store.continueWatching() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        Text(
            text = "▶ Продолжить просмотр",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp),
        )
        if (entries.isEmpty()) {
            Text(
                "Пока пусто — начните фильм или серию, и он появится здесь",
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
                items(entries.size, key = { entries[it].item.storeKey }) { i ->
                    val e = entries[i]
                    Column {
                        PosterCard(e.item) { onOpenDetails(e.item) }
                        // Прогресс-полоска под постером
                        if (e.durationSec > 0) {
                            Box(
                                Modifier
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .background(Color(0xFF2A3648)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(
                                            (e.positionSec.toFloat() / e.durationSec).coerceIn(0f, 1f),
                                        )
                                        .height(4.dp)
                                        .background(Color(0xFFF5B942)),
                                )
                            }
                            Text(
                                text = buildString {
                                    if (e.episodeLabel.isNotBlank()) append(e.episodeLabel + " · ")
                                    val left = (e.durationSec - e.positionSec) / 60
                                    if (left > 0) append("осталось ~$left мин") else append("почти досмотрено")
                                },
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Экран «Смотреть позже»: отложенные карточки. */
@Composable
fun WatchLaterScreen(onOpenDetails: (MediaItem) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalStore(context) }
    var list by remember { mutableStateOf(store.watchLater()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { list = store.watchLater() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        Text(
            text = "🕐 Смотреть позже",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp),
        )
        if (list.isEmpty()) {
            Text(
                "Пока пусто — отмечайте фильмы кнопкой «＋ Смотреть позже» в карточке",
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
                items(list.size) { i ->
                    PosterCard(list[i]) { onOpenDetails(list[i]) }
                }
            }
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

/**
 * Бейджи качества раздачи из названия (Zona-стиль): источник релиза
 * (WEB-DL/BluRay/HDTV/DVD/CAM) и перевод (Дубляж/Проф./Многоголосый...).
 * Разрешение (2160p/1080p...) уже в TorrentResult.resolution.
 */
fun releaseBadges(name: String): List<Pair<String, androidx.compose.ui.graphics.Color>> {
    val n = name.uppercase()
    val out = mutableListOf<Pair<String, androidx.compose.ui.graphics.Color>>()
    val src = when {
        "WEB-DL" in n || "WEBDL" in n || "WEBRIP" in n || "WEB-RIP" in n -> "WEB-DL"
        "BDRIP" in n || "BLU-RAY" in n || "BLURAY" in n || "BDREMUX" in n || "REMUX" in n -> "BluRay"
        "HDTVRIP" in n || "HDTV" in n -> "HDTV"
        "DVDRIP" in n || "DVD" in n -> "DVD"
        "CAMRIP" in n || "CAM " in n || "TELECINE" in n || "TS " in n || "SCR " in n -> "CAM"
        else -> ""
    }
    if (src.isNotEmpty()) out += src to androidx.compose.ui.graphics.Color(0xFF9DB8FF)
    val tr = when {
        "ДУБЛИРОВАН" in n || "ДУБЛЯЖ" in n -> "Дубляж"
        "ПРОФЕССИОНАЛЬНЫЙ" in n || "ПРОФ." in n || "ПРОФ " in n -> "Проф."
        "МНОГОГОЛОС" in n -> "Многогол."
        "ЛЮБИТЕЛЬСКИЙ" in n || "ЛЮБИТ." in n || "ЛЮБИТ " in n -> "Любит."
        "ЗАКАДР" in n -> "Закадр."
        "ОДНОГОЛОС" in n -> "Одногол."
        "ОЗВУЧКА" in n -> "Озвучка"
        else -> ""
    }
    if (tr.isNotEmpty()) out += tr to androidx.compose.ui.graphics.Color(0xFFE3A0E3)
    return out
}

/** Цвет по количеству сидов: ≥50 — зелёный, ≥5 — жёлтый, меньше — красный. */
fun seedColor(seeds: Int): androidx.compose.ui.graphics.Color = when {
    seeds >= 50 -> androidx.compose.ui.graphics.Color(0xFF7EE2A8)
    seeds >= 5 -> androidx.compose.ui.graphics.Color(0xFFFFD54F)
    else -> androidx.compose.ui.graphics.Color(0xFFE57373)
}
