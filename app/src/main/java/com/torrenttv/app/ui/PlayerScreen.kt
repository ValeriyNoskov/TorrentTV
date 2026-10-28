package com.torrenttv.app.ui

import android.view.KeyEvent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.torrenttv.app.MainActivity
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaApi
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.torrent.TorrentDataSource
import com.torrenttv.app.torrent.TorrentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Экран просмотра: стриминг торрента по кускам (последовательная загрузка),
 * буферизация первых мегабайт, затем ExoPlayer поверх недокачанного файла.
 * Позиция просмотра сохраняется каждые 5 секунд в «Продолжить просмотр».
 */
@Composable
fun PlayerScreen(
    item: MediaItem,
    episode: com.torrenttv.app.data.Episode? = null,
    onBack: () -> Unit,
    /** Серия закончилась — перейти к следующей (автоплей сериала). */
    onPlayNext: (com.torrenttv.app.data.Episode) -> Unit = {},
    /** «Другое качество» — вернуться к выбору раздач/ссылок. */
    onPickTorrent: () -> Unit = {},
    /** Кнопка 🔍 — уйти во вкладку поиска (плеер приостанавливается в onDispose). */
    onSearch: () -> Unit = {},
    /** Запуск конкретного файла раздачи (из экрана файлов). */
    startIndex: Int = -1,
) {
    // Экран не гаснет, пока играет фильм; в аудио-режиме — можно гаснуть
    var audioMode by remember { mutableStateOf(false) }
    KeepScreenOn(enabled = !audioMode)

    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    val scope = rememberCoroutineScope()
    // Карточка без меты (заглушка из поиска раздач): дотягиваем постер/год/IMDb
    // с КП — иначе в «Продолжить» карточка без постера (жалоба по «Спецам»)
    var itemState by remember(item.storeKey) { mutableStateOf(item) }
    LaunchedEffect(item.storeKey) {
        if (itemState.posterUrl.isBlank() || itemState.imdbId.isBlank()) {
            val en = withContext(Dispatchers.IO) { MediaApi.enrich(itemState, store) }
            if (en.posterUrl.isNotBlank() || en.imdbId.isNotBlank()) itemState = en
        }
    }

    // Разметка пропуска из открытых баз (SkipDB/IntroDB): точные таймкоды
    // заставки/пересказа/титров по IMDb ID. Кешируется, запрос — раз на серию.
    var skipSegs by remember(episode) { mutableStateOf<List<com.torrenttv.app.data.SkipSeg>>(emptyList()) }
    LaunchedEffect(episode, itemState.imdbId) {
        val imdb = itemState.imdbId
        if (imdb.isBlank() || !store.skipDbEnabled()) return@LaunchedEffect
        val segs = withContext(Dispatchers.IO) {
            runCatching {
                com.torrenttv.app.data.SkipSegments.fetch(
                    store, imdb,
                    episode?.season ?: 0,
                    episode?.episode,
                )
            }.getOrDefault(emptyList())
        }
        skipSegs = segs
        com.torrenttv.app.data.AppLog.log(
            "player",
            if (segs.isEmpty()) "разметка пропуска: нет данных ($imdb)"
            else "разметка пропуска: " + segs.joinToString { "${it.kind} ${it.startMs / 1000}-${it.endMs / 1000}с" },
        )
    }
    // Источник эпизода хранится с суффиксом (":s01e05") — у каждой серии свой
    val sourceSuffix = episode?.sourceSuffix ?: ""
    val sourceKey = item.storeKey + sourceSuffix
    val magnet = remember(episode) { store.getMagnet(item, sourceSuffix) }

    var status by remember { mutableStateOf(if (magnet == null) "Нет magnet-ссылки" else "Подготовка…") }
    var bufferedPct by remember { mutableStateOf(0) }
    var netInfo by remember { mutableStateOf("") }
    var stream by remember { mutableStateOf<TorrentEngine.StreamSession?>(null) }
    // Серии внутри торрента: (файл, имя) + выбор другой серии
    var torrentFiles by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var restartFileIndex by remember { mutableStateOf<Int?>(null) }
    // Оверлеи объявлены заранее: состояние нужно уже в LaunchedEffect старта
    var showFiles by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf(true) }

    // Стриминг встроенным движком jlibtorrent: качает прямо на ТВ по кускам
    // Колбэки движка приходят из IO/daemon-потоков — Compose-состояние пишем
    // только из главного потока (запись из фона ломает рекомпозицию)
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    LaunchedEffect(magnet) {
        if (magnet == null) return@LaunchedEffect

        com.torrenttv.app.data.AppLog.log("player", "startStream: ${magnet.take(90)}")

        // Перезапуск выбранной серии: гасим текущий стрим, стартуем с выбранного файла
        if (restartFileIndex != null) {
            stream?.stop()
            stream = null
            bufferedPct = 0
            val st2 = withContext(Dispatchers.IO) {
                TorrentEngine.startStream(
                    source = magnet,
                    saveDir = File(context.filesDir, "downloads/" + item.storeKey),
                    onStatus = { s -> mainHandler.post { status = s } },
                    fileIndex = restartFileIndex!!,
                )
            }
            restartFileIndex = null
            if (st2 == null) {
                status = "Не удалось переключить серию"
                return@LaunchedEffect
            }
            torrentFiles = st2.videoFiles()
            stream = st2
            return@LaunchedEffect
        }

        val st = withContext(Dispatchers.IO) {
            TorrentEngine.startStream(
                source = magnet,
                saveDir = File(context.filesDir, "downloads/" + item.storeKey),
                onStatus = { s ->
                    mainHandler.post {
                        status = s
                        com.torrenttv.app.data.AppLog.log("player", "status: $s")
                    }
                },
                // В пачке сезона включаем файл нужной серии
                fileMatcher = episode?.let { ep ->
                    { name: String -> com.torrenttv.app.data.Episode.fileMatches(name, ep.season, ep.episode) }
                },
                // Прямой запуск файла (из экрана файлов раздачи),
                // иначе — запомненная последняя серия раздачи
                fileIndex = if (startIndex >= 0) startIndex else {
                    // Приоритет: сохранённый S/E → эпизод в файлах → индекс
                    val sEp = store.lastEp(item)
                    val byEp = if (episode == null && sEp != null) torrentFiles.indexOfFirst {
                        com.torrenttv.app.data.Episode.fileMatches(it.second, sEp.first, sEp.second)
                    } else -1
                    if (byEp >= 0) byEp else store.lastFile(item)
                },
            )
        }
        if (st == null) {
            com.torrenttv.app.data.AppLog.log("player", "startStream FAILED (null)")
            status = "Не удалось начать стриминг"
            return@LaunchedEffect
        }
        store.saveLastWatched(item, st.fileIndex, st.videoFile.path)
        com.torrenttv.app.data.AppLog.log(
            "player",
            "started: file=\"${st.videoFile.name}\" size=${st.fileSize}",
        )
        torrentFiles = st.videoFiles()
        stream = st
        // Zona-стиль: сериал смотрим впервые — сначала СПИСОК СЕРИЙ,
        // пользователь сам выбирает (например, 8-ю), а не E01 по умолчанию
        val firstRun = episode == null && startIndex < 0 && !store.hasLastFile(item)
        if (firstRun && torrentFiles.size > 1) {
            showFiles = true
            showPanel = true
        } else {
            // Запомнить серию, с которой смотрели (для сериалов без episode-меты)
            if (episode == null) store.saveLastFile(item, st.fileIndex)
        }

        // Перемотка в середину: поднимаем приоритет кусков вокруг позиции продолжения
        val watching = withContext(Dispatchers.IO) {
            // Позиция хранится по файлу (серии): у каждой своя
            store.continueWatching().firstOrNull { it.sourceKey == sourceKey + ":f" + st.fileIndex }
        }
        val resumeSec = watching?.positionSec ?: 0
        val durationSec = watching?.durationSec ?: 0
        if (resumeSec > 30 && durationSec > 0) {
            withContext(Dispatchers.IO) {
                st.boostPiecesAround(st.fileOffset + st.fileSize * resumeSec / durationSec)
            }
        }

        // Порог старта: 12 МБ или ~5% файла — заголовки, индекс и запас кадров,
        // чтобы первые минуты не спотыкались. Debug-сборка: 256 КБ (проверка
        // кнопок на эмуляторе без реальных пиров)
        val threshold = if (com.torrenttv.app.BuildConfig.DEBUG) 256L * 1024
            else minOf(maxOf(12L * 1024 * 1024, st.fileSize / 20), 96L * 1024 * 1024)
        // Диагностика и таймаут: если за 3 минуты не скачалось — говорим прямо,
        // вместо вечного «Буферизация». Прогресс — по готовности кусков вокруг
        // стартовой позиции (havePiece), а не по общей скачанной голове: при
        // возобновлении с середины голова файла ни при чём
        val startPos = if (resumeSec > 30 && durationSec > 0) st.fileSize * resumeSec / durationSec else 0L
        val deadline = System.currentTimeMillis() + 180_000
        while (!st.isRangeReady(startPos, threshold)) {
            bufferedPct = ((st.readyFraction(startPos, threshold) * 100).toInt()).coerceIn(0, 100)
            val mb = st.headBytes() / 1024 / 1024
            val speed = st.speedBps() / 1024
            netInfo = if (st.peers() > 0) {
                "Пиры: ${st.peers()} · Скачано: $mb МБ · ${speed} КБ/с"
            } else {
                "Пиры не найдены — трекеры/DHT недоступны из этой сети"
            }
            if (System.currentTimeMillis() > deadline) {
                status = "Раздача не отвечает: пиры недоступны из вашей сети. " +
                    "Попробуйте другую раздачу, VPN или Jackett"
                return@LaunchedEffect
            }
            delay(500)
        }
        bufferedPct = 100
    }

    val st = stream
    if (st != null && bufferedPct >= 100) {
        VideoPlayer(
            stream = st,
            item = itemState,
            episode = episode,
            store = store,
            onBack = onBack,
            onEnded = {
                val ep = episode
                if (ep != null) {
                    // Автоплей: следующая серия по метаданным (кэш метабазы работает без сети)
                    scope.launch {
                        val seasons = withContext(Dispatchers.IO) {
                            runCatching { MediaApi.seasons(item) }.getOrDefault(emptyList())
                        }
                        val next = seasons
                            .firstOrNull { it.number == ep.season }
                            ?.episodes?.firstOrNull { it.episode == ep.episode + 1 }
                            ?: seasons
                                .firstOrNull { it.number == ep.season + 1 }
                                ?.episodes?.firstOrNull()
                        if (next != null) onPlayNext(next) else onBack()
                    }
                } else {
                    onBack()
                }
            },
            files = torrentFiles,
            onSelectFile = { idx ->
                if (idx != stream?.fileIndex) {
                    restartFileIndex = idx
                    // Переключили серию — запоминаем её: индекс + имя + S/E
                    // (имя и S/E переживают смену раздачи гонкой)
                    torrentFiles.getOrNull(idx)?.let { store.saveLastWatched(item, idx, it.second) }
                        ?: store.saveLastFile(item, idx)
                }
            },
            onPickTorrent = onPickTorrent,
            skipSegs = skipSegs,
            onSearch = onSearch,
            audioMode = audioMode,
            onAudioMode = { audioMode = it },
)
    } else {
        // Экран буферизации
        BackHandler { com.torrenttv.app.data.AppLog.log("player", "back from buffering"); onBack() }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0E1116))
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            // Реальное качество — из названия раздачи (dn magnet-ссылки)
            val bufQuality = remember(magnet) {
                magnet?.let { m ->
                    runCatching {
                        java.net.URLDecoder.decode(
                            Regex("[&?]dn=([^&]*)").find(m)?.groupValues?.get(1) ?: "", "UTF-8",
                        )
                    }.getOrDefault("")
                }?.let { com.torrenttv.app.data.ReleaseQuality.fromFileName(it) } ?: ""
            }
            if (episode != null) {
                Spacer(Modifier.height(6.dp))
                Text(episode.code, color = Color(0xFFE8ECF4), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Text(status + if (bufQuality.isNotBlank()) "  ·  $bufQuality" else "", color = Color(0xFFE8ECF4), fontSize = 18.sp)
            if (netInfo.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(netInfo, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            }

            if (bufferedPct < 100) {
                Spacer(Modifier.height(16.dp))
                // Прогресс-бар сразу при старте (минимум 2%): раньше до первых
                // кусков показывался только текст — выглядело «пусто»
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF2A3240)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((bufferedPct / 100f).coerceIn(0.02f, 1f))
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFE8ECF4)),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Буферизация: $bufferedPct%", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
            }

            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack) { Text("← Назад") }
        }
    }
}

@Composable
private fun VideoPlayer(
    stream: TorrentEngine.StreamSession,
    item: MediaItem,
    episode: com.torrenttv.app.data.Episode?,
    store: LocalStore,
    onBack: () -> Unit,
    /** Видео дочитано до конца (титры прошёл или «Пропустить титры»). */
    onEnded: () -> Unit,
    /** Видеофайлы торрента (индекс → имя) для выбора серии. */
    files: List<Pair<Int, String>> = emptyList(),
    /** Выбрана другая серия (файл) — перезапуск стрима. */
    onSelectFile: (Int) -> Unit = {},
    /** «Другое качество»: вернуться к выбору раздач. */
    onPickTorrent: () -> Unit = {},
    /** Разметка пропуска (заставка/пересказ/титры) из открытых баз. */
    skipSegs: List<com.torrenttv.app.data.SkipSeg> = emptyList(),
    /** 🔍 Поиск — уйти во вкладку поиска. */
    onSearch: () -> Unit = {},
    /** 🎧 Аудио-режим: видео не рисуется, экран может гаснуть (state в PlayerScreen). */
    audioMode: Boolean = false,
    onAudioMode: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val sourceKey = item.storeKey + (episode?.sourceSuffix ?: "")
    // Позиция просмотра хранится по конкретной серии (файлу торрента)
    val watchKey = stream?.let { sourceKey + ":f" + it.fileIndex } ?: sourceKey

    val resumeSec = remember(stream) {
        store.continueWatching().firstOrNull { it.sourceKey == watchKey }?.positionSec
            ?: store.episodePosition(watchKey)
    }

    val player = remember(stream) {
        ExoPlayer.Builder(context)
            // Перемотка стрелками пульта: ±10 секунд
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            // Буфер побольше: меньше стопов и тормозов при перемотке назад
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(60_000, 180_000, 6_000, 12_000)
                    .setBackBuffer(60_000, true)
                    .build()
            )
            .build()
            .apply {
                // Русский звук по умолчанию (дубляж), если дорожки размечены
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage("rus")
                    .build()
                // Стриминг: ExoPlayer читает недокачанный файл через TorrentDataSource,
                // который дожидается нужных кусков последовательной загрузки
                val dataSourceFactory = DataSource.Factory { TorrentDataSource(stream) }
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(ExoMediaItem.fromUri(Uri.parse("torrent://stream/" + item.storeKey)))
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                if (resumeSec > 5) seekTo(resumeSec * 1000L)
            }
    }

    // Конец видео: обработчик снаружи (автозакрытие фильма / следующая серия)
    var showSkip by remember { mutableStateOf(false) } // «Пропустить титры» доступна

    var seekInfo by remember { mutableStateOf("") }    // «⏩︎ +10 сек» при перемотке пультом
    // Разметка пропуска (SkipDB/IntroDB) грузится в PlayerScreen — тут только тик:
    // заставка/пересказ прыгают при входе в отрезок, титры/анонс — кнопкой
    var autoSkipped by remember(episode) { mutableStateOf(setOf<String>()) }
    var segButton by remember { mutableStateOf<com.torrenttv.app.data.SkipSeg?>(null) }
    // Фокус на кнопке пропуска при её появлении (владелец: «фокус на них»)
    val segSkipFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Тик раз в секунду: авто-заставка/пересказ (прыжок при входе в отрезок)
    // и кнопка «Пропустить» для титров/анонса
    LaunchedEffect(player, skipSegs) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            if (skipSegs.isEmpty() || player.duration <= 0) continue
            val pos = player.currentPosition
            // АВТО: заставка и пересказ — прыгаем на конец отрезка, один раз на серию
            for (seg in skipSegs) {
                if (seg.kind !in setOf("intro", "recap")) continue
                if (seg.kind in autoSkipped) continue
                if (pos >= seg.startMs && pos < seg.endMs) {
                    autoSkipped = autoSkipped + seg.kind
                    player.seekTo(seg.endMs)
                    player.play()
                    seekInfo = if (seg.kind == "intro") "⏭︎ Заставка пропущена" else "⏭︎ Пересказ пропущен"
                    com.torrenttv.app.data.AppLog.log(
                        "player", "${seg.kind} пропущен по базе: ${item.title}",
                    )
                    break
                }
            }
            // КНОПКА: титры (outro) и анонс следующей серии (preview)
            segButton = skipSegs.firstOrNull {
                (it.kind == "outro" || it.kind == "preview") &&
                    pos >= it.startMs && pos < it.endMs
            }
        }
    }
    // Автопереход: «Следующая серия через N…» с кнопками Сейчас/Отмена
    var nextCountdown by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(nextCountdown) {
        val n = nextCountdown ?: return@LaunchedEffect
        if (n <= 0) {
            nextCountdown = null
            onEnded()
        } else {
            delay(1000)
            nextCountdown = n - 1
        }
    }
    // Ошибка плеера: текст поверх экрана вместо молчаливого чёрного экрана
    var errorMsg by remember { mutableStateOf("") }
    // ⏳ Буферизация посреди просмотра: стоп/подкачка — показываем «живой» статус
    var playerBuf by remember { mutableStateOf(false) }
    var bufPct by remember { mutableIntStateOf(0) }
    // Авто-перезапуск после ошибки (как в VLC/Just Player): до 3 попыток
    var retries by remember { mutableStateOf(0) }
    // ---------- Сдвиг субтитров (как в Kodi): кастомный вывод через onCues ----------
    var subShiftMs by remember { mutableIntStateOf(store.playerSubShift()) }
    // Очередь сработавших групп cues: (время активации в мс плеера, cues)
    val pendingCues = remember { mutableListOf<Pair<Long, List<androidx.media3.common.text.Cue>>>() }
    var subCues by remember { mutableStateOf<List<androidx.media3.common.text.Cue>>(emptyList()) }
    LaunchedEffect(player, subShiftMs) {
        while (true) {
            delay(200)
            val effMs = player.currentPosition - subShiftMs
            val snapshot = synchronized(pendingCues) { pendingCues.toList() }
            if (snapshot.isNotEmpty()) {
                subCues = snapshot.lastOrNull { it.first <= effMs }?.second ?: emptyList()
                synchronized(pendingCues) { pendingCues.removeAll { it.first < effMs - 30_000 } }
            }
        }
    }

    // Процент буфера во время подкачки (для индикатора «живого» плеера)
    LaunchedEffect(playerBuf) {
        while (playerBuf) {
            bufPct = player.bufferedPercentage
            delay(400)
        }
    }

    DisposableEffect(player) {
        var audioApplied = false
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) errorMsg = ""
                playerBuf = state == androidx.media3.common.Player.STATE_BUFFERING
                if (state == androidx.media3.common.Player.STATE_ENDED) {                    // «Конец» — только если видео реально проигрывалось:
                    // при нулевой скорости/ошибке источника ExoPlayer может
                    // выдать ENDED сразу после prepare — это не конец фильма
                    if (player.duration > 0 && player.currentPosition > player.duration / 20) {
                        player.seekTo(player.duration)
                        // Сериал — автопереход с отсчётом; фильм — сразу закрыть
                        if (episode != null) nextCountdown = 8 else onEnded()
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val pos = player.currentPosition
                com.torrenttv.app.data.AppLog.log(
                    "player",
                    "playback error: ${error.errorCodeName} pos=${pos / 1000}s retries=$retries",
                )
                if (retries < 3) {
                    // Транзитные ошибки источника/декодера: перемотка на место
                    // и пере-подготовка — в большинстве случаев лечит
                    retries++
                    errorMsg = "Сбой воспроизведения, перезапускаю… ($retries/3)"
                    player.seekTo(pos)
                    player.prepare()
                    player.play()
                } else {
                    errorMsg = "Ошибка воспроизведения: ${error.errorCodeName}. " +
                        "Попробуйте другую раздачу или внешний плеер"
                }
            }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                // Свои субтитры со сдвигом: встроенный SubtitleView выключен
                synchronized(pendingCues) {
                    pendingCues.add(cueGroup.presentationTimeUs / 1000 to cueGroup.cues.toList())
                    if (pendingCues.size > 60) pendingCues.removeAt(0)
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {                // Запомненная аудиодорожка: применяем один раз при появлении дорожек
                if (audioApplied) return
                val want = store.playerAudioLabel()
                if (want.isBlank()) return
                for (g in 0 until tracks.groups.size) {
                    val group = tracks.groups[g]
                    if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
                    for (i in 0 until group.length) {
                        val fmt = group.getTrackFormat(i)
                        val label = (fmt.label ?: "") + " " + (fmt.language ?: "")
                        if (label.lowercase().contains(want.lowercase())) {
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                                .addOverride(
                                    androidx.media3.common.TrackSelectionOverride(
                                        group.mediaTrackGroup, i,
                                    )
                                )
                                .build()
                            audioApplied = true
                            return
                        }
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Автосохранение позиции каждые 5 секунд + показ «Пропустить титры» в последние 3 минуты
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.duration > 0) {
                // Карточка сюда приходит уже обогащённая (PlayerScreen передаёт
                // itemState): постер/описание попадают в «Продолжить»
                store.saveProgress(
                    item,
                    positionSec = (player.currentPosition / 1000).toInt(),
                    durationSec = (player.duration / 1000).toInt(),
                    sourceKey = watchKey,
                    episodeLabel = episode?.code ?: "",
                )
                showSkip = player.duration - player.currentPosition < 3 * 60_000L
                // Держим скачанными ~2 мин вперёд от позиции плеера (как в
                // открытых стримерах): перемотка вперёд попадает в готовые куски
                val st = stream
                if (player.duration > 0) {
                    val bps = st.fileSize * 1000.0 / player.duration
                    st.boostPiecesAround(st.fileOffset + player.currentPosition + (bps * 120).toLong())
                }
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            val finished = runCatching {
                com.torrenttv.app.torrent.TorrentCache.watchedToEnd(
                    player.currentPosition, player.duration,
                )
            }.getOrDefault(false)
            player.release()
            // Политика кэша: досмотрено до конца — удаляем; недосмотрено —
            // оставляем на диске для мгновенного «Продолжить просмотр»,
            // чистку старья делает TorrentCache.enforceLimit перед новым стримом
            if (finished) {
                stream.stop()
                runCatching { stream.saveDir.deleteRecursively() }
            } else {
                stream.stop()
            }
        }
    }

    // Выход из плеера: сначала ПАУЗА — иначе после возврата в меню звук
    // продолжал играть (жалоба «плеер работает в фоне»)
    fun exitPlayer() {
        runCatching { player.pause() }
        com.torrenttv.app.data.AppLog.log("player", "выход: пауза, возвращаюсь в меню")
        onBack()
    }

    // Время armed-BACK: первый BACK только «взводит» выход, второй (в 3 сек) — выходит
    var backArmedAt by remember { mutableStateOf(0L) }

    // BACK-иерархия и BackHandler'ы — ниже, после объявления оверлеев
    // (в Compose активен ПОСЛЕДНИЙ зарегистрированный enabled: базовый выход
    // регистрируем первым, оверлеи — после, они его перекрывают)

    var showTrackMenu by remember { mutableStateOf(false) }
    var showGear by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var gearMsg by remember { mutableStateOf("") }
    // Скорость воспроизведения: живёт вне панели, чтобы выбор не сбрасывался
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    var speedIdx by remember { mutableStateOf(1) }

    // ---------- MX: блокировка экрана от случайных нажатий ----------
    var locked by remember { mutableStateOf(false) }

    // 🎧 Аудио-режим живёт в PlayerScreen (там KeepScreenOn); сюда — параметром

    // ---------- MX: масштаб картинки (Вписать / Растянуть / Заполнить) ----------
    var pv by remember { mutableStateOf<androidx.media3.ui.PlayerView?>(null) }
    val scales = listOf(
        "Вписать" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
        "Растянуть" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
        "Заполнить" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    var scaleIdx by remember { mutableStateOf(store.playerScale().coerceIn(0, scales.size - 1)) }

    // ---------- Панель «как MX»: показывается при действии пульта ----------
    var showFiles by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf(true) }

    // Фокус на кнопке пропуска, как только она появилась, и после закрытия
    // панели: ретраи — кнопка попадает в иерархию не мгновенно, первый
    // requestFocus может прийти раньше композиции и молча не сработать
    LaunchedEffect(segButton, showPanel, showGear, showFiles) {
        if (segButton == null) return@LaunchedEffect
        repeat(5) {
            runCatching { segSkipFocus.requestFocus() }
            kotlinx.coroutines.delay(60)
        }
    }
    var panelTick by remember { mutableStateOf(0) }
    val playFocus = remember { FocusRequester() }

    // BACK-иерархия: базовый выход первым, оверлеи после (перекрывают его).
    // Выход — только двойным BACK (3 сек): первый прячет панель, второй —
    // выходит (жалоба: «назад сразу закрывает плеер» — как в онлайн-плеере)
    BackHandler(enabled = showPanel && !showFiles && !showGear && !showJump && !showTrackMenu) {
        val now = System.currentTimeMillis()
        if (backArmedAt > 0 && now - backArmedAt < 3000) {
            com.torrenttv.app.data.AppLog.log("player", "back: выход из плеера (подтверждён)")
            exitPlayer()
        } else {
            showPanel = false
            backArmedAt = 0
            com.torrenttv.app.data.AppLog.log("player", "back: скрыл панель")
        }
    }
    BackHandler(enabled = showJump) { showJump = false }
    BackHandler(enabled = showFiles) { showFiles = false }
    BackHandler(enabled = showGear) { showGear = false }
    // Меню дорожек закрывает свой BackHandler внутри TrackMenuOverlay

    // Время автоскрытия — настройка из ⚙ (дефолт 20 сек, 0 = не прятать)
    var panelHideSec by remember { mutableStateOf(store.panelHideSec()) }
    LaunchedEffect(panelTick, panelHideSec) {
        if (panelTick > 0 && !showFiles && !showGear && !showTrackMenu) {
            showPanel = true
            if (panelHideSec > 0) {
                kotlinx.coroutines.delay(panelHideSec * 1000L)
                if (!showFiles && !showGear && !showTrackMenu) showPanel = false
            }
        }
    }
    // runCatching ВНУТРИ LaunchedEffect с ретраями: фокус-таргет попадает
    // в иерархию не мгновенно, а защита снаружи краш не ловит — корутина
    // выполнится позже (панель молча оставалась без фокуса)
    LaunchedEffect(showPanel) {
        if (showPanel) {
            repeat(5) {
                runCatching { playFocus.requestFocus() }
                kotlinx.coroutines.delay(60)
            }
        }
    }

    // Единый обработчик кнопок пульта — вызывается и из Compose-перехвата
    // (onPreviewKeyEvent на Box), и из PlayerView: панель появляется при
    // ЛЮБОЙ кнопке, независимо от того, где фокус
    fun handleRemoteKey(keyCode: Int): Boolean {
        // MX-блокировка: пока включена, пульт мёртв; OK — разблокировка
        if (locked) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                locked = false
                seekInfo = "🔓 Разблокировано"
                panelTick++
            } else {
                seekInfo = "🔒 Экран заблокирован — нажмите OK"
            }
            return true
        }
        val panelVisible = showPanel || showFiles || showGear || showTrackMenu || showJump
        // Любая кнопка пульта при скрытой панели СНАЧАЛА показывает панель
        // (как в MX Player), действие выполняется следующим нажатием
        return when (keyCode) {
        // Панель видна: стрелки двигают фокус по кнопкам (как в MX),
        // скрыта: первый показ панели, дальше — фокус по кнопкам.
        // При видимой панели обновляем panelTick — таймер авто-скрытия
        // продлевается, пока пользователь идёт по кнопкам
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (panelVisible) { panelTick++; false }
            else {
                // перемотка + сразу показать панель, чтобы были видны кнопки
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                player.seekTo((player.currentPosition + delta).coerceIn(0L, dur))
                seekInfo = if (delta > 0) "⏩︎ +10 сек" else "⏪︎ −10 сек"
                panelTick++
                true
            }
        }
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            // скрытая панель: UP/DOWN показывают панель (иначе кнопки «не существуют»)
            if (panelVisible) false else { panelTick++; true }
        }
        // Панель видна: OK нажимает кнопку в фокусе; скрыта: OK показывает панель
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            if (panelVisible) false
            else { panelTick++; true }
        }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            if (player.isPlaying) player.pause() else player.play()
            seekInfo = if (player.isPlaying) "▶︎ Продолжаем" else "▮▮ Пауза"
            panelTick++
            true
        }
        KeyEvent.KEYCODE_MEDIA_PLAY -> {
            player.play(); panelTick++; true
        }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            player.pause(); panelTick++; true
        }
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekTo(player.currentPosition + 30_000L); seekInfo = "⏩︎ +30 сек"; panelTick++; true }
        KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekTo((player.currentPosition - 30_000L).coerceAtLeast(0)); seekInfo = "⏪︎ −30 сек"; panelTick++; true }
        KeyEvent.KEYCODE_MENU -> { showTrackMenu = !showTrackMenu; true }
        // BACK: когда всё закрыто и панель скрыта — первый BACK поднимает
        // панель с подсказкой «ещё раз — выход», второй подтверждает.
        // Оверлеи не трогаем: их закрывают BackHandler'ы (свой у меню дорожек)
        KeyEvent.KEYCODE_BACK -> {
            if (!showPanel && !showFiles && !showGear && !showJump && !showTrackMenu) {
                showPanel = true
                panelTick++
                backArmedAt = System.currentTimeMillis()
                seekInfo = "⚠ BACK ещё раз — выход из плеера"
                true
            } else false
        }
        else -> false
        }
    }

    // Регистрация обработчика в Activity: пульт работает при любом фокусе.
    // BACK не перехватываем, пока экран не заблокирован (при блокировке
    // handleRemoteKey сам вернёт true и выход не сработает).
    DisposableEffect(player) {
        MainActivity.remoteKeyHandler = { keyCode -> handleRemoteKey(keyCode) }
        onDispose { MainActivity.remoteKeyHandler = null }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (!audioMode) AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    // Нативные контролы выключены — своя панель «как MX»
                    useController = false
                    // Субтитры рисуем сами (Compose-оверлей со сдвигом ±)
                    subtitleView?.visibility = android.view.View.GONE
                    // Запомненный масштаб (Вписать/Растянуть/Заполнить)
                    resizeMode = scales[scaleIdx].second
                    isFocusable = true
                    // requestFocus на свежем PlayerView иногда падает
                    // («FocusRequester is not initialized») — крашило плеер при входе
                    runCatching { requestFocus() }
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
                        handleRemoteKey(keyCode)
                    }
                    pv = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 🎧 Полноэкранная заглушка аудио-режима: название + возврат видео
        if (audioMode) {
            val backFocusAudio = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { runCatching { backFocusAudio.requestFocus() } }
            androidx.tv.material3.Surface(
                onClick = { onAudioMode(false); seekInfo = "🎬 Видео включено"; panelTick++ },
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(backFocusAudio),
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(0.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xF20A0E14),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xF20A0E14),
                    focusedContentColor = Color.White,
                ),
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("🎧", fontSize = 64.sp)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        item.title,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 60.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Экран ТВ может гаснуть — звук продолжится\nOK — вернуть видео",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(26.dp))
                    Text(
                        "↩ Показать видео",
                        color = Color(0xFF062B10),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xFF7FB84F), RoundedCornerShape(10.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }

        // Ошибка воспроизведения: сверху, чтобы не пересекаться с тостами центра
        if (errorMsg.isNotEmpty()) {            androidx.tv.material3.Text(
                errorMsg,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp)
                    .background(Color(0xCC1F2430), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }

        // Центр: полупрозрачные пауза/перемотка — появляются при любом нажатии
        // на пульте и исчезают вместе с панелью (как в YouTube/Kinopoisk).
        // Фокус на «Пауза»: OK — пауза, ←/→ — между кнопками.
        if ((showPanel || showFiles || showGear || showTrackMenu) && nextCountdown == null) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.tv.material3.Surface(
                    onClick = {
                        val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                        player.seekTo((player.currentPosition - 10_000L).coerceIn(0L, dur))
                        seekInfo = "⏪︎ −10 сек"; panelTick++
                    },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x99151B26),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("⏪︎", fontSize = 30.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 15.dp)) }
                androidx.tv.material3.Surface(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                        panelTick++
                    },
                    modifier = Modifier.focusRequester(playFocus),
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x99151B26),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) {
                    Text(
                        if (player.isPlaying) "▮▮" else "▶︎",
                        fontSize = 34.sp,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                }
                androidx.tv.material3.Surface(
                    onClick = {
                        val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                        player.seekTo((player.currentPosition + 10_000L).coerceIn(0L, dur))
                        seekInfo = "⏩︎ +10 сек"; panelTick++
                    },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x99151B26),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("⏩︎", fontSize = 30.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 15.dp)) }
            }
        }

        // ⏳ Буферизация посреди просмотра: чёрный экран ≠ «зависло»
        if (playerBuf && errorMsg.isBlank() && nextCountdown == null && !showFiles && !showGear && !showTrackMenu) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE0151B26), RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⏳ Буферизация…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("буфер $bufPct%", color = Color(0xB3FFFFFF), fontSize = 15.sp)
            }
        }

        // Автопереход к следующей серии: отсчёт с кнопками (как у стримингов).
        // Фокус на «Отмена» — случайное OK не проматывает сериал
        if (nextCountdown != null) {
            val cancelFocus = remember { FocusRequester() }
            // Ретраи: узел не готов мгновенно; runCatching только внутри
            LaunchedEffect(Unit) {
                repeat(5) {
                    runCatching { cancelFocus.requestFocus() }
                    kotlinx.coroutines.delay(60)
                }
            }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xF0151B26), RoundedCornerShape(16.dp))
                    .padding(horizontal = 30.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.tv.material3.Text(
                    "⏭︎ Следующая серия через $nextCountdown…",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(14.dp))
                Row {
                    Button(
                        onClick = { nextCountdown = null },
                        modifier = Modifier.focusRequester(cancelFocus),
                    ) { Text("✕ Отмена") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { nextCountdown = 0 }) { Text("▶︎ Сейчас") }
                }
            }
        }

        // Субтитры (сдвиг ±): рисуются поверх видео, скрыты при открытых оверлеях
        val visibleSubs = subCues.filter { !it.text.isNullOrBlank() }
        if (visibleSubs.isNotEmpty() && nextCountdown == null && !showFiles && !showGear && !showTrackMenu) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.86f)
                    .padding(bottom = if (showPanel) 160.dp else 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                visibleSubs.forEach { cue ->
                    androidx.tv.material3.Text(
                        cue.text?.toString() ?: "",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black,
                                offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                                blurRadius = 6f,
                            ),
                        ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        // 🔒 Индикатор блокировки (как в MX — замок в углу)
        if (locked) {
            androidx.tv.material3.Text(
                "🔒 Нажмите OK для разблокировки",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp)
                    .background(Color(0xCC1F2430), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // «⏭︎ Пропустить» по разметке баз (титры/анонс) — точные границы
        val segSkip = segButton
        if (segSkip != null) {
            Button(
                onClick = {
                    segButton = null
                    val target = segSkip.endMs
                    // Титры у самого конца файла — сразу «конец серии»,
                    // чтобы включился переход к следующей
                    if (player.duration - target < 90_000L) {
                        player.seekTo((player.duration - 3000).coerceAtLeast(0))
                    } else {
                        player.seekTo(target)
                    }
                    player.play()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 28.dp, bottom = if (showPanel || showGear || showFiles) 150.dp else 28.dp)
                    .focusRequester(segSkipFocus),
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = Color(0xCC1F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFE8ECF4),
                    focusedContentColor = Color.Black,
                ),
            ) {
                Text(
                    if (segSkip.kind == "preview") "⏭︎ Пропустить анонс" else "⏭︎ Пропустить титры",
                    fontSize = 15.sp,
                )
            }
        }
        // «⏭︎ Пропустить титры» — в последние 3 минуты (поверх панели, без разметки)
        if (showSkip && segSkip == null) {
            val skipFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(showSkip, showPanel, showGear, showFiles) {
                if (!showSkip) return@LaunchedEffect
                repeat(5) {
                    runCatching { skipFocus.requestFocus() }
                    kotlinx.coroutines.delay(60)
                }
            }
            Button(
                onClick = {
                    showSkip = false
                    player.seekTo((player.duration - 3000).coerceAtLeast(0))
                    player.play()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 28.dp, bottom = if (showPanel || showGear || showFiles) 150.dp else 28.dp)
                    .focusRequester(skipFocus),
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = Color(0xCC1F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFE8ECF4),
                    focusedContentColor = Color.Black,
                ),
            ) {
                Text(
                    if (episode != null) "⏭︎ Пропустить титры → следующая серия" else "⏭︎ Пропустить титры",
                    fontSize = 15.sp,
                )
            }
        }

        // ---------- Панель «как MX»: сикбар во всю ширину + кнопки ----------
        if (showPanel || showFiles || showGear || showTrackMenu) {
            var posText by remember { mutableStateOf("") }
            var posFrac by remember { mutableStateOf(0f) }
            var bufFrac by remember { mutableStateOf(0f) }
            LaunchedEffect(panelTick) {
                while (true) {
                    val dur = player.duration
                    val p = player.currentPosition
                    posText = "%d:%02d / %s".format(
                        p / 60000, (p / 1000) % 60,
                        if (dur > 0) "%d:%02d".format(dur / 60000, (dur / 1000) % 60) else "—",
                    )
                    if (dur > 0) posFrac = (p.toFloat() / dur).coerceIn(0f, 1f)
                    bufFrac = player.bufferedPercentage / 100f
                    kotlinx.coroutines.delay(400)
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC0A0E14))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                // Заголовок — как в MX: название и серия над сикбаром
                androidx.tv.material3.Text(
                    item.title + (episode?.let { "  ·  ${it.code}" } ?: ""),
                    color = Color(0xFFB8C0CC),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                // Сикбар как в MX: в фокусе ←/→ листают позицию с растущим
                // шагом (10с → 30с → 1мин → 5мин), OK — прыжок
                var jumpTarget by remember { mutableStateOf<Long?>(null) }
                var seekSteps by remember { mutableStateOf(0) }
                fun mxStep(): Long = when {
                    seekSteps < 3 -> 10_000L
                    seekSteps < 6 -> 30_000L
                    seekSteps < 12 -> 60_000L
                    else -> 300_000L
                }
                androidx.tv.material3.Surface(
                    onClick = {
                        jumpTarget?.let { player.seekTo(it) }
                        jumpTarget = null
                        seekSteps = 0
                        seekInfo = "⏱ Перемотка"
                        panelTick++
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusable()
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyUp) { seekSteps = 0; return@onKeyEvent false }
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val base = jumpTarget ?: player.currentPosition
                            val dur = if (player.duration > 0) player.duration else 0L
                            when (e.key) {
                                Key.DirectionLeft -> {
                                    seekSteps++
                                    jumpTarget = (base - mxStep()).coerceIn(0L, dur)
                                    panelTick++
                                    true
                                }
                                Key.DirectionRight -> {
                                    seekSteps++
                                    jumpTarget = if (dur > 0) (base + mxStep()).coerceIn(0L, dur) else base
                                    panelTick++
                                    true
                                }
                                else -> false
                            }
                        },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color(0x33FFFFFF),
                        focusedContentColor = Color.White,
                    ),
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(14.dp),
                        ) {
                            val shownFrac = jumpTarget?.let { jt ->
                                val dur = player.duration
                                if (dur > 0) (jt.toFloat() / dur).coerceIn(0f, 1f) else posFrac
                            } ?: posFrac
                            Box(
                                Modifier
                                    .fillMaxWidth(bufFrac.coerceAtLeast(shownFrac))
                                    .height(5.dp)
                                    .align(Alignment.CenterStart)
                                    .background(Color(0xFF4A5568), RoundedCornerShape(3.dp)),
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth(shownFrac)
                                    .height(8.dp)
                                    .align(Alignment.CenterStart)
                                    .background(if (jumpTarget != null) Color.White else Color(0xB3FFFFFF), RoundedCornerShape(4.dp)),
                            )
                            Text(
                                posText,
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                        if (jumpTarget != null) {
                            val jt = jumpTarget ?: 0L
                            Text(
                                (if (jt >= 3_600_000L)
                                    "⏱ %d:%02d:%02d — OK чтобы перейти".format(jt / 3600000, (jt / 60000) % 60, (jt / 1000) % 60)
                                else
                                    "⏱ %d:%02d — OK чтобы перейти".format(jt / 60000, (jt / 1000) % 60)),
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Пауза/перемотка переехали в центр экрана (полупрозрачные)
                    // ⏱ Перейти к… — пресеты % (середина фильма и т.п.), как в MX/VLC
                    androidx.tv.material3.Surface(
                        onClick = { showJump = true; showFiles = false; showGear = false; panelTick++ },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("⏱", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
                    Spacer(Modifier.width(8.dp))
                    // Скорость: 0.75× → 1× → 1.25× → 1.5× → 2× по кругу
                    LaunchedEffect(Unit) { player.setPlaybackParameters(player.playbackParameters.withSpeed(speeds[speedIdx])) }
                    androidx.tv.material3.Surface(
                        onClick = {
                            speedIdx = (speedIdx + 1) % speeds.size
                            player.setPlaybackParameters(player.playbackParameters.withSpeed(speeds[speedIdx]))
                            seekInfo = "Скорость ${speeds[speedIdx]}×"
                            panelTick++
                        },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color(0xB3FFFFFF),
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("${speeds[speedIdx]}×", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
                    Spacer(Modifier.weight(1f))
                    // ♪ Аудио — дорожки из mkv (как кнопка аудио в MX)
                    androidx.tv.material3.Surface(
                        onClick = { showTrackMenu = true },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("♪ Аудио", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
                    Spacer(Modifier.width(8.dp))
                    // 💬 Субтитры — те же дорожки, вкладка субтитров
                    androidx.tv.material3.Surface(
                        onClick = { showTrackMenu = true },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("💬 Субтитры", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
                    Spacer(Modifier.width(8.dp))
                    // ⛶ Масштаб картинки: Вписать → Растянуть → Заполнить (кнопка scale в MX)
                    androidx.tv.material3.Surface(
                        onClick = {
                            scaleIdx = (scaleIdx + 1) % scales.size
                            pv?.resizeMode = scales[scaleIdx].second
                            store.setPlayerScale(scaleIdx)
                            seekInfo = "Масштаб: ${scales[scaleIdx].first}"
                            panelTick++
                        },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("⛶ ${scales[scaleIdx].first}", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
                    Spacer(Modifier.width(8.dp))
                    // 🔒 Блокировка экрана (как lock в MX): пульт мёртв, OK — разблок
                    androidx.tv.material3.Surface(
                        onClick = {
                            locked = true
                            showPanel = false; showFiles = false; showGear = false; showTrackMenu = false
                            seekInfo = "🔒 Экран заблокирован"
                        },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("🔒", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
                    Spacer(Modifier.width(8.dp))
                    if (files.size > 1) {
                        androidx.tv.material3.Surface(
                            onClick = { showFiles = !showFiles; showGear = false },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF1F2430),
                                contentColor = Color.White,
                                focusedContainerColor = Color(0xFFE8ECF4),
                                focusedContentColor = Color.Black,
                            ),
                        ) { Text(if (showFiles) "✕ Серии" else "☰ Серии (${files.size})", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
                        Spacer(Modifier.width(8.dp))
                    }
                    androidx.tv.material3.Surface(
                        onClick = { showGear = !showGear; showFiles = false },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text(if (showGear) "✕" else "⚙", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
                }
            }
        }
        if (showFiles) {
            // Автоскролл к текущей серии: на сериале с 50 файлами фокус
            // стартует с E01 — листать 39 позиций с пульта мучение
            val filesListState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(showFiles) {
                val cur = files.indexOfFirst { it.first == stream.fileIndex }
                if (cur > 0) runCatching { filesListState.scrollToItem(cur) }
            }
            androidx.compose.foundation.lazy.LazyColumn(
                state = filesListState,
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(560.dp)
                    .height(420.dp)
                    .background(Color(0xF0151B26), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                items(files.size) { n ->
                    val (idx, name) = files[n]
                    val current = idx == stream.fileIndex
                    androidx.tv.material3.Surface(
                        onClick = {
                            showFiles = false
                            if (!current) onSelectFile(idx)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        ),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                            containerColor = if (current) Color(0xFF3A5A2E) else Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) {
                        androidx.tv.material3.Text(
                            (if (current) "▶︎ " else "") + name.substringAfterLast('/'),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        // ⏱ «Перейти к…» — пресеты % поверх видео
        if (showJump) {
            JumpToDialog(
                durationMs = if (player.duration > 0) player.duration else 0L,
                title = item.title,
                onJump = { ms ->
                    player.seekTo(ms)
                    seekInfo = "⏱ Перемотка"
                    panelTick++
                },
                onClose = { showJump = false },
            )
        }

        // ⚙-меню: «Другое качество» и «Внешний плеер» — раскрывается из панели,
        // показывается над панелью
        if (showGear) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = if (showSkip) 230.dp else 150.dp)
                    .background(Color(0xF0151B26), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .padding(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                // 🧲 Качество — выбор раздач/ссылок (владелец: всё в шестерёнку)
                androidx.tv.material3.Surface(
                    onClick = { showGear = false; onPickTorrent() },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    ),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("🧲 Качество (другая раздача)", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) }
                androidx.tv.material3.Surface(
                    onClick = {
                        showGear = false
                        player.pause()
                        onSearch()
                    },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    ),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("🔍 Поиск", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) }
                androidx.tv.material3.Surface(
                    onClick = { showGear = false; onAudioMode(true); seekInfo = "🎧 Аудио-режим"; panelTick++ },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    ),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("🎧 Аудио-режим", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) }
                Spacer(Modifier.height(4.dp))
                // Скрытие панели: цикл по вариантам прямо в меню (выбор сохраняется)
                androidx.tv.material3.Surface(
                    onClick = {
                        // Цикл 10 → 20 → 30 → 60 → не прятать → 10 …
                        val options = listOf(10, 20, 30, 60, 0)
                        val cur = options.indexOf(panelHideSec).takeIf { it >= 0 } ?: 1
                        panelHideSec = options[(cur + 1) % options.size]
                        store.setPanelHideSec(panelHideSec)
                        panelTick++
                        gearMsg = "Панель скрывается через " + when (panelHideSec) {
                            0 -> "никогда"
                            60 -> "1 мин"
                            else -> "$panelHideSec сек"
                        }
                    },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    ),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) {
                    Text(
                        "🕘 Скрытие панели: " + when (panelHideSec) {
                            0 -> "не прятать"
                            60 -> "1 мин"
                            else -> "$panelHideSec сек"
                        },
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (gearMsg.isNotBlank() && seekInfo.isBlank() && errorMsg.isBlank()) {
            LaunchedEffect(gearMsg) {
                kotlinx.coroutines.delay(2500)
                gearMsg = ""
            }
            androidx.tv.material3.Text(
                gearMsg,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xF0151B26), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(16.dp),
            )
        }

        // Индикатор перемотки пультом: один тост в центре — скрыт при открытых
        // оверлеях и ошибке, чтобы тексты не наезжали друг на друга
        if (seekInfo.isNotBlank() && errorMsg.isBlank() && nextCountdown == null && !showFiles && !showGear && !showTrackMenu) {
            LaunchedEffect(seekInfo) {
                kotlinx.coroutines.delay(900)
                seekInfo = ""
            }
            androidx.tv.material3.Text(
                seekInfo,
                color = Color(0xB3FFFFFF),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE0151B26), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        if (showTrackMenu) {
            TrackMenuOverlay(
                player = player,
                onDismiss = { showTrackMenu = false },
                onAudioPicked = { label -> store.setPlayerAudioLabel(label) },
                subShiftMs = subShiftMs,
                onSubShift = { delta ->
                    subShiftMs = (subShiftMs + delta).coerceIn(-5000, 5000)
                    store.setPlayerSubShift(subShiftMs)
                    seekInfo = "Сдвиг сабов: %+.1fс".format(subShiftMs / 1000f)
                },
            )
        }
    }
}
