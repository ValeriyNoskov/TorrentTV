package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

/**
 * Общий LocalStore для UI-мелочей (бейджи новых серий в PosterCard) —
 * SharedPreferences дёшевы и читаются из кеша в памяти, объект один на процесс.
 */
object LocalStoreShared {
    @Volatile private var inst: LocalStore? = null
    fun get(context: android.content.Context): LocalStore =
        inst ?: synchronized(this) {
            inst ?: LocalStore(context.applicationContext).also { inst = it }
        }
}

/**
 * Клавиатура как модалка снизу (как Gboard TV): затемнение + нижняя панель
 * со строкой ввода и клавиатурой. Контент экрана остаётся видимым сверху.
 * «Готово»/doneLabel закрывает (или вызывает действие хозяина — «Найти» и т.п.).
 */
@Composable
fun KeyboardSheet(
    value: String,
    title: String = "Ввод",
    doneLabel: String = "✓ Готово",
    onDone: (() -> Unit)? = null,
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    /** Клик по чипу-подсказке: хозяин заменяет весь текст. */
    onSuggest: (String) -> Unit = {},
) {
    val kbFocus = remember { FocusRequester() }
    // Подсказки слов: история поисков + названия открытых карточек,
    // фильтр по набранному (вхождение без учёта регистра), топ-6
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val suggestions = remember(value) {
        if (value.length < 2) emptyList()
        else runCatching {
            val st = LocalStoreShared.get(appCtx)
            (st.recentQueries() + st.kbWords())
                .distinct()
                .filterNot { it.equals(value, ignoreCase = true) }
                .filter { it.contains(value.trim(), ignoreCase = true) }
                .take(6)
        }.getOrDefault(emptyList())
    }
    androidx.activity.compose.BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        // Затемнение поверх контента; клик мышью — закрыть
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .mouseClick { onDismiss() },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xF5171C26), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
                    Text(
                        value.ifBlank { "…" },
                        color = Color(0xFFA9D7F5),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Surface(
                    onClick = { (onDone ?: onDismiss).invoke() },
                    shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1F2430),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFA9D7F5),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text(doneLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp)) }
            }
            if (suggestions.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    suggestions.forEach { word ->
                        Surface(
                            onClick = { onSuggest(word) },
                            shape = clickableSurfaceShape(RoundedCornerShape(20.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF242B3A),
                                contentColor = Color(0xFFE8ECF4),
                                focusedContainerColor = Color(0xFFA9D7F5),
                                focusedContentColor = Color.Black,
                            ),
                        ) {
                            Text(
                                word,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            TvKeyboard(
                onChar = onChar,
                onBackspace = onBackspace,
                onClear = onClear,
                firstKeyFocus = kbFocus,
            )
        }
    }
}

/**
 * Пункт списка фильтра: id может быть составным («y:2025», «p:2020+», «r:7»).
 */
data class FilterOption(val id: String, val label: String, val checked: Boolean)

/**
 * Модалка фильтра «как в Зоне»: поиск сверху (в фокусе при открытии),
 * ниже вертикальный список с галочками — ↑↓ листает, ОК ставит/снимает
 * галочку. Курсор не «прыгает» по странице. singleSelect — радио-режим
 * (Тип/Рейтинг): новая галочка снимает предыдущую.
 */
@Composable
fun FilterListDialog(
    title: String,
    options: List<FilterOption>,
    onToggle: (FilterOption) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var keyboard by remember { mutableStateOf(false) }
    val listFocus = remember { FocusRequester() }

    androidx.activity.compose.BackHandler { onClose() }

    // Затемнение поверх каталога; сам каталог виден по краям
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .mouseClick { onClose() },
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .width(460.dp)
                .background(Color(0xFF141922), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF2A3648), RoundedCornerShape(16.dp)),
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 14.dp),
            )
            // Поиск: всегда первое поле; ОК — клавиатурная шторка
            Surface(
                onClick = { keyboard = true },
                shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF1F2430),
                    contentColor = Color(0xFF8A94A6),
                    focusedContainerColor = Color(0xFFA9D7F5),
                    focusedContentColor = Color(0xFF0E1116),
                ),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    // mouseClick — чтобы Surface кликался тапом мыши в эмуляторе
                    .mouseClick { keyboard = true },
            ) {
                Text(
                    if (query.isBlank()) "🔍 Найти фильтр…" else "🔍 $query",
                    fontSize = 15.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
            // Список с галочками, сжатый поиском по подстроке
            val shown = if (query.isBlank()) options else options.filter {
                it.label.contains(query.trim(), ignoreCase = true)
            }
            LazyColumn(
                Modifier
                    .padding(horizontal = 10.dp)
                    .height(340.dp),
            ) {
                items(shown, key = { it.id }) { opt ->
                    androidx.tv.material3.Surface(
                        onClick = { onToggle(opt) },
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color(0xFFC6CFDD),
                            focusedContainerColor = Color(0xFFA9D7F5),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .mouseClick { onToggle(opt) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            // Квадрат-галочка: тёмный фон с голубой ✓, если включено
                            Box(
                                Modifier
                                    .padding(end = 12.dp)
                                    .size(20.dp)
                                    .background(
                                        if (opt.checked) Color(0xFF0E1116) else Color.Transparent,
                                        RoundedCornerShape(5.dp),
                                    )
                                    .border(
                                        2.dp,
                                        if (opt.checked) Color(0xFF0E1116) else Color(0xFF55617A),
                                        RoundedCornerShape(5.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (opt.checked) {
                                    Text(
                                        "✓",
                                        color = Color(0xFFA9D7F5),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                    )
                                }
                            }
                            Text(
                                opt.label,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val chosen = options.count { it.checked }
                Surface(
                    onClick = onClose,
                    shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFFA9D7F5),
                        contentColor = Color(0xFF0E1116),
                        focusedContainerColor = Color(0xFFBFE3F7),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                    modifier = Modifier.mouseClick { onClose() },
                ) {
                    Text(
                        "✓ Применить",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    onClick = onReset,
                    shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1F2430),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFA9D7F5),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                    modifier = Modifier.mouseClick { onReset() },
                ) {
                    Text(
                        "Сбросить",
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (chosen > 0) "Выбрано: $chosen" else title,
                    color = Color(0xFF8A94A6),
                    fontSize = 13.sp,
                )
            }
        }
    }

    if (keyboard) {
        KeyboardSheet(
            value = query,
            title = "Поиск фильтра",
            onDone = { keyboard = false },
            onChar = { query += it },
            onBackspace = { query = query.dropLast(1) },
            onClear = { query = "" },
            onDismiss = { keyboard = false },
        )
    }
}

/**
 * Универсальный чип-пикер фильтров (по образцу Кинопоиска): чипы в несколько
 * рядов (FlowRow) вместо прокрутки одной строки, поиск-фильтр по названию
 * и «Снять все». Вызывается прямо из каталогов кнопкой «⚙ Фильтры».
 */
@Composable
fun FilterPicker(
    title: String,
    groups: List<Pair<String, List<com.torrenttv.app.data.Genre>>>,
    excluded: Set<Int>,
    onToggle: (com.torrenttv.app.data.Genre) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
) {
    var filterOn by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onClose,
                shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF1F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFA9D7F5),
                    focusedContentColor = Color.Black,
                ),
            ) { Text("← Готово", fontSize = 17.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
            Spacer(Modifier.width(16.dp))
            Text(
                "$title · исключено: ${excluded.size}",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Surface(
                onClick = { filterOn = !filterOn; if (!filterOn) filter = "" },
                shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF1F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFA9D7F5),
                    focusedContentColor = Color.Black,
                ),
            ) { Text(if (filterOn) "✕ Скрыть поиск" else "🔎 Поиск", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
            Spacer(Modifier.width(12.dp))
            Surface(
                onClick = onClearAll,
                enabled = excluded.isNotEmpty(),
                shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF1F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFA9D7F5),
                    focusedContentColor = Color.Black,
                    disabledContainerColor = Color(0xFF141922),
                    disabledContentColor = Color.White.copy(alpha = 0.3f),
                ),
            ) { Text("🗑 Снять все", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
            if (filterOn) {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = if (filter.isBlank()) "Введите часть названия…" else filter,
                    color = if (filter.isBlank()) Color.White.copy(alpha = 0.35f) else Color(0xFFA9D7F5),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp),
        ) {
            groups.forEach { (label, items) ->
                val visible = if (filter.isBlank()) items
                else items.filter { it.name.contains(filter, ignoreCase = true) }
                if (visible.isEmpty()) return@forEach
                Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 17.sp)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
                ) {
                    visible.forEach { g ->
                        CheckChip(g.name, g.id in excluded) { onToggle(g) }
                    }
                }
            }
            if (filter.isNotBlank() && groups.all { (_, items) ->
                    items.none { it.name.contains(filter, ignoreCase = true) }
                }
            ) {
                Text(
                    "Ничего не найдено по «$filter»",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 17.sp,
                )
            }
        }
        if (filterOn) {
            KeyboardSheet(
                value = filter,
                title = "Поиск по названию",
                onChar = { ch -> filter += ch },
                onBackspace = { if (filter.isNotEmpty()) filter = filter.dropLast(1) },
                onClear = { filter = "" },
                onDismiss = { filterOn = false },
                onSuggest = { filter = it },
            )
        }
    }
}

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

/**
 * Экран не гаснет, пока открыт плеер: телевизор засыпал по таймауту посреди
 * фильма (стоит на паузе/долгая буферизация — и экран выключается).
 * Флаг снимается при уходе с экрана автоматически.
 */
@Composable
fun KeepScreenOn(enabled: Boolean = true) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, enabled) {
        val window = (view.context as? android.app.Activity)?.window
        if (enabled) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            // Аудио-режим: экран ТВ может погаснуть по таймауту, звук продолжается
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

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
                        if (item.rating > 0.0) append("★︎ ${"%.1f".format(item.rating)}  ·  ")
                        if (item.year.isNotBlank()) append("${item.year}  ·  ")
                        append(item.typeLabel)
                    },
                    color = Color(0xFFA9D7F5),
                    fontSize = 17.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Surface(
                    onClick = { onOpenDetails(item) },
                    modifier = Modifier.mouseClick { onOpenDetails(item) },
                    shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFFA9D7F5),
                        contentColor = Color(0xFF04141C),
                        focusedContainerColor = Color(0xFFCDE9FA),
                        focusedContentColor = Color(0xFF04141C),
                        pressedContainerColor = Color(0xFF7FBDE8),
                        pressedContentColor = Color(0xFF04141C),
                        disabledContainerColor = Color(0xFFA9D7F5),
                        disabledContentColor = Color(0xFF04141C),
                    ),
                ) {
                    Text(
                        text = "▶︎  Подробнее",
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
    // Бейдж «N новых серий» для сериалов: LocalStore — дешёвые SharedPreferences,
    // читаем лениво и только для type=tv (один remember на карточку).
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val newEps = if (item.type == "tv") {
        remember(item.id) {
            runCatching { LocalStoreShared.get(appCtx).newEpisodesCount(item) }.getOrDefault(0)
        }
    } else 0
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
                        text = "★︎ ${"%.1f".format(item.rating)}",
                        color = Color(0xFF0E1116),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color(0xFFA9D7F5), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                // 🟢 Бейдж новых серий: «+2 сер.» — появились в кеше после
                // последнего открытия списка серий
                if (newEps > 0) {
                    Text(
                        text = "+$newEps сер.",
                        color = Color(0xFF062B10),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color(0xFF7FB84F), RoundedCornerShape(8.dp))
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
                        if (item.rating > 0.0) append("★︎ ${"%.1f".format(item.rating)}")
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
                                        .background(Color(0xFFA9D7F5), RoundedCornerShape(6.dp))
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
                                    .background(Color(0xFFA9D7F5), RoundedCornerShape(3.dp)),
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
                    color = Color(0xFFA9D7F5),
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
                    focusedContentColor = Color(0xFFA9D7F5),
                    pressedContainerColor = Color(0xFF223047),
                    pressedContentColor = Color(0xFFA9D7F5),
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
                    focusedContentColor = Color(0xFFA9D7F5),
                    pressedContainerColor = Color(0xFF223047),
                    pressedContentColor = Color(0xFFA9D7F5),
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
                    color = Color(0xFFBFE3F7),
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

/**
 * Полоса сортировки каталога (пары: ключ → подпись). Тот же стиль, что жанры.
 */
@Composable
fun SortChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 28.dp)) {
        items(options.size) { i ->
            val (key, label) = options[i]
            GenreChip(label, selected == key) { onSelect(key) }
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
fun SideNav(active: Screen, onTab: (Screen) -> Unit, restoreFocusTick: Int = 0) {
    var expanded by remember { mutableStateOf(false) }
    // Фокус на активном пункте: после возврата из плеера/карточки Compose
    // отдаёт фокус ПЕРВОЙ кнопке («Главная»), хотя подсвечен текущий экран.
    // restoreFocusTick>0 — сигнал «мы вернулись на вкладку»: ставим фокус
    // на активный (жёлтый) пункт меню.
    val activeFocus = remember(active) { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(restoreFocusTick, active) {
        if (restoreFocusTick > 0) {
            kotlinx.coroutines.delay(250) // даём композиции вкладки устояться
            runCatching { activeFocus.requestFocus() }
        }
    }
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
                color = Color(0xFFA9D7F5),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        } else {
            Text(
                text = "ВНК",
                color = Color(0xFFA9D7F5),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        SideBarButton("🏠", "Главная", Screen.Home, active, onTab, expanded,
            focusRequester = activeFocus.takeIf { active == Screen.Home }) { expanded = it }
        SideBarButton("▶︎", "Продолжить", Screen.Continue, active, onTab, expanded,
            focusRequester = activeFocus.takeIf { active == Screen.Continue }) { expanded = it }
        SideBarButton("⭐", "Избранное", Screen.Favorites, active, onTab, expanded,
            focusRequester = activeFocus.takeIf { active == Screen.Favorites }) { expanded = it }
        // «Смотреть позже» убрано: дублировало избранное — есть фильтр
        // «Фильмы / Сериалы» (решение владельца).
        SideBarButton("🎬", "Фильмы", Screen.Movies, active, onTab, expanded,
            focusRequester = activeFocus.takeIf { active == Screen.Movies }) { expanded = it }
        SideBarButton("📺", "Сериалы", Screen.TvShows, active, onTab, expanded,
            focusRequester = activeFocus.takeIf { active == Screen.TvShows }) { expanded = it }
        SideBarButton("🎪", "Шоу", Screen.Shows, active, onTab, expanded,
            focusRequester = activeFocus.takeIf { active == Screen.Shows }) { expanded = it }
        SideBarButton("📡", "ТВ", Screen.Tv, active, onTab, expanded,
            focusRequester = activeFocus.takeIf { active == Screen.Tv }) { expanded = it }
        SideBarButton("🧲", "Торренты", Screen.Torrents, active, onTab, expanded,
            focusRequester = activeFocus.takeIf { active == Screen.Torrents }) { expanded = it }
        SideBarButton("🔍", "Поиск", Screen.Search, active, onTab, expanded,
            focusRequester = activeFocus.takeIf { active == Screen.Search }) { expanded = it }
        SideBarButton("⚙", "Настройки", Screen.Settings, active, onTab, expanded,
            focusRequester = activeFocus.takeIf { active == Screen.Settings }) { expanded = it }
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
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onFocusInBar: (Boolean) -> Unit = {},
) {
    val isActive = active == target
    Surface(
        onClick = { onTab(target) },
        modifier = Modifier
            .then(
                if (focusRequester != null)
                    Modifier.focusRequester(focusRequester) else Modifier
            )
            .width(if (expanded) 188.dp else 60.dp)
            .mouseClick { onTab(target) }
            .onFocusChanged { onFocusInBar(it.hasFocus) },
        shape = clickableSurfaceShape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isActive) Color(0xFFA9D7F5) else Color.Transparent,
            contentColor = if (isActive) Color(0xFF0E1116) else Color.White,
            focusedContainerColor = Color(0xFFCDE9FA),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFF7FBDE8),
            pressedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // В свёрнутом баре иконка — по центру кнопки, иначе прижата влево
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
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
                horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
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
                        color = Color(0xFFA9D7F5),
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
            text = "▶︎ Продолжить просмотр",
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
                                        .background(Color(0xFFA9D7F5)),
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

/** Экран избранного: сетка карточек + фильтр «Все / Фильмы / Сериалы». */
@Composable
fun FavoritesScreen(onOpenDetails: (MediaItem) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalStore(context) }
    var favs by remember { mutableStateOf(store.favorites()) }
    // Фильтр вместо отдельных полок «Позже» и «Буду смотреть»: одного
    // избранного достаточно, делим его на фильмы и сериалы (просьба владельца).
    var filter by remember { mutableStateOf("all") }
    androidx.compose.runtime.LaunchedEffect(Unit) { favs = store.favorites() }

    val shown = favs.filter {
        when (filter) {
            "movie" -> it.type == "movie"
            "tv" -> it.type == "tv"
            else -> true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp),
        ) {
            Text(
                text = "★︎ Избранное",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            if (favs.isNotEmpty()) {
                FilterChipTv("Все (${favs.size})", filter == "all") { filter = "all" }
                FilterChipTv("🎬 Фильмы (${favs.count { it.type == "movie" }})", filter == "movie") { filter = "movie" }
                FilterChipTv("📺 Сериалы (${favs.count { it.type == "tv" }})", filter == "tv") { filter = "tv" }
            }
        }
        if (favs.isEmpty()) {
            Text(
                "Пока пусто — добавляйте кнопкой «☆ В избранное» в карточке фильма",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        } else if (shown.isEmpty()) {
            Text(
                if (filter == "movie") "Фильмов в избранном нет" else "Сериалов в избранном нет",
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
                items(shown.size) { i ->
                    PosterCard(shown[i]) { onOpenDetails(shown[i]) }
                }
            }
        }
    }
}

/** Кнопка-фильтр на ТВ: золотая заливка у выбранного варианта. */
@Composable
private fun FilterChipTv(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = clickableSurfaceShape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFFA9D7F5) else Color(0xFF1F2430),
            contentColor = if (selected) Color(0xFF0E1116) else Color.White.copy(alpha = 0.8f),
            focusedContainerColor = if (selected) Color(0xFFCDE9FA) else Color(0xFF31435A),
            focusedContentColor = if (selected) Color(0xFF0E1116) else Color.White,
        ),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
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
            containerColor = if (checked) Color(0xFFA9D7F5) else Color(0xFF1F2430),
            contentColor = if (checked) Color(0xFF0E1116) else Color.White.copy(alpha = 0.75f),
            focusedContainerColor = Color(0xFFCDE9FA),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFF7FBDE8),
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
            containerColor = if (isSelected) Color(0xFFA9D7F5) else Color(0xFF1F2430),
            contentColor = if (isSelected) Color(0xFF0E1116) else Color.White,
            focusedContainerColor = Color(0xFFCDE9FA),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFF7FBDE8),
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
    seeds >= 5 -> androidx.compose.ui.graphics.Color(0xFFBFE3F7)
    else -> androidx.compose.ui.graphics.Color(0xFFE57373)
}
