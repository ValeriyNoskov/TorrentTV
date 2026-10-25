package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.torrenttv.app.data.LocalStore
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.MediaApi
import com.torrenttv.app.data.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Карточка фильма/сериала: описание, избранное, magnet, просмотр и «Похожее». */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // Фильм есть в «Продолжить просмотр» → в «Ещё» доступна кнопка убрать.
    // Раньше это было только длинным нажатием на карточке в ряду, и в самой
    // карточке кнопка пропала (владелец искал — «куда делась»).
    var inHistory by remember(item.storeKey) { mutableStateOf(store.hasProgress(item)) }
    var magnet by remember { mutableStateOf(store.getMagnet(item) != null) }
    var status by remember { mutableStateOf("") }
    var similar by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    // Карточка без меты (заглушка из поиска раздач): дотягиваем постер/описание
    // с КП прямо на экране деталей — иначе «нет постера» (жалоба по «Спецам»)
    var itemState by remember(item.storeKey) { mutableStateOf(item) }
    LaunchedEffect(item.storeKey) {
        if (itemState.posterUrl.isBlank()) {
            val en = withContext(Dispatchers.IO) { MediaApi.enrich(itemState, store) }
            if (en.posterUrl.isNotBlank()) itemState = en
        }
        // КП не дал постер — ищем на Trakt (media.trakt.tv доступен из РФ)
        if (itemState.posterUrl.isBlank()) {
            val traktPoster = withContext(Dispatchers.IO) {
                runCatching {
                    com.torrenttv.app.data.TraktClient.posterUrl(
                        itemState.title, itemState.year, itemState.type == "tv", itemState.country,
                    )
                }.getOrNull()
            }
            if (!traktPoster.isNullOrBlank()) {
                itemState = itemState.copy(posterUrl = traktPoster)
                com.torrenttv.app.data.AppLog.log("details", "«${itemState.title}»: постер взят с Trakt")
            }
        }
    }
    val watchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { watchFocus.requestFocus() }
    }

    LaunchedEffect(item) {
        similar = withContext(Dispatchers.IO) { MediaApi.similar(item) }
        // Название карточки — в словарь подсказок клавиатуры
        runCatching { store.kbAddWord(itemState.title) }
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

    // 🖼 Fanart-фон: постер растянут на весь экран, размыт и затемнён
    // (стиль Netflix/Kinopoisk) — карточка перестаёт «тонуть» в чёрном фоне
    if (itemState.posterUrl.isNotBlank()) {
        AsyncImage(
            model = itemState.posterUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(28.dp),
            contentScale = ContentScale.Crop,
            alpha = 0.30f,
        )
        // Затемняющая плёнка поверх — текст читается
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x990E1116)),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            // Поверх fanart фон прозрачный — иначе непрозрачный Column закроет его
            .background(if (itemState.posterUrl.isNotBlank()) Color.Transparent else Color(0xFF0E1116))
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        // Вертикальная карточка: постер → кнопки → описание → «Похожее».
        // Всё листается пультом ВНИЗ (просьба владельца: «двигается вниз,
        // а не вправо»), поэтому вместо Row с боковым скроллом — одна колонка.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---------- Постер сверху ----------
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (itemState.posterUrl.isBlank()) {
                    Box(
                        Modifier
                            .width(210.dp)
                            .height(310.dp)
                            .background(Color(0xFF1F2430), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("нет постера", color = Color.White.copy(alpha = 0.4f)) }
                } else {
                    AsyncImage(
                        model = itemState.posterUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .width(210.dp)
                            .height(310.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            // ---------- Название, тип, год ----------
            Text(
                item.title,
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
            Text(
                text = buildString {
                    append(item.typeLabel)
                    if (item.year.isNotBlank()) append(" · ${item.year}")
                },
                color = Color(0xFFB8C0CC),
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )

            // Рейтинги: КП и IMDb одним запросом, кеш 24 ч
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                val kpShown = kpRating.takeIf { it > 0 } ?: item.rating
                if (kpShown > 0) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF31435A))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "★︎ ${"%.1f".format(kpShown)} КП",
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

            // Статус сериала: «идёт/завершён» и ближайшая серия (TMDB)
            if (item.type == "tv") {
                var tvStatus by remember(item.id) { mutableStateOf<TmdbClient.TvStatus?>(null) }
                LaunchedEffect(item.id) {
                    val st = withContext(Dispatchers.IO) {
                        // TMDB недоступен в РФ — фолбэк на Trakt (тот же формат статуса)
                        runCatching { TmdbClient.tvStatus(item.title, item.year) }.getOrNull()
                            ?: runCatching {
                                com.torrenttv.app.data.TraktClient.tvStatus(item.title, item.year, item.country)
                            }.getOrNull()
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

            // ---------- Кнопки: сразу под постером, компактные ----------
            // «Позже» и статусы убраны: они дублировали избранное
            // (владелец: «возможно нам избранного хватит»).
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Button(
                    onClick = {
                        com.torrenttv.app.data.AppLog.log(
                            "ui",
                            "нажал: «▶︎ Смотреть» «${item.title}» (${if (magnet) "торрент-карточка" else "умный подбор"})",
                        )
                        if (magnet) {
                            store.saveProgress(item, positionSec = 0, durationSec = 0)
                            onWatch()
                        } else {
                            onWatchSmart()
                        }
                    },
                    modifier = Modifier.focusRequester(watchFocus),
                ) { Text("▶︎ Смотреть") }
                Button(onClick = {
                    fav = store.toggleFavorite(item)
                    com.torrenttv.app.data.AppLog.log(
                        "ui",
                        "нажал: «${if (fav) "В избранном" else "В избранное"}» «${item.title}»",
                    )
                }) { Text(if (fav) "★︎ В избранном" else "☆ В избранное") }
                MiniButton("🔍 Раздача") { onManualSearch() }
                // ▶︎ Трейлер — ищем на Rutube и играем встроенным плеером
                MiniButton("🎬 Трейлер") {
                    com.torrenttv.app.data.AppLog.log(
                        "ui",
                        "нажал: «🎬 Трейлер» «${item.title}»",
                    )
                    onTrailer()
                }
                if (inHistory) {
                    MiniButton("✕ Из просмотра") {
                        store.removeProgress(item)
                        inHistory = false
                        com.torrenttv.app.data.AppLog.log(
                            "ui",
                            "нажал: «Убрать из просмотра» «${item.title}»",
                        )
                    }
                }
            }

            // ---------- Описание ----------
            if (item.overview.isNotBlank()) {
                Text(
                    // Нормализация: «Год\n2026\nСтрана\nРоссия» →
                    // «Год: 2026 · Страна: Россия» в одну строку
                    text = item.normalizedOverview(),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 1100.dp)
                        .padding(top = 18.dp),
                )
            }

            // Бесплатный легальный стрим Rutube
            if (rutubeFree.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "📺 Бесплатно на Rutube (легально, с рекламой):",
                    color = Color(0xFF9FB0C0),
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(6.dp))
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

            if (status.isNotBlank()) {
                Text(status, color = Color(0xFFE8ECF4), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            }

            // ---------- Сезоны и серии (владелец: вместо кнопки «Эпизоды»,
            // с автообновлением при открытии и кнопкой «Обновить») ----------
            var seasons by remember(item) { mutableStateOf<List<com.torrenttv.app.data.Season>>(emptyList()) }
            var selSeason by remember(item) { mutableStateOf<Int?>(null) }
            var epTick by remember(item) { mutableStateOf(0) }
            if (item.type == "tv") {
                LaunchedEffect(item, epTick) {
                    // 1) Кеш: секция видна мгновенно
                    val cached = store.loadEpisodeCache(item)
                    if (cached.isNotEmpty()) {
                        seasons = cached
                        if (selSeason == null) selSeason = store.lastSeason(item).takeIf { n -> n != 0 && cached.any { it.number == n } }
                            ?: cached.first().number
                        // Кеш мог быть сохранён до появления дат (старая версия):
                        // дат нет — тихо дотягиваем из Trakt и обновляем кеш
                        val cachedDates = cached.sumOf { s -> s.episodes.count { it.airDate.isNotBlank() } }
                        if (cachedDates == 0) {
                            val trakt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { com.torrenttv.app.data.TraktClient.seasons(item.title, item.year, item.country) }.getOrDefault(emptyList())
                            }
                            if (trakt.isNotEmpty()) {
                                val merged = enrichWithTrakt(item, cached, trakt)
                                store.saveEpisodeCache(item, merged)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { seasons = merged }
                            }
                        }
                    }
                    // 2) Автообновление при открытии: дос-поиск раздач, если
                    //    с прошлого — больше 30 минут (иначе закрытие/открытие
                    //    карточки молотило бы поиск каждый раз)
                    val stale = System.currentTimeMillis() - store.episodeCacheTime(item) > 30 * 60_000L
                    if (cached.isEmpty() || stale || epTick > 0) {
                        if (epTick > 0) com.torrenttv.app.data.AppLog.log("episodes", "«${item.title}»: ручное обновление серий (детали)")
                        episodeRefreshScope.launch {
                            val (found, reason) = seasonsFromTorrents(item, store, context)
                            if (found.isNotEmpty()) {
                                // Даты выхода из Trakt — как на экране «Эпизоды»:
                                // КП дат не даёт, TMDB недоступен в РФ
                                val trakt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { com.torrenttv.app.data.TraktClient.seasons(item.title, item.year, item.country) }.getOrDefault(emptyList())
                                }
                                val merged = enrichWithTrakt(item, mergeSeasons(cached, found), trakt)
                                store.saveEpisodeCache(item, merged)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    seasons = merged
                                    if (selSeason == null) selSeason = merged.firstOrNull()?.number
                                    val dates = merged.sumOf { s -> s.episodes.count { ep -> ep.airDate.isNotBlank() } }
                                    com.torrenttv.app.data.AppLog.log(
                                        "episodes",
                                        "«${item.title}»: секция серий обновлена: сезонов ${merged.size} ${merged.map { "S${it.number}(${it.episodes.size})" }}, серий с датами $dates",
                                    )
                                }
                            } else if (cached.isEmpty()) {
                                // Кеша нет и раздачи пусты — метаданные из КП
                                val meta = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { com.torrenttv.app.data.MediaApi.seasons(item) }.getOrDefault(emptyList())
                                }
                                if (meta.isNotEmpty()) store.saveEpisodeCache(item, meta)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    seasons = meta
                                    if (selSeason == null) selSeason = meta.firstOrNull()?.number
                                }
                            } else {
                                com.torrenttv.app.data.AppLog.log("episodes", "«${item.title}»: дос-поиск секции — новых нет${reason?.let { ": $it" } ?: ""}")
                            }
                        }
                    }
                }
            }
            if (seasons.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                ) {
                    Text(
                        "🎬 Сезоны и серии",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.tv.material3.Surface(
                        onClick = {
                            if (epTick >= 0) { epTick++; com.torrenttv.app.data.AppLog.log("episodes", "«${item.title}»: нажал «Обновить» в секции серий") }
                        },
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xB3FFFFFF),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("🔄 Обновить", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                }
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
                                containerColor = if (sel) Color(0xFF31435A) else Color(0xFF1F2430),
                                contentColor = Color.White,
                                focusedContainerColor = Color(0xB3FFFFFF),
                                focusedContentColor = Color.Black,
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
                    // Вертикальный список серий (макет design/details_episodes_list.html,
                    // подтверждён владельцем): код+название, дата выхода, одна кнопка —
                    // «▶︎ Смотреть» (раздача есть) или «🧲 Раздача» (ведёт на поиск).
                    // Обычный Column: секция живёт внутри общего verticalScroll карточки
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        for (ep in curSeason.episodes) {
                            val hasSource = store.getMagnet(item, ep.sourceSuffix) != null
                            val today = java.time.LocalDate.now()
                            val future = ep.airDate.isNotBlank() && runCatching {
                                java.time.LocalDate.parse(ep.airDate).isAfter(today)
                            }.getOrDefault(false)
                            val dateText = ep.airDate.takeIf { it.isNotBlank() }?.let {
                                val p = it.split('-') // yyyy-MM-dd → dd.MM.yyyy
                                if (future) "выйдет ${p[2]}.${p[1]}" else "${p[2]}.${p[1]}.${p[0]}"
                            }
                            Surface(
                                onClick = { if (hasSource) onPlayEpisode(ep) else onEpisodes(ep.season) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .mouseClick { if (hasSource) onPlayEpisode(ep) else onEpisodes(ep.season) },
                                shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color(0xFF1F2430),
                                    contentColor = Color.White,
                                    focusedContainerColor = Color(0xFF31435A),
                                    focusedContentColor = Color.White,
                                    pressedContainerColor = Color(0xFF273548),
                                    pressedContentColor = Color.White,
                                ),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        ep.code,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.width(76.dp),
                                    )
                                    if (ep.title.isNotBlank()) {
                                        Text(
                                            ep.title,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                    if (dateText != null) {
                                        Text(
                                            dateText,
                                            color = if (future) Color(0xFF7FB84F) else Color(0xFF6B7488),
                                            fontSize = 13.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                        )
                                    }
                                    Text(
                                        if (hasSource) "▶︎ Смотреть" else "🧲 Раздача",
                                        color = if (hasSource) Color(0xFF9CCC65) else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------- Похожее: в самом низу, сетка растёт вниз ----------
            if (similar.isNotEmpty()) {
                Text(
                    "🎬 Похожее",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                ) {
                    for (s in similar.take(18)) {
                        PosterCard(s) { onOpenDetails(s) }
                    }
                }
            }
        }

        Button(onClick = onBack) { Text("← Назад") }
    }
}

/**
 * Компактная кнопка карточки: 34dp высоты, 13sp — все действия помещаются
 * в один ряд и не выглядят «огромными» на ТВ (просьба владельца).
 */
@Composable
private fun MiniButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
    ) {
        Text(text, fontSize = 13.sp, maxLines = 1)
    }
}
