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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    /** Запуск конкретного файла раздачи (из экрана файлов). */
    startIndex: Int = -1,
) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    val scope = rememberCoroutineScope()
    // Источник эпизода хранится с суффиксом (":s01e05") — у каждой серии свой
    val sourceSuffix = episode?.sourceSuffix ?: ""
    val sourceKey = item.storeKey + sourceSuffix
    val magnet = remember(episode) { store.getMagnet(item, sourceSuffix) }

    var status by remember { mutableStateOf(if (magnet == null) "Нет magnet-ссылки" else "Подготовка…") }
    var bufferedPct by remember { mutableStateOf(0) }
    var netInfo by remember { mutableStateOf("") }
    var stream by remember { mutableStateOf<TorrentEngine.StreamSession?>(null) }
    var torrLink by remember { mutableStateOf<String?>(null) }
    // Серии внутри торрента: (файл, имя) + выбор другой серии
    var torrentFiles by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var restartFileIndex by remember { mutableStateOf<Int?>(null) }

    // Приоритет движков: 1) TorrServe (внешний), 2) внутренний jlibtorrent
    LaunchedEffect(magnet) {
        if (magnet == null) return@LaunchedEffect
        val tsHost = store.torrServeHost()
        val tsReady = withContext(Dispatchers.IO) {
            com.torrenttv.app.data.TorrServeService.ensureRunning(context, tsHost)
        }
        if (tsReady) {
            status = "Добавляю в TorrServe…"
            com.torrenttv.app.data.AppLog.log("player", "torrserve: echo ok, adding")
            val ts = withContext(Dispatchers.IO) {
                com.torrenttv.app.data.TorrServeService.addMagnet(tsHost, magnet, item.title) { s -> status = s }
            }
            val file = ts?.let { t ->
                val ep = episode
                if (ep != null) t.files.firstOrNull {
                    com.torrenttv.app.data.Episode.fileMatches(it.path, ep.season, ep.episode)
                } ?: com.torrenttv.app.data.TorrServeService.bestVideo(t)
                else com.torrenttv.app.data.TorrServeService.bestVideo(t)
            }
            if (ts != null && file != null) {
                val buffered = withContext(Dispatchers.IO) {
                    com.torrenttv.app.data.TorrServeService.preloadFile(tsHost, ts.hash, file) { s -> status = s }
                }
                com.torrenttv.app.data.AppLog.log("player", "torrserve: ok hash=${ts.hash} file=\"${file.path}\" buffered=$buffered")
                if (buffered) {
                    torrLink = com.torrenttv.app.data.TorrServeService.streamUrl(tsHost, ts.hash, file)
                    return@LaunchedEffect
                }
                com.torrenttv.app.data.AppLog.log("player", "torrserve: no buffer, fallback to internal engine")
            } else {
                com.torrenttv.app.data.AppLog.log("player", "torrserve: failed, fallback to internal engine")
            }
        }

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
                    onStatus = { status = it },
                    fileIndex = restartFileIndex!!,
                )
            }
            restartFileIndex = null
            if (st2 == null) {
                status = "Не удалось переключить серию — нажмите «Назад» и зайдите снова"
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
                onStatus = {
                    status = it
                    com.torrenttv.app.data.AppLog.log("player", "status: $it")
                },
                // В пачке сезона включаем файл нужной серии
                fileMatcher = episode?.let { ep ->
                    { name: String -> com.torrenttv.app.data.Episode.fileMatches(name, ep.season, ep.episode) }
                },
                // Прямой запуск файла (из экрана файлов раздачи),
                // иначе — запомненная последняя серия раздачи
                fileIndex = if (startIndex >= 0) startIndex else store.lastFile(item),
            )
        }
        if (st == null) {
            com.torrenttv.app.data.AppLog.log("player", "startStream FAILED (null)")
            status = "Не удалось начать стриминг"
            return@LaunchedEffect
        }
        com.torrenttv.app.data.AppLog.log(
            "player",
            "started: file=\"${st.videoFile.name}\" size=${st.fileSize}",
        )
        torrentFiles = st.videoFiles()
        stream = st
        // Запомнить серию, с которой смотрели (для сериалов без episode-меты)
        if (episode == null) store.saveLastFile(item, st.fileIndex)

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

    // TorrServe готов — играем его HTTP-стрим родным онлайн-плеером
    val tl = torrLink
    if (tl != null) {
        OnlinePlayerScreen(
            link = OnlineLink(url = tl, label = "torrserve"),
            item = item,
            title = item.title,
            onBack = onBack,
        )
        return
    }

    val st = stream
    if (st != null && bufferedPct >= 100) {
        VideoPlayer(
            stream = st,
            item = item,
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
                    // Переключили серию — запоминаем её как последнюю
                    store.saveLastFile(item, idx)
                }
            },
            onPickTorrent = onPickTorrent,
            externalUrl = torrLink,
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
            if (episode != null) {
                Spacer(Modifier.height(6.dp))
                Text(episode.code, color = Color(0xFFF5B942), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Text(status, color = Color(0xFFF5B942), fontSize = 18.sp)
            if (netInfo.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(netInfo, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            }

            if (bufferedPct > 0 && bufferedPct < 100) {
                Spacer(Modifier.height(16.dp))
                // Прогресс-бар (D-pad friendly, без Material-зависимостей)
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(10.dp)
                        .background(Color(0xFF1F2430)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(bufferedPct / 100f)
                            .height(10.dp)
                            .background(Color(0xFFF5B942)),
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
    /** URL для внешнего плеера (TorrServe) или null — внешний недоступен. */
    externalUrl: String? = null,
) {
    val context = LocalContext.current
    val sourceKey = item.storeKey + (episode?.sourceSuffix ?: "")
    // Позиция просмотра хранится по конкретной серии (файлу торрента)
    val watchKey = stream?.let { sourceKey + ":f" + it.fileIndex } ?: sourceKey

    val resumeSec = remember(stream) {
        store.continueWatching().firstOrNull { it.sourceKey == watchKey }?.positionSec ?: 0
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
    var seekInfo by remember { mutableStateOf("") }    // «⏩ +10 сек» при перемотке пультом
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
    // Авто-перезапуск после ошибки (как в VLC/Just Player): до 3 попыток
    var retries by remember { mutableStateOf(0) }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) errorMsg = ""
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    // «Конец» — только если видео реально проигрывалось:
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
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Автосохранение позиции каждые 5 секунд + показ «Пропустить титры» в последние 3 минуты
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.duration > 0) {
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
                if (st != null && player.duration > 0) {
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

    BackHandler { onBack() }

    var showTrackMenu by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var showGear by remember { mutableStateOf(false) }
    var gearMsg by remember { mutableStateOf("") }
    // Скорость воспроизведения: живёт вне панели, чтобы выбор не сбрасывался
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    var speedIdx by remember { mutableStateOf(1) }

    // ---------- MX: блокировка экрана от случайных нажатий ----------
    var locked by remember { mutableStateOf(false) }

    // ---------- MX: масштаб картинки (Вписать / Растянуть / Заполнить) ----------
    var pv by remember { mutableStateOf<androidx.media3.ui.PlayerView?>(null) }
    val scales = listOf(
        "Вписать" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
        "Растянуть" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
        "Заполнить" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    var scaleIdx by remember { mutableStateOf(0) }

    // ---------- Панель «как MX»: показывается при действии пульта ----------
    var panelTick by remember { mutableStateOf(1) }   // панель видна сразу при входе
    var showPanel by remember { mutableStateOf(true) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(panelTick) {
        if (panelTick > 0 && !showFiles && !showGear && !showTrackMenu) {
            showPanel = true
            kotlinx.coroutines.delay(4500)
            if (!showFiles && !showGear && !showTrackMenu) showPanel = false
        }
    }
    LaunchedEffect(showPanel) { if (showPanel) playFocus.requestFocus() }

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
        val panelVisible = showPanel || showFiles || showGear || showTrackMenu
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
                seekInfo = if (delta > 0) "⏩ +10 сек" else "⏪ −10 сек"
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
            seekInfo = if (player.isPlaying) "▶ Продолжаем" else "⏸ Пауза"
            panelTick++
            true
        }
        KeyEvent.KEYCODE_MEDIA_PLAY -> {
            player.play(); panelTick++; true
        }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            player.pause(); panelTick++; true
        }
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekTo(player.currentPosition + 30_000L); seekInfo = "⏩ +30 сек"; panelTick++; true }
        KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekTo((player.currentPosition - 30_000L).coerceAtLeast(0)); seekInfo = "⏪ −30 сек"; panelTick++; true }
        KeyEvent.KEYCODE_MENU -> { showTrackMenu = !showTrackMenu; true }
        // BACK при открытой панели прячет панель, при скрытой — выход из плеера
        // (меню дорожек не трогаем: его закрывает свой BackHandler в оверлее)
        KeyEvent.KEYCODE_BACK -> {
            if (showFiles || showGear) {
                showFiles = false; showGear = false; panelTick++; true
            } else if (showPanel) {
                showPanel = false; true
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
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    // Нативные контролы выключены — своя панель «как MX»
                    useController = false
                    isFocusable = true
                    requestFocus()
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
                        handleRemoteKey(keyCode)
                    }
                    pv = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Ошибка воспроизведения: сверху, чтобы не пересекаться с тостами центра
        if (errorMsg.isNotEmpty()) {
            androidx.tv.material3.Text(
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

        // «⏭ Пропустить титры» — в последние 3 минуты (поверх панели)
        if (showSkip) {
            Button(
                onClick = {
                    showSkip = false
                    player.seekTo((player.duration - 3000).coerceAtLeast(0))
                    player.play()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 28.dp, bottom = if (showPanel || showGear || showFiles) 150.dp else 28.dp),
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = Color(0xCC1F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFF5B942),
                    focusedContentColor = Color.Black,
                ),
            ) {
                Text(
                    if (episode != null) "⏭ Пропустить титры → следующая серия" else "⏭ Пропустить титры",
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
                // Сикбар во всю ширину: буфер + позиция + время
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(bufFrac.coerceAtLeast(posFrac))
                            .height(5.dp)
                            .align(Alignment.CenterStart)
                            .background(Color(0xFF4A5568), RoundedCornerShape(3.dp)),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(posFrac)
                            .height(8.dp)
                            .align(Alignment.CenterStart)
                            .background(Color(0xFFF5B942), RoundedCornerShape(4.dp)),
                    )
                    Text(
                        posText,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // play/pause — фокус приходит сюда при показе панели
                    androidx.tv.material3.Surface(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier.focusRequester(playFocus),
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFF5B942),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("⏸ / ▶", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
                    Spacer(Modifier.width(8.dp))
                    androidx.tv.material3.Surface(
                        onClick = {
                            val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                            player.seekTo((player.currentPosition - 10_000L).coerceIn(0L, dur))
                            seekInfo = "⏪ −10 сек"; panelTick++
                        },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFF5B942),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("⏪ 10с", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
                    Spacer(Modifier.width(8.dp))
                    androidx.tv.material3.Surface(
                        onClick = {
                            val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                            player.seekTo((player.currentPosition + 10_000L).coerceIn(0L, dur))
                            seekInfo = "⏩ +10 сек"; panelTick++
                        },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFF5B942),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("⏩ 10с", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
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
                            contentColor = Color(0xFFF5B942),
                            focusedContainerColor = Color(0xFFF5B942),
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
                            focusedContainerColor = Color(0xFFF5B942),
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
                            focusedContainerColor = Color(0xFFF5B942),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("💬 Субтитры", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
                    Spacer(Modifier.width(8.dp))
                    // ⛶ Масштаб картинки: Вписать → Растянуть → Заполнить (кнопка scale в MX)
                    androidx.tv.material3.Surface(
                        onClick = {
                            scaleIdx = (scaleIdx + 1) % scales.size
                            pv?.resizeMode = scales[scaleIdx].second
                            seekInfo = "Масштаб: ${scales[scaleIdx].first}"
                            panelTick++
                        },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFF5B942),
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
                            focusedContainerColor = Color(0xFFF5B942),
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
                                focusedContainerColor = Color(0xFFF5B942),
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
                            focusedContainerColor = Color(0xFFF5B942),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text(if (showGear) "✕" else "⚙", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
                }
            }
        }
        if (showFiles) {
            androidx.compose.foundation.lazy.LazyColumn(
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
                            focusedContainerColor = Color(0xFFF5B942),
                            focusedContentColor = Color.Black,
                        ),
                    ) {
                        androidx.tv.material3.Text(
                            (if (current) "▶ " else "") + name.substringAfterLast('/'),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
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
                androidx.tv.material3.Surface(
                    onClick = { showGear = false; onPickTorrent() },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    ),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFF5B942),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("🧲 Другое качество", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) }
                Spacer(Modifier.height(4.dp))
                androidx.tv.material3.Surface(
                    onClick = {
                        showGear = false
                        val url = externalUrl
                        if (url == null) {
                            gearMsg = "Внешний плеер доступен при стриме через TorrServe"
                        } else {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(android.net.Uri.parse(url), "video/*")
                                addFlags(
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                )
                            }
                            if (!runCatching { context.startActivity(intent) }.isSuccess) {
                                gearMsg = "Внешний плеер не найден — установите VLC или MX Player"
                            }
                        }
                    },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    ),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFF5B942),
                        focusedContentColor = Color.Black,
                    ),
                ) { Text("⤴ Внешний плеер", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) }
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
        if (seekInfo.isNotBlank() && errorMsg.isBlank() && !showFiles && !showGear && !showTrackMenu) {
            LaunchedEffect(seekInfo) {
                kotlinx.coroutines.delay(900)
                seekInfo = ""
            }
            androidx.tv.material3.Text(
                seekInfo,
                color = Color(0xFFF5B942),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE0151B26), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        if (showTrackMenu) {
            TrackMenuOverlay(player = player, onDismiss = { showTrackMenu = false })
        }
    }
}
