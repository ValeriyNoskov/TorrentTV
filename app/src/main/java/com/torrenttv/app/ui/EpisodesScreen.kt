package com.torrenttv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.data.AppLog
import com.torrenttv.app.data.Episode
import com.torrenttv.app.data.KinopoiskClient
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.MediaApi
import com.torrenttv.app.data.Season
import com.torrenttv.app.data.TorrServeService
import com.torrenttv.app.data.TorrentSearch
import com.torrenttv.app.data.TorrentResult
import com.torrenttv.app.torrent.TorrentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Сезоны и эпизоды сериала: выбор сезона (чипы), список серий с названием.
 * У каждой серии свой источник (magnet или .torrent) и свой прогресс —
 * смотреть сразу или искать раздачу под эпизод.
 */
@Composable
fun EpisodesScreen(
    item: MediaItem,
    onPlay: (Episode) -> Unit,
    onFind: (Episode) -> Unit,
    onBack: () -> Unit,
    initialSeason: Int? = null,
) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Int?>(null) } // null = первый сезон
    var tick by remember { mutableStateOf(0) } // перерисовка статусов после поиска источника
    // Причина пустого списка («TorrServe не отвечает») — вместо немого «не найдены»
    var failReason by remember { mutableStateOf<String?>(null) }

    // Список открыт — «N новых серий» пользователь увидел, бейдж погасить
    LaunchedEffect(item.id) { store.markEpisodesSeen(item) }

    // 🔄 Обновить — принудительный пересбор серий из раздач: дос-досылка
    // срабатывает раз в 12 ч, а свежий сезон («Титаны» S05) хочется увидеть сразу
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(item, refreshTick) {
        // Кеш: найденные однажды серии запоминаются — повторные открытия
        // показываются мгновенно, без поиска (жалоба: «запоминай ссылки»)
        val cached = store.loadEpisodeCache(item)
        if (cached.isNotEmpty()) {
            seasons = cached
            loading = false
            if (selected == null) {
                // Порядок: явно запрошенный сезон → последний выбранный вручную → первый
                val last = store.lastSeason(item).takeIf { n -> n != 0 && cached.any { it.number == n } }
                selected = initialSeason?.takeIf { s -> cached.any { it.number == s } }
                    ?: last
                    ?: cached.first().number
            }
            AppLog.log("episodes", "«${item.title}»: из кеша ${cached.sumOf { it.episodes.size }} серий")
            // Дос-досылка: серии выходят периодически — тихо ищем раз в 12 ч,
            // добавляем ТОЛЬКО недостающие, названия старых не трогаем
            if (refreshTick > 0 || System.currentTimeMillis() - store.episodeCacheTime(item) > 12 * 3_600_000L) {
                val (found, reason) = withContext(Dispatchers.IO) { seasonsFromTorrents(item, store, context) }
                if (found.isNotEmpty()) {
                    val merged = mergeSeasons(cached, found)
                    val added = merged.sumOf { it.episodes.size } - cached.sumOf { it.episodes.size }
                    store.saveEpisodeCache(item, merged)
                    if (added > 0) {
                        seasons = merged
                        tick++
                        AppLog.log("episodes", "«${item.title}»: дос-поиск добавил $added новых серий")
                    } else {
                        AppLog.log("episodes", "«${item.title}»: дос-поиск — новых серий нет")
                    }
                } else {
                    AppLog.log("episodes", "«${item.title}»: дос-поиск не удался${reason?.let { ": $it" } ?: ""}")
                    store.saveEpisodeCache(item, cached) // обновить время кеша — не молотить каждое открытие
                }
            }
            return@LaunchedEffect
        }
        // Первый запуск: КП → раздачи → кеш
        seasons = withContext(Dispatchers.IO) { MediaApi.seasons(item) }
        // Главный источник при торрентовом показе — сама раздача: файлы в ней и
        // есть серии. КП/TMDB тут только украшение (названия серий), когда доступны.
        if (seasons.isEmpty()) {
            val (fromTorrents, reason) = withContext(Dispatchers.IO) { seasonsFromTorrents(item, store, context) }
            seasons = fromTorrents
            failReason = reason
        }
        // Найденное запоминаем: и собранное из раздач, и КП-мету
        if (seasons.isNotEmpty()) store.saveEpisodeCache(item, seasons)
        loading = false
        AppLog.log("episodes", "«${item.title}»: загружено сезонов ${seasons.size} (id=${item.id})")
        // КП-мета не знает про раздачи: у «Титанов» 5-й сезон выходит отдельной
        // раздачей, а КП отдаёт только 1–4. Раньше раздачи смотрели ТОЛЬКО когда
        // КП молчал — и при наличии сезона 1 «пятёрка» не появлялась вовсе.
        // Теперь всегда подмешиваем сезоны из раздач (фоном, после отрисовки)
        if (seasons.isNotEmpty()) {
            val (fromTorrents, reason) = withContext(Dispatchers.IO) { seasonsFromTorrents(item, store, context) }
            if (fromTorrents.isNotEmpty()) {
                val merged = mergeSeasons(seasons, fromTorrents)
                val added = merged.sumOf { it.episodes.size } - seasons.sumOf { it.episodes.size }
                if (added > 0) {
                    seasons = merged
                    store.saveEpisodeCache(item, merged)
                    tick++
                    AppLog.log(
                        "episodes",
                        "«${item.title}»: из раздач добавлено $added серий (сезоны ${fromTorrents.map { it.number }})",
                    )
                } else {
                    AppLog.log("episodes", "«${item.title}»: раздачи новых серий не дали")
                }
            } else {
                AppLog.log("episodes", "«${item.title}»: раздачи не дали серий${reason?.let { ": $it" } ?: ""}")
            }
        }
        if (seasons.isNotEmpty() && selected == null) {
            val last = store.lastSeason(item).takeIf { n -> n != 0 && seasons.any { it.number == n } }
            selected = initialSeason?.takeIf { s -> seasons.any { it.number == s } }
                ?: last
                ?: seasons.first().number
        }
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(32.dp),
    ) {
        Text("🎬 Эпизоды", color = Color(0xFFF5B942), fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // 🔄 Принудительный пересбор серий из раздач: дос-досылка раз в 12 ч,
            // а свежий сезон («Титаны» S05) хочется увидеть сразу
            androidx.tv.material3.Surface(
                onClick = {
                    if (!loading) {
                        loading = true
                        refreshTick++
                        AppLog.log("episodes", "«${item.title}»: ручное обновление серий")
                    }
                },
                enabled = !loading,
                modifier = Modifier.padding(start = 12.dp),
                shape = clickableSurfaceShape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFF1F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFF5B942),
                    focusedContentColor = Color.Black,
                    disabledContainerColor = Color(0xFF141922),
                    disabledContentColor = Color.White.copy(alpha = 0.3f),
                ),
            ) { Text("🔄 Обновить", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
        }

        when {
            loading -> Text(
                "Загружаю сезоны…",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp),
            )

            seasons.isEmpty() -> Text(
                // Причина вместо немого «не найдены»: TorrServe / лимит КП / нет раздач
                failReason
                    ?: (if (KinopoiskClient.lastFailed) "Эпизоды временно недоступны" else "Сезоны не найдены"),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // ---------- Чипы сезонов ----------
        if (!loading && seasons.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp)) {
                LazyRow {
                    itemsIndexed(seasons) { _, s ->
                        SeasonChip("Сезон ${s.number}", s.number == (selected ?: seasons.first().number)) {
                            selected = s.number
                            store.saveLastSeason(item, s.number) // «Титаны»: открыл S05 — запомнить
                        }
                    }
                }
            }

            // ---------- Список эпизодов ----------
            val season = seasons.first { it.number == (selected ?: seasons.first().number) }
            LazyColumn(Modifier.padding(top = 10.dp).weight(1f)) {
                itemsIndexed(season.episodes) { _, ep ->
                    val hasSource = remember(tick) { store.getMagnet(item, ep.sourceSuffix) != null }
                    Surface(
                        onClick = { if (hasSource) onPlay(ep) else onFind(ep) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .mouseClick { if (hasSource) onPlay(ep) else onFind(ep) },
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFF31435A),
                            focusedContentColor = Color.White,
                            pressedContainerColor = Color(0xFF273548),
                            pressedContentColor = Color.White,
                            disabledContainerColor = Color(0xFF1F2430),
                            disabledContentColor = Color.White,
                        ),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                             Text(
                                 text = ep.code,
                                 color = Color(0xFFF5B942),
                                 fontSize = 15.sp,
                                 fontWeight = FontWeight.Bold,
                                 modifier = Modifier.width(78.dp),
                             )
                             // ✓ — серия просмотрена (кнопкой в плеере или досмотрена ≥95%);
                             // проверяем оба ключа: эта карточка + нормализованный
                             // (один на тайтл — галочка переживает смену раздачи)
                             @Suppress("UNUSED_EXPRESSION") tick
                             if (store.isWatchedAny(listOf(
                                     item.storeKey + ":online" + ep.sourceSuffix,
                                     store.normWatchKey(item.title, ep.sourceSuffix),
                                 ))
                             ) {
                                 Text(
                                     text = "✓",
                                     color = Color(0xFF9CCC65),
                                     fontSize = 17.sp,
                                     fontWeight = FontWeight.Bold,
                                     modifier = Modifier.width(24.dp),
                                 )
                             }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = ep.title.ifBlank { "Эпизод ${ep.episode}" },
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = if (hasSource) "▶ Смотреть" else "🔍 Найти",
                                color = if (hasSource) Color(0xFF9CCC65) else Color(0xFF64B5F6),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.padding(top = 10.dp))
        Button(onClick = onBack) { Text("← Назад") }
    }
}

/**
 * Объединение кеша и дос-поиска: новые серии добавляются, старые (с их
 * названиями из КП/файлов) не трогаются.
 */
private fun mergeSeasons(old: List<Season>, fresh: List<Season>): List<Season> {
    val map = sortedMapOf<Int, MutableMap<Int, Episode>>()
    for (s in old) for (e in s.episodes) map.getOrPut(s.number) { mutableMapOf() }[e.episode] = e
    for (s in fresh) for (e in s.episodes) map.getOrPut(s.number) { mutableMapOf() }.putIfAbsent(e.episode, e)
    return map.map { (num, eps) -> Season(num, eps.values.sortedBy { it.episode }) }
}

/**
 * Сезоны из раздачи (главный источник при торрентовом показе): TorrServe
 * подхватывает лучшую раздачу, серии — видео-файлы (S01E05…). Магнит раздачи
 * сохраняется за каждой серией — «Смотреть» работает сразу, без поиска.
 */
private suspend fun seasonsFromTorrents(
    item: MediaItem,
    store: LocalStore,
    context: android.content.Context,
): Pair<List<Season>, String?> {
    val host = store.torrServeHost()
    if (host.isBlank()) {
        AppLog.log("episodes", "torrserve: адрес не задан — эпизоды из раздачи недоступны")
        return emptyList<Season>() to "TorrServe не настроен (Настройки → TorrServe)"
    }
    if (!TorrServeService.ensureRunning(context, host)) {
        AppLog.log("episodes", "torrserve: не отвечает ($host) — эпизоды из раздачи недоступны")
        return emptyList<Season>() to "TorrServe не отвечает — откройте приложение TorrServe и повторите"
    }
    val found: List<TorrentResult> = try {
        // Полный набор параметров, как в SmartPlayScreen: jackett/приватные
        // трекеры/исключения — иначе поиск сужен и «Спецы» не находятся
        TorrentSearch.searchDetailed(
            query = item.title,
            isTvShow = item.type != "movie",
            imdbId = item.imdbId,
            jackett = store.jackettConfig(),
            privateTrackers = store.privateTrackers(),
            excludedKeywords = store.excludedKeywords().toSet(),
        ).first
    } catch (e: Exception) {
        // Раньше падение глоталось молча — в логе «найдено 0» без причины
        AppLog.log("episodes", "поиск раздач FAIL: ${e.message}")
        emptyList()
    }
    AppLog.log("episodes", "«${item.title}»: раздач для эпизодов найдено ${found.size}")
    // Собираем сезоны из НЕСКОЛЬКИХ раздач: сезоны одного сериала часто лежат
    // отдельными раздачами («Титаны» S01–S04 в одной, S05 — в другой).
    // Выбор раздач: группируем по номеру сезона из НАЗВАНИЯ и берём ЛУЧШУЮ
    // по сидам на каждый сезон — иначе топ-3 по сидам были только пачками
    // S01–S04 и свежая раздача S05 никогда не попадала в выборку.
    // Раздачи без метки сезона (полные пачки) — тоже до 2 лучших.
    val torrentSeasonOf: (TorrentResult) -> Int? = { r ->
        Regex("S(\\d{1,2})", RegexOption.IGNORE_CASE).find(r.name)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(\\d{1,2})\\s*[сС]езон").find(r.name)?.groupValues?.get(1)?.toIntOrNull()
    }
    val perSeason = found.filter { torrentSeasonOf(it) != null }
        .groupBy { torrentSeasonOf(it)!! }
        .mapValues { (_, rs) -> rs.maxByOrNull { it.seeds }!! }
        .values
    val multipack = found.filter { torrentSeasonOf(it) == null }.sortedByDescending { it.seeds }.take(2)
    val picked = (perSeason + multipack).sortedByDescending { it.seeds }
    AppLog.log("episodes", "«${item.title}»: к загрузке выбраны раздачи: " +
        picked.joinToString("; ") { "${it.name.take(50)} (S${torrentSeasonOf(it) ?: "?"}, ${it.seeds} сидов)" })
    val allSeasons = sortedMapOf<Int, MutableMap<Int, Episode>>()
    for (r in picked) {
        val t = runCatching {
            TorrServeService.addMagnet(host, TorrentSearch.toMagnet(r), item.title, 45_000)
        }.getOrNull() ?: continue
        val video = t.files.filter {
            it.path.substringAfterLast('.', "").lowercase() in TorrentEngine.VIDEO_EXT
        }
        // Сезон из названия раздачи: «Титаны.S05» / «5 сезон» — для пачек,
        // где файлы названы просто «05. …» без метки сезона
        val torrentSeason = Regex("S(\\d{1,2})", RegexOption.IGNORE_CASE).find(r.name)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(\\d{1,2})\\s*[сС]езон").find(r.name)?.groupValues?.get(1)?.toIntOrNull()
        val bySeason = sortedMapOf<Int, MutableList<Episode>>()
        for (f in video) {
            val parsed = Episode.fileNumbers(f.path) ?: run {
                // Файл без S/E-метки: серия — ведущее число имени («05. …» → 5),
                // сезон — из названия раздачи. Без обоих — файл пропускаем
                val num = Regex("^\\D*?(\\d{1,3})").find(f.path.substringAfterLast('/'))
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (torrentSeason != null && num != null && num in 1..200) torrentSeason to num else null
            } ?: continue
            val (s, e) = parsed
            bySeason.getOrPut(s) { mutableListOf() }.add(
                Episode(
                    season = s,
                    episode = e,
                    title = f.path.substringAfterLast('/').substringBeforeLast('.'),
                ),
            )
        }
        if (bySeason.isEmpty()) continue
        val magnet = TorrentSearch.toMagnet(r)
        for (eps in bySeason.values) for (ep in eps) store.saveMagnet(item, magnet, ep.sourceSuffix)
        for ((num, eps) in bySeason) {
            val target = allSeasons.getOrPut(num) { mutableMapOf() }
            for (ep in eps) target.putIfAbsent(ep.episode, ep)
        }
    }
    if (allSeasons.isEmpty()) return emptyList<Season>() to "Раздач с сериями не нашлось"
    AppLog.log("episodes", "«${item.title}»: из раздач собраны сезоны ${allSeasons.keys}")
    return allSeasons.map { (num, eps) -> Season(num, eps.values.sortedBy { it.episode }) }
        .sortedBy { it.number } to null
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .padding(end = 8.dp)
            .mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
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
