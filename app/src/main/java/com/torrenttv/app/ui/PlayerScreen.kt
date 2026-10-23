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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
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

        // Перемотка в середину: поднимаем приоритет кусков вокруг позиции продолжения
        val watching = withContext(Dispatchers.IO) {
            store.continueWatching().firstOrNull { it.sourceKey == sourceKey }
        }
        val resumeSec = watching?.positionSec ?: 0
        val durationSec = watching?.durationSec ?: 0
        if (resumeSec > 30 && durationSec > 0) {
            withContext(Dispatchers.IO) {
                st.boostPiecesAround(st.fileOffset + st.fileSize * resumeSec / durationSec)
            }
        }

        // Порог старта: 4 МБ или ~3% файла (чтобы плеер прочитал заголовки/индекс)
        val threshold = maxOf(4L * 1024 * 1024, st.fileSize / 33)
        // Диагностика и таймаут: если за 3 минуты не скачалось — говорим прямо,
        // вместо вечного «Буферизация»
        val deadline = System.currentTimeMillis() + 180_000
        while (st.headBytes() < threshold) {
            bufferedPct = ((st.headBytes() * 100) / threshold).toInt().coerceIn(0, 100)
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
            link = com.torrenttv.app.data.OnlineService.OnlineLink(url = tl, label = "torrserve"),
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
                if (idx != stream?.fileIndex) restartFileIndex = idx
            },
            onPickTorrent = onPickTorrent,
            externalUrl = torrLink,
        )
    } else {
        // Экран буферизации
        BackHandler { onBack() }
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

    val resumeSec = remember {
        store.continueWatching().firstOrNull { it.sourceKey == sourceKey }?.positionSec ?: 0
    }

    val player = remember(stream) {
        ExoPlayer.Builder(context)
            // Перемотка стрелками пульта: ±10 секунд
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
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
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    player.seekTo(player.duration)
                    onEnded()
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
                    sourceKey = sourceKey,
                    episodeLabel = episode?.code ?: "",
                )
                showSkip = player.duration - player.currentPosition < 3 * 60_000L
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
            // Политика хранения: удаляем скачанное после просмотра и гасим торрент
            stream.stop()
            runCatching { stream.saveDir.deleteRecursively() }
        }
    }

    BackHandler { onBack() }

    var showTrackMenu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    // Контролы скрываются через 10 секунд бездействия пульта
                    controllerShowTimeoutMs = 10_000
                    // MENU пульта — оверлей выбора аудиодорожек/субтитров
                    // (встроенный контроллер Media3 аудио выбирать не умеет)
                    setOnKeyListener { _, keyCode, event ->
                        // Влево/вправо — перемотка ±10 сек пультом
                        if (event.action == KeyEvent.ACTION_UP &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                        ) {
                            val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                            val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                            player.seekTo((player.currentPosition + delta).coerceIn(0L, dur))
                            seekInfo = if (delta > 0) "⏩ +10 сек" else "⏪ −10 сек"
                            showController() // панель: сикбар, время, кнопки
                            true
                        } else if (event.action == KeyEvent.ACTION_UP &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == KeyEvent.KEYCODE_ENTER ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        ) {
                            // ОК на пульте — пауза/продолжить
                            if (player.isPlaying) player.pause() else player.play()
                            seekInfo = if (player.isPlaying) "▶ Продолжаем" else "⏸ Пауза"
                            showController()
                            true
                        } else if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU) {
                            showTrackMenu = !showTrackMenu
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // «⏭ Пропустить титры» — в последние 3 минуты: к следующей серии (сериал)
        // или к закрытию плеера (фильм)
        if (showSkip) {
            Box(Modifier.fillMaxSize()) {
                Button(
                    onClick = {
                        showSkip = false
                        // Прыжок к самому концу: STATE_ENDED запустит переход к следующей серии
                        player.seekTo((player.duration - 3000).coerceAtLeast(0))
                        player.play()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(32.dp),
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
        }

        // «☰ Серии» — выбор другой серии/файла внутри торрента
        var showFiles by remember { mutableStateOf(false) }
        if (files.size > 1) {
            androidx.tv.material3.Button(
                onClick = { showFiles = !showFiles },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp),
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = Color(0xCC1F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFF5B942),
                    focusedContentColor = Color.Black,
                ),
            ) {
                Text(if (showFiles) "✕ Серии" else "☰ Серии (${files.size})", fontSize = 15.sp)
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

        // ⚙ — качество (выбор раздачи) и внешний плеер
        var showGear by remember { mutableStateOf(false) }
        var gearMsg by remember { mutableStateOf("") }
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = if (showSkip) 110.dp else 24.dp),
            horizontalAlignment = Alignment.End,
        ) {
            androidx.tv.material3.Button(
                onClick = { showGear = !showGear },
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = Color(0xCC1F2430),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFF5B942),
                    focusedContentColor = Color.Black,
                ),
            ) { Text(if (showGear) "✕" else "⚙", fontSize = 16.sp) }
        }
        if (showGear) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = if (showSkip) 162.dp else 76.dp)
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
        if (gearMsg.isNotBlank()) {
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

        // Индикатор перемотки пультом
        if (seekInfo.isNotBlank()) {
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
