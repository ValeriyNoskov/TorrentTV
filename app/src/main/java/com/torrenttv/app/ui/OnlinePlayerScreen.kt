package com.torrenttv.app.ui

import androidx.activity.compose.BackHandler
import android.view.KeyEvent
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.MainActivity
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Прямая ссылка на поток (сейчас — только HTTP-стрим TorrServe).
 * Раньше здесь жили и онлайн-источники Filmix/HDRezka — вырезаны.
 */
data class OnlineLink(
    val url: String,
    val label: String,
    val season: Int = 0,
    val episode: Int = 0,
    val quality: String = "",
    val translation: String = "",
    /** Referer для CDN-плеера (нужен для онлайн-источников, TorrServe — без). */
    val referer: String = "",
)

@Composable
fun OnlinePlayerScreen(
    link: OnlineLink,
    item: MediaItem,
    title: String,
    onBack: () -> Unit,
    /** Сохранять позицию в «Продолжить смотреть» (трейлерам не нужно). */
    saveProgress: Boolean = true,
    /** Сразу открыть список серий (первый запуск сериала — Zona-стиль). */
    showFilesOnStart: Boolean = false,
    /** Серии (для TorrServe): (индекс файла, имя). Пусто — кнопки «Серии» нет. */
    files: List<Pair<Int, String>> = emptyList(),
    /** Текущая серия (индекс в files). */
    currentFile: Int = -1,
    /** Выбрали другую серию — родитель перезапустит стрим. */
    onSelectFile: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    // Каждый вход в плеер — в лог: по выгрузке видно цепочку «нажал → открылся → ошибка»
    androidx.compose.runtime.LaunchedEffect(link.url) {
        com.torrenttv.app.data.AppLog.log(
            "online",
            "открыл плеер: «$title» ${link.label} (сезон=${link.season} серия=${link.episode})",
        )
    }
    val store = remember { com.torrenttv.app.data.LocalStore(context) }
    // Ключ источника: своя позиция на каждую ссылку (и серию) онлайн-просмотра
    val onlineKey = item.storeKey + ":online" +
        (if (link.season > 0) ":s%02de%02d".format(link.season, link.episode) else "")
    // С какой секунды продолжать («Продолжить просмотр» + запасное хранилище серий)
    val resumeSec = remember {
        store.continueWatching().firstOrNull { it.sourceKey == onlineKey }?.positionSec
            ?: store.episodePosition(onlineKey)
    }
    var showQuality by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showTrackMenu by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    // Список серий (TorrServe): выбор серии прямо в плеере
    var showFiles by remember { mutableStateOf(showFilesOnStart) }
    // Авто-повтор при ошибке старта потока TorrServe (3003: данные ещё не готовы)
    var retryTick by remember { mutableStateOf(0) }
    var retryCount by remember { mutableStateOf(0) }
    // Кнопки на панели: игра/пауза (иконка меняется) + фокус на кнопке Play
    var playing by remember { mutableStateOf(true) }
    val playBtnFocus = remember { FocusRequester() }
    var maxQuality by remember { mutableStateOf<Int?>(null) } // null = авто
    var seekInfo by remember { mutableStateOf("") }           // «⏩ +10 сек» при перемотке пультом
    // ---------- Состояние: буферизация и ошибки (чёрный экран ≠ «зависло») ----------
    var playState by remember { mutableStateOf(Player.STATE_IDLE) }
    var errorMsg by remember { mutableStateOf("") }
    var bufPct by remember { mutableIntStateOf(0) }
    // Панель контролов при действии пульта (счётчик действий — триггер показа).
    // Видна сразу при входе — пользователь видит, что плеер жив
    var panelTick by remember { mutableStateOf(1) }
    var showPanel by remember { mutableStateOf(true) }
    LaunchedEffect(panelTick) {
        if (panelTick > 0 && !showSettings && !showQuality && !showTrackMenu) {
            showPanel = true
            kotlinx.coroutines.delay(4000)
            if (!showSettings && !showQuality && !showTrackMenu) showPanel = false
        }
    }
    LaunchedEffect(showPanel) { if (showPanel) runCatching { playBtnFocus.requestFocus() } }

    val player = remember(link.url, retryTick) {
        // CDN проверяет Referer: у Filmix — filmix.ac, у HDRezka — её зеркало.
        // TorrServe (label="torrserve") — локальный стрим, Referer не нужен.
        // TorrServe-движок узнаём по адресу, а не по метке ссылки:
        // SmartPlay создаёт ссылки с произвольными label («⚡ Играть сразу…»),
        // из-за метки не срабатывали retry/таймауты
        val isTorrserve = link.url.contains("127.0.0.1:8090")
        val referer = if (isTorrserve) "" else link.referer.ifBlank { "https://filmix.ac/" }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .apply { if (referer.isNotBlank()) setDefaultRequestProperties(mapOf("Referer" to referer)) }
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(if (isTorrserve) 60_000 else 15_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                // Русский звук по умолчанию (дубляж), если дорожки размечены
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage("rus")
                    .build()
                // .m3u8 → HLS-источник, остальное — прогрессивный стрим
                setMediaItem(
                    ExoMediaItem.Builder()
                        .setUri(link.url)
                        .setMimeType(
                            if (link.url.contains(".m3u8", ignoreCase = true))
                                androidx.media3.common.MimeTypes.APPLICATION_M3U8
                            else null
                        )
                        .build()
                )
                prepare()
                // Продолжаем с сохранённого места (если смотрели раньше)
                if (resumeSec > 30) {
                    seekTo(resumeSec * 1000L)
                    seekInfo = "▶ Продолжаем с %d:%02d".format(resumeSec / 60, resumeSec % 60)
                }
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        playState = state
                        // Стрим реально запустился — сбрасываем счётчик повторов
                        if (state == Player.STATE_READY) retryCount = 0
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        com.torrenttv.app.data.AppLog.log(
                            "player",
                            "playback error: ${error.errorCodeName} code=${error.errorCode} retry=$retryCount link=${link.url.take(120)}",
                        )
                        // TorrServe часто не успевает скачать начало новой серии —
                        // молча перезапускаем плеер до 3 раз, без алармов
                        if (isTorrserve && retryCount < 3) {
                            retryCount++
                            seekInfo = "🔄 Запускаю поток (попытка ${retryCount + 1})…"
                            panelTick++
                            // TorrServe после IO-ошибки (2000) ещё докачивает серию —
                            // мгновенный рестарт попадает в то же состояние
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                kotlinx.coroutines.delay(1500)
                                retryTick++
                            }
                        } else {
                            errorMsg = "Ошибка воспроизведения: ${error.errorCodeName}"
                            // Автовыгрузка лога: Gist (вшитый токен) → dpaste
                            val ctx = context
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                val url = runCatching {
                                    com.torrenttv.app.data.AppLog.sendToPastebin(
                                        com.torrenttv.app.update.UpdateManager.ghToken(ctx).ifBlank { null },
                                    )
                                }.getOrNull()
                                // Состояние Compose пишем только из главного потока
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    if (!url.isNullOrBlank()) {
                                        com.torrenttv.app.data.AppLog.log("upload", "автовыгрузка ок")
                                        // URL выгрузки пользователю не показываем (не для него),
                                        // разработчик получает адрес через gh gist list
                                        errorMsg = "Ошибка: ${error.errorCodeName}\nЛог отправлен"
                                    } else {
                                        com.torrenttv.app.data.AppLog.log("upload", "автовыгрузка FAIL")
                                    }
                                }
                            }
                        }
                    }
                })
            }
    }

    // Запоминание позиции: каждые 5 секунд + при выходе из плеера
    LaunchedEffect(player) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            if (!saveProgress) return@LaunchedEffect
            val pos = (player.currentPosition / 1000).toInt()
            val dur = (player.duration / 1000).toInt()
            if (pos > 10 && dur > 0) {
                // Досмотрено до конца — удаляем из «Продолжить просмотр»
                if (pos >= dur * 95 / 100) store.removeProgress(item)
                else store.saveProgress(item, pos, dur, onlineKey)
            }
        }
    }
    DisposableEffect(player) {
        onDispose {
            val pos = (player.currentPosition / 1000).toInt()
            val dur = (player.duration / 1000).toInt()
            if (saveProgress && pos > 10 && dur > 0 && pos < dur * 95 / 100) {
                store.saveProgress(item, pos, dur, onlineKey)
            }
            player.release()
        }
    }

    // Ограничение качества видео (для HLS-мастера с вариантами)
    LaunchedEffect(player, maxQuality) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, maxQuality ?: Int.MAX_VALUE)
            .build()
    }

    // Процент буфера во время загрузки (для индикатора на чёрном экране)
    LaunchedEffect(playState) {
        if (playState == Player.STATE_BUFFERING) {
            while (playState == Player.STATE_BUFFERING) {
                bufPct = player.bufferedPercentage
                delay(300)
            }
            bufPct = 100
        }
    }

    // Запомненная аудиодорожка: применяем один раз при появлении дорожек
    DisposableEffect(player) {
        var audioApplied = false
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
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
                                .addOverride(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
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

    // ---------- Единый обработчик пульта: работает при ЛЮБОМ фокусе ----------
    // Как в сетевых плеерах: OK — пауза, ←/→ — перемотка, ↑ — меню,
    // MENU — дорожки. Никаких кнопок на панели — фокус не нужен
    fun handleRemoteKey(keyCode: Int): Boolean {
        // Список серий открыт — BACK закрывает его, не выходя из плеера
        if (showFiles) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { showFiles = false; return true }
            return false
        }
        // Панель видима — стрелки/OK управляют ФОКУСОМ по кнопкам панели
        // (как в Zona), глобальные действия пульта уступают
        if (showPanel) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { showSettings = !showSettings; panelTick++; return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return false
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                player.seekTo((player.currentPosition + delta).coerceIn(0L, dur))
                seekInfo = if (delta > 0) "⏩ +10 сек" else "⏪ −10 сек"
                panelTick++
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { showSettings = !showSettings; panelTick++; true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { panelTick++; true }
            // OK — пауза/продолжить (как в YouTube TV)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play()
                seekInfo = if (player.isPlaying) "▶ Продолжаем" else "⏸ Пауза"
                panelTick++; true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); panelTick++; true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); panelTick++; true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekTo(player.currentPosition + 30_000L); seekInfo = "⏩ +30 сек"; panelTick++; true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekTo((player.currentPosition - 30_000L).coerceAtLeast(0)); seekInfo = "⏪ −30 сек"; panelTick++; true }
            KeyEvent.KEYCODE_MENU -> { showTrackMenu = !showTrackMenu; true }
            // BACK: панель скрыта — СНАЧАЛА показываем панель (защита от
            // случайного нажатия: выход только вторым BACK при видимой панели).
            // Открытые оверлеи отдают false — их закрывают BackHandler'ы ниже
            KeyEvent.KEYCODE_BACK -> {
                if (!showPanel && !showSettings && !showQuality && !showTrackMenu && !showFiles && !showJump) {
                    showPanel = true
                    panelTick++
                    seekInfo = "⚠ BACK ещё раз — выход из плеера"
                    true
                } else false
            }
            else -> false
        }
    }
    DisposableEffect(player) {
        MainActivity.remoteKeyHandler = { keyCode -> handleRemoteKey(keyCode) }
        onDispose { MainActivity.remoteKeyHandler = null }
    }

    // BACK-иерархия: в Compose активен ПОСЛЕДНИЙ зарегистрированный enabled —
    // поэтому базовый выход первым, оверлеи — после (перекрывают его)
    BackHandler(enabled = showPanel && !showSettings && !showQuality && !showTrackMenu && !showFiles && !showJump) { onBack() }
    BackHandler(enabled = showJump) { showJump = false }
    BackHandler(enabled = showFiles) { showFiles = false }
    BackHandler(enabled = showTrackMenu) { showTrackMenu = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showQuality) { showQuality = false }

    // Галочка «Всегда внешний плеер»: сразу открываем во внешнем плеере и выходим
    val storePrefs = remember { com.torrenttv.app.data.LocalStore(context) }
    var extMsg by remember { mutableStateOf("") }
    fun openExternal(auto: Boolean = false) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(
                android.net.Uri.parse(link.url),
                if (link.url.contains(".m3u8", true)) "application/x-mpegURL" else "video/*",
            )
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) {
            extMsg = "Внешний плеер не найден — установите VLC или MX Player, либо выключите «Всегда внешний плеер» в настройках"
        } else if (auto) {
            onBack()
        }
    }
    LaunchedEffect(Unit) {
        if (storePrefs.useExternalPlayer()) openExternal(auto = true)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // Нативные контролы выключены: перехватывают пульт и мешают
                    // дойти до нашей панели (⚙ Качество). Панель — своя, Compose.
                    useController = false
                    // Пульт: всё через единый handleRemoteKey (панель при любой кнопке)
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action != android.view.KeyEvent.ACTION_UP) false
                        else handleRemoteKey(keyCode)
                    }
                }
            },
            // КРИТИЧНО: при смене серии создаётся НОВЫЙ player (remember(link.url)),
            // а AndroidView не пересоздаётся — без update он остаётся привязан к
            // старому release'нутому плееру → видео нет, только звук
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            "$title · ${link.label}",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )

        // Индикатор перемотки пультом: скрыт при открытых меню (не наезжает)
        if (seekInfo.isNotBlank() && !showQuality && !showSettings) {
            LaunchedEffect(seekInfo) {
                kotlinx.coroutines.delay(900)
                seekInfo = ""
            }
            Text(
                seekInfo,
                color = Color(0xFFF5B942),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE0151B26), RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        // ⏳ Буферизация: чёрный экран — значит «грузим», а не «зависло»
        if (playState == Player.STATE_BUFFERING && errorMsg.isBlank()) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE0151B26), RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⏳ Загружаю видео…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("$title · буфер $bufPct%", color = Color(0xFFF5B942), fontSize = 15.sp)
            }
        }

        // Ошибка воспроизведения: сообщение + Повторить/Назад (не вечный чёрный экран)
        if (errorMsg.isNotBlank()) {
            val retryFocus = remember { FocusRequester() }
            // runCatching ВНУТРИ LaunchedEffect: фокус-таргет может быть ещё не готов —
            // защита снаружи краш не ловит (выполняется из корутины позже)
            LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xF0151B26), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    errorMsg,
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Row {
                    Surface(
                        onClick = { errorMsg = ""; player.prepare(); player.play() },
                        modifier = Modifier.focusRequester(retryFocus),
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFF5B942),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                    ) { Text("🔄 Повторить", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        onClick = onBack,
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFFFD07A),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                    ) { Text("← Назад", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                }
            }
        }

        // Меню дорожек/субтитров (кнопка 🎵 на панели или клавиша MENU)
        if (showTrackMenu) {
            TrackMenuOverlay(
                player = player,
                onDismiss = { showTrackMenu = false },
                onAudioPicked = { label -> store.setPlayerAudioLabel(label) },
            )
        }

        // Панель «как в сетевых плеерах» (YouTube TV/Zona): внизу сикбар
        // с буфером и временем — никаких кнопок. Управление пультом:
        // OK — пауза, ←/→ — перемотка, ↑ — меню настроек, MENU — дорожки
        if (showPanel || showSettings || showQuality || showTrackMenu) {
            var posMs by remember { mutableStateOf(0L) }
            var durMs by remember { mutableStateOf(0L) }
            var bufFrac by remember { mutableStateOf(0f) }
            LaunchedEffect(panelTick) {
                while (true) {
                    posMs = player.currentPosition
                    durMs = player.duration
                    bufFrac = player.bufferedPercentage / 100f
                    playing = player.isPlaying
                    kotlinx.coroutines.delay(400)
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                // Заголовок над сикбаром
                Text(
                    title,
                    color = Color(0xFFB8C0CC),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                // Сикбар: буфер (тускло) + позиция (жёлтый)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color(0xFF2A3242)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(bufFrac.coerceAtLeast(if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f))
                            .height(5.dp)
                            .background(Color(0xFF4A5872)),
                    )
                    if (durMs > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth((posMs.toFloat() / durMs).coerceIn(0f, 1f))
                                .height(5.dp)
                                .background(Color(0xFFF5B942)),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "%d:%02d".format(posMs / 60000, (posMs / 1000) % 60),
                        color = Color.White, fontSize = 14.sp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "⏳ $bufPct%" + if (bufPct >= 100) "" else " · ← → перемотка · OK пауза · ↑ меню",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (durMs > 0) "-%d:%02d".format((durMs - posMs) / 60000, ((durMs - posMs) / 1000) % 60) else "—",
                        color = Color.White, fontSize = 14.sp,
                    )
                }
                // Ряд кнопок (как в Zona): перемотка, пауза, серии, настройки.
                // Фокус при показе панели — на ▶/⏸, стрелки ходят по ряду
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    @Composable fun Btn(label: String, focusReq: FocusRequester? = null, action: () -> Unit) {
                        androidx.tv.material3.Surface(
                            onClick = action,
                            modifier = if (focusReq != null) Modifier.focusRequester(focusReq) else Modifier,
                            shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF1F2430),
                                contentColor = Color.White,
                                focusedContainerColor = Color(0xFFF5B942),
                                focusedContentColor = Color(0xFF0E1116),
                            ),
                        ) { Text(label, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                    }
                    Btn("⏪") {
                        val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                        player.seekTo((player.currentPosition - 10_000L).coerceIn(0L, dur))
                        seekInfo = "⏪ −10 сек"; panelTick++
                    }
                    Spacer(Modifier.width(10.dp))
                    Btn(if (playing) "⏸" else "▶", focusReq = playBtnFocus) {
                        if (player.isPlaying) player.pause() else player.play()
                        playing = !player.isPlaying
                        seekInfo = if (player.isPlaying) "▶ Продолжаем" else "⏸ Пауза"
                        panelTick++
                    }
                    Spacer(Modifier.width(10.dp))
                    Btn("⏩") {
                        val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                        player.seekTo((player.currentPosition + 10_000L).coerceIn(0L, dur))
                        seekInfo = "⏩ +10 сек"; panelTick++
                    }
                    if (files.size > 1) {
                        Spacer(Modifier.width(10.dp))
                        Btn("☰ Серии (${files.size})") { showFiles = true; panelTick++ }
                    }
                    Spacer(Modifier.weight(1f))
                    Btn("🎵") { showTrackMenu = true; panelTick++ }
                    Spacer(Modifier.width(10.dp))
                    Btn("⤴") { openExternal() }
                    Spacer(Modifier.width(10.dp))
                    Btn("⚙") { showQuality = true; panelTick++ }
                }
            }
        }

        // Меню настроек (↑ на пульте): качество, дорожки, внешний плеер
        if (showSettings) {
            val firstItemFocus = remember { FocusRequester() }
            LaunchedEffect(showSettings) { runCatching { firstItemFocus.requestFocus() } }
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 48.dp)
                    .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Text(
                    "Настройки плеера",
                    color = Color(0xFFF5B942),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                SettingsMenuItem(
                    label = "Качество: ${maxQuality?.let { "${it}p" } ?: "авто"}",
                    focusRequester = firstItemFocus,
                ) {
                    showSettings = false
                    showQuality = true
                }
                Spacer(Modifier.height(6.dp))
                SettingsMenuItem(label = "🎵 Дорожки и субтитры") {
                    showSettings = false
                    showTrackMenu = true
                }
                if (files.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    SettingsMenuItem(label = "☰ Серии (${files.size})") {
                        showSettings = false
                        showFiles = true
                    }
                }
                Spacer(Modifier.height(6.dp))
                SettingsMenuItem(label = "⤴ Внешний плеер") {
                    showSettings = false
                    openExternal()
                }
                Spacer(Modifier.height(6.dp))
                SettingsMenuItem(label = "⏱ Перейти к…") {
                    showSettings = false
                    showJump = true
                }
            }
        }

        // «Перейти к…» — пресеты % поверх видео
        if (showJump) {
            JumpToDialog(
                durationMs = if (player.duration > 0) player.duration else 0L,
                title = title,
                onJump = { ms ->
                    player.seekTo(ms)
                    seekInfo = "⏱ Перемотка"
                    panelTick++
                },
                onClose = { showJump = false },
            )
        }

        // Список серий (TorrServe): выбираем серию, не выходя из плеера
        if (showFiles && files.size > 1) {
            val firstFileFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(showFiles) { runCatching { firstFileFocus.requestFocus() } }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(560.dp)
                    .height(420.dp)
                    .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                items(files.size) { n ->
                    val (idx, name) = files[n]
                    val current = idx == currentFile
                    androidx.tv.material3.Surface(
                        onClick = {
                            showFiles = false
                            if (!current) onSelectFile(idx)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .then(if (n == 0) Modifier.focusRequester(firstFileFocus) else Modifier),
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                            shape = RoundedCornerShape(8.dp),
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

        if (extMsg.isNotBlank() && !showQuality && !showSettings) {
            Text(
                extMsg,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xF0151B26), RoundedCornerShape(12.dp))
                    .padding(16.dp),
            )
        }

        if (showQuality) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Качество видео",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                val options = listOf(null to "Авто (по каналу)", 2160 to "2160p · 4K", 1080 to "1080p", 720 to "720p", 480 to "480p")
                for ((q, label) in options) {
                    androidx.tv.material3.Surface(
                        onClick = { maxQuality = q; showQuality = false },
                        modifier = Modifier
                            .width(220.dp)
                            .padding(vertical = 3.dp)
                            .mouseClick { maxQuality = q; showQuality = false },
                        shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (maxQuality == q) Color(0xFFF5B942) else Color(0xFF1F2430),
                            contentColor = if (maxQuality == q) Color(0xFF0E1116) else Color.White,
                            focusedContainerColor = Color(0xFFFFD07A),
                            focusedContentColor = Color(0xFF0E1116),
                            pressedContainerColor = Color(0xFFE0A82E),
                            pressedContentColor = Color(0xFF0E1116),
                        ),
                    ) {
                        Text(
                            label,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Пункт меню настроек плеера (фокус с пульта, мышь — для отладки). */
@Composable
internal fun SettingsMenuItem(label: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(260.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFF5B942),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
        ),
    ) {
        Text(
            label,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/**
 * Трейлер — играет ВНУТРИ приложения: поиск на Rutube по названию,
 * HLS-стрим в нашем плеере. Внешние приложения/браузер не нужны
 * (на ТВ-приставке их часто нет — кнопка раньше была «мёртвая»).
 */
@Composable
fun TrailerScreen(item: MediaItem, onBack: () -> Unit) {    var link by remember { mutableStateOf<OnlineLink?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(item) {
        val t = withContext(Dispatchers.IO) {
            com.torrenttv.app.data.RutubeService.searchTrailer(item.title + " трейлер")
        }
        link = t?.let { OnlineLink(url = it.hls, label = "rutube") }
        failed = t == null
    }

    val tl = link
    if (tl != null) {
        OnlinePlayerScreen(
            link = tl,
            item = item,
            title = "Трейлер · ${item.title}",
            onBack = onBack,
            // Трейлер не попадает в «Продолжить смотреть»
            saveProgress = false,
        )
        return
    }

    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (failed) {
                Text(
                    "Трейлер на Rutube не найден 🤷",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))
                val backFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
                Surface(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(backFocus),
                    shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1F2430),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFF5B942),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) { Text("← Назад", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) }
            } else {
                Text("🎬 Ищу трейлер на Rutube…", color = Color(0xFFF5B942), fontSize = 18.sp)
            }
        }
    }
}

/**
 * Просмотр полного фильма/серии с Rutube: получаем HLS по id видео
 * и открываем обычный онлайн-плеер. Прогресс сохраняется — попадёт
 * в «Продолжить просмотр».
 */
@Composable
fun RutubeWatchScreen(videoId: String, title: String, onBack: () -> Unit) {
    var link by remember { mutableStateOf<OnlineLink?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(videoId) {
        val hls = withContext(Dispatchers.IO) {
            com.torrenttv.app.data.RutubeService.videoHls(videoId)
        }
        link = hls?.let { OnlineLink(url = it, label = "rutube") }
        failed = hls == null
    }

    val tl = link
    if (tl != null) {
        // Заглушка MediaItem: у Rutube-видео нет карточки Кинопоиска;
        // id детерминированный — прогресс «Продолжить» привяжется к видео
        val pseudoItem = remember(videoId, title) {
            MediaItem(
                id = videoId.hashCode(),
                type = "movie",
                title = title,
            )
        }
        OnlinePlayerScreen(
            link = tl,
            item = pseudoItem,
            title = "Rutube · $title",
            onBack = onBack,
            saveProgress = true,
        )
        return
    }

    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (failed) {
                Text(
                    "Не удалось открыть видео на Rutube 🤷",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))
                val backFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
                Surface(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(backFocus),
                    shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1F2430),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFF5B942),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) { Text("← Назад", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) }
            } else {
                Text("📺 Подключаюсь к Rutube…", color = Color(0xFFF5B942), fontSize = 18.sp)
            }
        }
    }
}
