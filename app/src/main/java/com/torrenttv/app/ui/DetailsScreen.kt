package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.torrenttv.app.data.LocalStore
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.MediaApi
import com.torrenttv.app.data.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Карточка фильма/сериала: описание, избранное, magnet, просмотр и «Похожее». */
@Composable
fun DetailsScreen(
    item: MediaItem,
    onBack: () -> Unit,
    onWatch: () -> Unit = {},
    onEnterMagnet: () -> Unit = {},
    /** Авто-подбор раздачи и сразу просмотр (Zona-флоу). */
    onAutoSearch: () -> Unit = {},
    /** Умный просмотр: онлайн → торрент автоматически. */
    onWatchSmart: () -> Unit = {},
    /** Ручной выбор из списка раздач. */
    onManualSearch: () -> Unit = {},
    /** Трейлер внутри приложения (Rutube API + наш плеер). */
    onTrailer: () -> Unit = {},
    /** Бесплатный полный фильм/серия на Rutube (легальный стрим с рекламой). */
    onWatchRutube: (id: String, title: String, thumb: String) -> Unit = { _, _, _ -> },
    onOpenDetails: (MediaItem) -> Unit = {},
    onEpisodes: (Int) -> Unit = { },
    /** Играем конкретную серию (из блока «Сезоны и серии»). */
    onPlayEpisode: (com.torrenttv.app.data.Episode) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    var fav by remember { mutableStateOf(store.isFavorite(item)) }
    var later by remember { mutableStateOf(store.isWatchLater(item)) }
    // Статус: Смотрю / Буду смотреть / Просмотрено (цикл кнопкой)
    var statusTag by remember { mutableStateOf(store.itemStatus(item)) }
    fun statusLabel(s: String) = when (s) {
        "watching" -> "👁 Смотрю"; "want" -> "📋 Буду смотреть"; "done" -> "✔ Просмотрено"; else -> "🔖 Отметить"
    }
    var magnet by remember { mutableStateOf(store.getMagnet(item) != null) }
    var status by remember { mutableStateOf("") }
    var similar by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    // Карточка без меты (заглушка из поиска раздач): дотягиваем постер/описание
    // с КП прямо на экране деталей — иначе «нет постера» (жалоба по «Спецам»)
    var itemState by remember(item.storeKey) { mutableStateOf(item) }
    LaunchedEffect(item.storeKey) {
        if (itemState.posterUrl.isBlank()) {
            val en = withContext(Dispatchers.IO) { MediaApi.enrich(itemState) }
            if (en.posterUrl.isNotBlank()) itemState = en
        }
    }
    val watchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { watchFocus.requestFocus() }
    }

    LaunchedEffect(item) {
        similar = withContext(Dispatchers.IO) { MediaApi.similar(item) }
    }

    // Бесплатный легальный стрим: полные фильмы/серии на Rutube (открытый API)
    var rutubeFree by remember(item.storeKey) {
        mutableStateOf<List<com.torrenttv.app.data.RutubeService.RutubeVideo>>(emptyList())
    }
    LaunchedEffect(item.storeKey) {
        rutubeFree = withContext(Dispatchers.IO) {
            runCatching { com.torrenttv.app.data.RutubeService.searchContent(item.title) }
                .getOrDefault(emptyList())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Row(Modifier.weight(1f)) {
            // Постер (обогащённый из КП, если карточка была заглушкой)
            if (itemState.posterUrl.isBlank()) {
                Box(
                    Modifier
                        .width(190.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF1F2430), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("нет постера", color = Color.White.copy(alpha = 0.4f)) }
            } else {
                AsyncImage(
                    model = itemState.posterUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .width(190.dp)
                        .fillMaxHeight(),
                    contentScale = ContentScale.Crop,
                )
            }

            Spacer(Modifier.width(28.dp))

            // Колонка скроллится: длинное описание не наезжает на «Похожее»
            // и кнопку «Назад», всё остаётся доступным
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(item.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = buildString {
                        append(item.typeLabel)
                        if (item.year.isNotBlank()) append(" · ${item.year}")
                    },
                    color = Color(0xFFF5B942),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Рейтинги разных сервисов: КП и IMDb догружаются одним запросом
                // (/api/v1.2/movie/{id}, кеш 24ч). TMDB недоступен из РФ.
                var kpRating by remember(item.id) { mutableStateOf(0.0) }
                var imdbRating by remember(item.id) { mutableStateOf(0.0) }
                LaunchedEffect(item.id) {
                    withContext(Dispatchers.IO) {
                        com.torrenttv.app.data.KinopoiskClient.ratings(item.id)?.let { (kp, imdb) ->
                            kpRating = kp
                            imdbRating = imdb
                        }
                    }
                }
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    val kpShown = kpRating.takeIf { it > 0 } ?: item.rating
                    if (kpShown > 0) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF5B942))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "★ ${"%.1f".format(kpShown)} КП",
                                color = Color(0xFF0E1116),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    if (imdbRating > 0) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF2B2F3A))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "IMDb ${"%.1f".format(imdbRating)}",
                                color = Color(0xFFF5C518),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                // Статус сериала (ROADMAP №10): «идёт/завершён» и ближайшая серия.
                // TMDB (через DoH) — единственный надёжный источник; если его нет,
                // строка просто не показывается
                if (item.type == "tv") {
                    var tvStatus by remember(item.id) { mutableStateOf<TmdbClient.TvStatus?>(null) }
                    LaunchedEffect(item.id) {
                        val st = withContext(Dispatchers.IO) {
                            runCatching { TmdbClient.tvStatus(item.title, item.year) }.getOrNull()
                        }
                        tvStatus = st
                    }
                    tvStatus?.let { st ->
                        val date = { d: String ->
                            if (d.length >= 10) "${d.substring(8, 10)}.${d.substring(5, 7)}.${d.substring(0, 4)}" else d
                        }
                        val text = when {
                            st.running && st.nextSeason > 0 ->
                                "📺 Идёт · следующая серия S%02dE%02d".format(st.nextSeason, st.nextEpisode) +
                                    if (st.nextAirDate.isNotBlank()) " — ${date(st.nextAirDate)}" else ""
                            st.running -> "📺 Идёт · новые серии ожидаются"
                            st.lastSeason > 0 ->
                                "📺 Завершён · последняя серия S%02dE%02d".format(st.lastSeason, st.lastEpisode) +
                                    if (st.lastAirDate.isNotBlank()) " — ${date(st.lastAirDate)}" else ""
                            else -> ""
                        }
                        if (text.isNotBlank()) {
                            Text(
                                text = text,
                                color = if (st.running) Color(0xFF7FB84F) else Color.White.copy(alpha = 0.6f),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
                if (item.overview.isNotBlank()) {
                    Text(
                        text = item.overview,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        maxLines = 5,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Все действия — ОДНИМ рядом с прокруткой: на узком экране
                // ничего не «уезжает» за границу, а «В избранное» и
                // «Убрать из просмотра» больше не спрятаны в меню «Ещё».
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item {
                        Button(
                            onClick = {
                                com.torrenttv.app.data.AppLog.log(
                                    "ui",
                                    "нажал: «▶ Смотреть» «${item.title}» (${if (magnet) "торрент-карточка" else "умный подбор"})",
                                )
                                if (magnet) {
                                    // Фиксируем начало просмотра и открываем плеер
                                    store.saveProgress(item, positionSec = 0, durationSec = 0)
                                    onWatch()
                                } else {
                                    onWatchSmart()
                                }
                            },
                            modifier = Modifier.focusRequester(watchFocus),
                        ) { Text("▶ Смотреть") }
                    }
                    // Сериал — «Эпизоды» всегда рядом со «Смотреть» (Zona-стиль)
                    if (item.type == "tv") {
                        item {
                            Button(onClick = {
                                com.torrenttv.app.data.AppLog.log("ui", "нажал: «Эпизоды» на карточке «${item.title}»")
                                onEpisodes(0)
                            }) { Text("🎬 Эпизоды") }
                        }
                    }
                    item {
                        Button(onClick = {
                            fav = store.toggleFavorite(item)
                            com.torrenttv.app.data.AppLog.log(
                                "ui",
                                "нажал: «${if (fav) "В избранном" else "В избранное"}» «${item.title}»",
                            )
                        }) { Text(if (fav) "★ В избранном" else "☆ В избранное") }
                    }
                    item {
                        Button(onClick = { menu = !menu }) { Text("⋯ Ещё") }
                    }
                }

                // Бесплатный легальный стрим: полные фильмы/серии на Rutube.
                // Если есть — показываем до 3 кнопок с названиями видео
                if (rutubeFree.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "📺 Бесплатно на Rutube (легально, с рекламой):",
                        color = Color(0xFF9FB0C0),
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (v in rutubeFree.take(3)) {
                            Button(onClick = {
                                com.torrenttv.app.data.AppLog.log("nav", "нажал: «Rutube бесплатно» «${v.title}»")
                                onWatchRutube(v.id, v.title, v.thumb)
                            }) {
                                Text(
                                    v.title.take(34) + if (v.title.length > 34) "…" else "",
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                if (menu) {
                    val menuFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(menu) { runCatching { menuFocus.requestFocus() } }
                    Spacer(Modifier.height(10.dp))
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // «Эпизоды» больше не здесь — для сериалов она всегда видна рядом с «Смотреть»
                        Button(
                            onClick = { menu = false; onManualSearch() },
                            modifier = Modifier.focusRequester(menuFocus),
                        ) { Text("🔍 Выбрать раздачу") }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { menu = false; onEnterMagnet() }) {
                            Text(if (magnet) "🔗 Заменить magnet" else "🔗 Указать magnet")
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { later = store.toggleWatchLater(item); menu = false }) {
                            Text(if (later) "✓ Смотреть позже" else "＋ Смотреть позже")
                        }
                        Button(onClick = {
                            statusTag = store.cycleItemStatus(item)
                            menu = false
                        }) {
                            Text(statusLabel(statusTag))
                        }
                    }
                }

                if (status.isNotBlank()) {
                    Text(status, color = Color(0xFFFFD54F), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                }

                // ---------- Сезоны и серии: сразу в карточке сериала ----------
                var seasons by remember(item) { mutableStateOf<List<com.torrenttv.app.data.Season>>(emptyList()) }
                var selSeason by remember(item) { mutableStateOf<Int?>(null) }
                if (item.type == "tv") {
                    LaunchedEffect(item) {
                        seasons = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { com.torrenttv.app.data.MediaApi.seasons(item) }.getOrDefault(emptyList())
                        }
                        selSeason = seasons.firstOrNull()?.number
                    }
                }
                if (seasons.isNotEmpty()) {
                    Text(
                        "🎬 Сезоны и серии",
                        color = Color(0xFFF5B942),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        itemsIndexed(seasons) { _, s ->
                            val sel = s.number == (selSeason ?: seasons.first().number)
                            Surface(
                                onClick = { selSeason = s.number },
                                shape = clickableSurfaceShape(RoundedCornerShape(18.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (sel) Color(0xFFF5B942) else Color(0xFF1F2430),
                                    contentColor = if (sel) Color(0xFF0E1116) else Color.White,
                                    focusedContainerColor = Color(0xFFFFD07A),
                                    focusedContentColor = Color(0xFF0E1116),
                                ),
                            ) {
                                Text(
                                    "Сезон ${s.number}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    val curSeason = seasons.firstOrNull { it.number == (selSeason ?: seasons.first().number) }
                    if (curSeason != null) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            itemsIndexed(curSeason.episodes) { _, ep ->
                                val hasSource = store.getMagnet(item, ep.sourceSuffix) != null
                                Surface(
                                    onClick = {
                                        if (hasSource) onPlayEpisode(ep) else onEpisodes(ep.season)
                                    },
                                    shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = if (hasSource) Color(0xFF2E4A26) else Color(0xFF1F2430),
                                        contentColor = Color.White,
                                        focusedContainerColor = Color(0xFFF5B942),
                                        focusedContentColor = Color.Black,
                                    ),
                                ) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text(
                                            (if (hasSource) "▶ " else "") + ep.code,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (ep.title.isNotBlank()) {
                                            Text(
                                                ep.title,
                                                color = Color.White.copy(alpha = 0.6f),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.width(150.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Похожее
        if (similar.isNotEmpty()) {
            Box(Modifier.weight(0.45f)) {
                MediaRow("Похожее", similar, onOpenDetails)
            }
        }

        Button(onClick = onBack) { Text("← Назад") }
    }
}
