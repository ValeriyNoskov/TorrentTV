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
import com.torrenttv.app.data.MediaApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextAlign
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
    /** «Другая раздача» — выйти в выбор раздач (позиция сохранится). */
    onPickTorrent: () -> Unit = {},
    /** 🔍 Поиск — уйти во вкладку поиска (из шестерёнки). */
    onSearch: () -> Unit = {},
) {
    // Экран не гаснет, пока играет фильм; в аудио-режиме — может гаснуть
    var audioMode by remember { mutableStateOf(false) }
    KeepScreenOn(enabled = !audioMode)

    val context = LocalContext.current
    // Подпись с серией в заголовках плеера: «Название · S01E08» (или «· Серия 8»)
    // Реальное качество ТЕКУЩЕГО файла (из его имени, не из названия раздачи:
    // релизеры пишут «4K», а файл — 1080p)
    val qualityHint = remember(files, currentFile) {
        files.getOrNull(currentFile)?.let { com.torrenttv.app.data.ReleaseQuality.fromFileName(it.second) } ?: ""
    }
    val titleWithEp = remember(link, qualityHint) {
        title + when {
            link.season > 0 && link.episode > 0 -> "  ·  " + "S%02dE%02d".format(link.season, link.episode)
            link.episode > 0 -> "  ·  Серия ${link.episode}"
            else -> ""
        } + if (qualityHint.isNotBlank()) "  ·  $qualityHint" else ""
    }
    // Каждый вход в плеер — в лог: по выгрузке видно цепочку «нажал → открылся → ошибка»
    androidx.compose.runtime.LaunchedEffect(link.url) {
        com.torrenttv.app.data.AppLog.log(
            "online",
            "открыл плеер: «$title» ${link.label} (сезон=${link.season} серия=${link.episode})",
        )
    }
    val store = remember { com.torrenttv.app.data.LocalStore(context) }
    // Карточка без меты (заглушка из поиска раздач): дотягиваем постер/год/IMDb
    // с КП — иначе в «Продолжить» карточка без постера (жалоба по «Спецам»)
    var itemState by remember(item.storeKey) { mutableStateOf(item) }
    LaunchedEffect(item.storeKey) {
        if (itemState.posterUrl.isBlank() || itemState.imdbId.isBlank()) {
            val en = withContext(Dispatchers.IO) { MediaApi.enrich(itemState, store) }
            if (en.posterUrl.isNotBlank() || en.imdbId.isNotBlank()) itemState = en
        }
    }
    // Ключ источника: своя позиция на каждую ссылку (и серию) онлайн-просмотра
    val onlineSuffix = if (link.season > 0) ":s%02de%02d".format(link.season, link.episode) else ""
    val onlineKey = item.storeKey + ":online" + onlineSuffix
    // Ключи отметки «просмотрено» под конкретную серию: обычный (эта карточка)
    // + нормализованный (один на тайтл — работает и для другой раздачи)
    fun watchedKeys(suffix: String): List<String> = listOf(
        item.storeKey + ":online" + suffix,
        store.normWatchKey(itemState.title, suffix),
    )
    // С какой секунды продолжать («Продолжить просмотр» + запасное хранилище серий)
    val resumeSec = remember {
        store.continueWatching().firstOrNull { it.sourceKey == onlineKey }?.positionSec
            ?: store.episodePosition(onlineKey)
    }
    var showQuality by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // ⚙ Единое меню шестерёнки: серии, перемотка, скорость, дорожки, аудио,
    // качество потока, внешний плеер, другая раздача, поиск
    var showGear by remember { mutableStateOf(false) }
    var showTrackMenu by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    // Список серий (TorrServe): выбор серии прямо в плеере
    var showFiles by remember { mutableStateOf(showFilesOnStart) }
    // Триггер перерисовки галочек «просмотрено» (меняется кнопкой в списке)
    var watchedTick by remember { mutableStateOf(0) }
    // Авто-повтор при ошибке старта потока TorrServe (3003: данные ещё не готовы)
    var retryTick by remember { mutableStateOf(0) }
    // Подряд безуспешные авто-перезапуски: 3+ = раздача не качается,
    // зацикливать не надо — предлагаем сменить качество (🧲)
    var stallRestarts by remember { mutableStateOf(0) }
    var showStalled by remember { mutableStateOf(false) }
    var retryCount by remember { mutableStateOf(0) }
    // Когда была последняя ошибка: счётчик повторов сбрасываем только после
    // 20 секунд стабильного показа, иначе каждый READY обнулял попытки
    var lastErrorAt by remember { mutableStateOf(0L) }
    // Кнопки на панели: игра/пауза (иконка меняется) + фокус на кнопке Play
    var playing by remember { mutableStateOf(true) }
    val playBtnFocus = remember { FocusRequester() }
    var maxQuality by remember { mutableStateOf<Int?>(null) } // null = авто
    var seekInfo by remember { mutableStateOf("") }           // «⏩︎ +10 сек» при перемотке пультом
    // «Взведён» выход: первый BACK показывает «ещё раз — выход», второй за 3 сек — закрывает
    var backArmedAt by remember { mutableStateOf(0L) }
    // ---------- Состояние: буферизация и ошибки (чёрный экран ≠ «зависло») ----------
    var playState by remember { mutableStateOf(Player.STATE_IDLE) }
    // Авто-следующая серия: серия закончилась — отсчёт и переход (как в движковом)
    var nextCountdown by remember { mutableStateOf<Int?>(null) }
    // Скорость воспроизведения: подпись кнопки панели (значение — speeds[speedIdx])
    var speedIdx by remember { mutableStateOf(0) }
    var speedLabel by remember { mutableStateOf("1×") }
    // Скорость нужно переустанавливать после пересоздания плеера — перенесено
    // ниже, после объявления player (см. LaunchedEffect(player, retryTick))
    LaunchedEffect(playState) {
        if (playState == Player.STATE_ENDED && files.size > 1 &&
            currentFile >= 0 && currentFile + 1 < files.size
        ) {
            nextCountdown = 8
            com.torrenttv.app.data.AppLog.log("player", "серия закончилась — предлагаю следующую (${files[currentFile + 1].second.takeLast(40)})")
        }
    }
    LaunchedEffect(nextCountdown) {
        var n = nextCountdown ?: return@LaunchedEffect
        while (n > 0) {
            kotlinx.coroutines.delay(1000)
            n--
            nextCountdown = n
        }
        // Время вышло — переходим на следующую серию
        if (nextCountdown == 0 && currentFile + 1 < files.size) {
            com.torrenttv.app.data.AppLog.log("player", "автопереход на следующую серию")
            nextCountdown = null
            onSelectFile(currentFile + 1)
        }
    }
    var errorMsg by remember { mutableStateOf("") }
    var bufPct by remember { mutableIntStateOf(0) }
    // Панель контролов при действии пульта (счётчик действий — триггер показа).
    // Видна сразу при входе — пользователь видит, что плеер жив.
    // Время автоскрытия — настройка в ⚙ (дефолт 20 сек, 0 = не прятать)
    var panelTick by remember { mutableStateOf(1) }
    var showPanel by remember { mutableStateOf(true) }
    var panelHideSec by remember { mutableStateOf(store.panelHideSec()) }
    LaunchedEffect(panelTick, panelHideSec) {
        if (panelTick > 0 && !showSettings && !showQuality && !showTrackMenu && !showGear) {
            showPanel = true
            if (panelHideSec > 0) {
                kotlinx.coroutines.delay(panelHideSec * 1000L)
                if (!showSettings && !showQuality && !showTrackMenu && !showGear) showPanel = false
            }
        }
    }
    LaunchedEffect(showPanel) {
        if (showPanel) {
            // Задержка: PlayerView (AndroidView) запрашивает native-фокус в
            // factory и перебивает фокус-запрос — ставим ▶︎ после него
            kotlinx.coroutines.delay(200)
            runCatching { playBtnFocus.requestFocus() }
        }
    }

    val player = remember(link.url, retryTick) {
        // CDN проверяет Referer: у Filmix — filmix.ac, у HDRezka — её зеркало
        val referer = link.referer.ifBlank { "https://filmix.ac/" }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .apply { setDefaultRequestProperties(mapOf("Referer" to referer)) }
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        // media3 1.11 включила «детектор зависаний»: если позиция не двигается
        // 10 секунд, поток рвётся ошибкой ERROR_CODE_TIMEOUT, хотя он живой —
        // торрент-стрим просто медленно наливает буфер на старте («Полдень»:
        // картинка на паузе, перемотка работает, через 10 с ошибка).
        // Для торрент-потоков этот детектор отключаем: ждём буферизацию сами.
        runCatching {
            androidx.media3.exoplayer.ExoPlayer.Builder.experimentalEnableStuckPlayingDetection = false
        }
        // Рендереры: системные MediaCodec + резервный FFmpeg-декодер
        // (все известные аудиокодеки: AC3/EAC3/DTS/TrueHD/AAC/FLAC/Opus…).
        // FFmpeg включается только там, где ТВ не тянет дорожку системно.
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory))
            // Буфер больше: торрент-поток неровный, лучше подождать, чем рвать
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(120_000, 300_000, 20_000, 45_000)
                    .setBackBuffer(60_000, true)
                    .build()
            )
            .build()
            .apply {
                // Скорость выбранная в панели — переживает пересоздание плеера
                setPlaybackSpeed(listOf(1f, 1.25f, 1.5f, 2f, 0.75f)[speedIdx])
                // Русский звук по умолчанию (дубляж), если дорожки размечены
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage("rus")
                    .build()
                // Режим «Всегда внешний плеер»: наш ExoPlayer НЕ запускаем —
                // он не качает и не конкурирует с внешним плеером за поток.
                // Видео играет внешний (VLC/MX Player), наш экран только ждёт
                // возврата, чтобы вернуться в приложение без системных меню.
                val extMode = store.useExternalPlayer()
                if (!extMode) {
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
                        seekInfo = "▶︎ Продолжаем с %d:%02d".format(resumeSec / 60, resumeSec % 60)
                    }
                    playWhenReady = true
                }
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        playState = state
                        // Стрим реально запустился — сбрасываем счётчик повторов,
                        // но только если ошибка была давно (поток успел пожить)
                        if (state == Player.STATE_READY &&
                            System.currentTimeMillis() - lastErrorAt > 20_000
                        ) retryCount = 0
                        // Диагностика: какие дорожки выбрал плеер (аудио/видео).
                        // Нужна, если поток стоит на паузе: частая причина —
                        // аудио E-AC3/DTS, которое телевизор не тянет.
                        if (state == Player.STATE_READY) {
                            runCatching {
                                val kinds = mapOf(
                                    androidx.media3.common.C.TRACK_TYPE_VIDEO to "видео",
                                    androidx.media3.common.C.TRACK_TYPE_AUDIO to "аудио",
                                    androidx.media3.common.C.TRACK_TYPE_TEXT to "субтитры",
                                )
                                val info = currentTracks.groups.joinToString(", ") { g ->
                                    val f = g.getTrackFormat(0)
                                    "${kinds[g.type] ?: g.type}:${f.sampleMimeType ?: "?"}/${f.language ?: "-"}"
                                }
                                com.torrenttv.app.data.AppLog.log("player", "дорожки: $info")
                            }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        com.torrenttv.app.data.AppLog.log(
                            "player",
                            "playback error: ${error.errorCodeName} code=${error.errorCode} retry=$retryCount " +
                                "причина=${error.cause?.javaClass?.simpleName}: ${error.cause?.message?.take(160)} " +
                                "link=${link.url.take(120)}",
                        )
                        // Живой поток иногда не успевает отдать начало —
                        // молча перезапускаем плеер, без алармов.
                        // Пауза растёт: медленная раздача (4 сида, ~0.7 МБ/с) за 1.5 с
                        // не успевает отдать даже начало файла — раньше 3 попытки
                        // заканчивались ошибкой, хотя файл был жив
                        lastErrorAt = System.currentTimeMillis()
                        // 6 попыток: у медленных раздач старт дольше
                        if (retryCount < 6) {
                            retryCount++
                            seekInfo = "🔄 Запускаю поток (попытка ${retryCount + 1})…"
                            panelTick++
                            // После IO-ошибки (2000) источник ещё докачивает данные —
                            // мгновенный рестарт попадает в то же состояние
                            val waitMs = (4000L * retryCount).coerceAtMost(20_000L) // 4…20 с
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                kotlinx.coroutines.delay(waitMs)
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
                // и помечаем серию просмотренной (галочка в списках) под
                // обоими ключами: обычным (эта карточка) и нормализованным
                // (одинаковым для любых раздач того же тайтла)
                if (pos >= dur * 95 / 100) {
                    store.removeProgress(itemState)
                    store.markWatchedKeys(watchedKeys(onlineSuffix))
                } else store.saveProgress(itemState, pos, dur, onlineKey)
            }
        }
    }
    DisposableEffect(player) {
        onDispose {
            val pos = (player.currentPosition / 1000).toInt()
            val dur = (player.duration / 1000).toInt()
            if (saveProgress && pos > 10 && dur > 0) {
                if (pos >= dur * 95 / 100) store.markWatchedKeys(watchedKeys(onlineSuffix))
                else store.saveProgress(itemState, pos, dur, onlineKey)
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

    // Запомненные аудио и субтитры: применяем один раз при появлении дорожек
    DisposableEffect(player) {
        var audioApplied = false
        var subsApplied = false
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // --- Аудио: подбор по запомненной подписи ---
                if (!audioApplied) {
                    val want = store.playerAudioLabel()
                    if (want.isNotBlank()) {
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
                                    break
                                }
                            }
                            if (audioApplied) return
                        }
                    }
                }
                // --- Субтитры: включить запомненные / выключить («off») ---
                if (!subsApplied) {
                    val wantSub = store.playerSubLabel()
                    if (wantSub.isBlank()) return
                    subsApplied = true
                    if (wantSub == "off") {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                            .build()
                        return
                    }
                    for (g in 0 until tracks.groups.size) {
                        val group = tracks.groups[g]
                        if (group.type != androidx.media3.common.C.TRACK_TYPE_TEXT) continue
                        for (i in 0 until group.length) {
                            val fmt = group.getTrackFormat(i)
                            val label = (fmt.label ?: "") + " " + (fmt.language ?: "")
                            if (label.lowercase().contains(wantSub.lowercase())) {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                                    .addOverride(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                    .build()
                                return
                            }
                        }
                    }
                    subsApplied = false // запомненного трека в этой серии нет — не блокируем будущие
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Детектор зависания стрима: играет, но позиция И буфер стоят 30+ сек —
    // тихо перезапускаем (раньше — только руками)

    LaunchedEffect(player, retryTick) {
        var stuck = 0
        var idleBuf = 0
        var lastPos = -1L
        var lastBuf = -1L
        while (true) {
            kotlinx.coroutines.delay(5000)
            if (!player.isPlaying) {
                // Плеер «на паузе» сам по себе — это ещё не зависание, но если
                // он в BUFFERING и буфер не растёт 30 с, поток реально встал.
                // Пишем это в лог: причина видна без догадок (аудио/сеть/диск).
                val pos = player.currentPosition
                val buf = player.bufferedPosition
                if (player.playbackState == Player.STATE_BUFFERING && pos == lastPos && buf == lastBuf) {
                    idleBuf++
                    if (idleBuf == 6) {
                        com.torrenttv.app.data.AppLog.log(
                            "player",
                            "буферизация 30 с без данных: pos=${pos / 1000}s buf=${buf / 1024}КБ " +
                                "длительность=${player.duration / 1000}s",
                        )
                    }
                } else {
                    idleBuf = 0
                    lastPos = pos
                    lastBuf = buf
                }
                stuck = 0
                continue
            }
            val pos = player.currentPosition
            val buf = player.bufferedPosition
            if (pos == lastPos && buf == lastBuf) {
                stuck++
                if (stuck >= 6) { // 6 × 5 сек = 30 секунд без движения
                    stallRestarts++
                    if (stallRestarts >= 3) {
                        // Раздача не качается: не зацикливаемся, зовём сменить качество
                        com.torrenttv.app.data.AppLog.log(
                            "player",
                            "стрим завис 3 раза подряд (pos=$pos) — предлагаю сменить раздачу",
                        )
                        showStalled = true
                        break
                    }
                    com.torrenttv.app.data.AppLog.log(
                        "player",
                        "стрим завис (30 сек без движения, pos=$pos) — авто-перезапуск $stallRestarts/3",
                    )
                    seekInfo = "🔄 Стрим завис — перезапускаю…"
                    panelTick++
                    retryTick++ // пересоздаёт плеер; позиция подтянется из «Продолжить»
                    break
                }
            } else {
                if (stuck > 0 && pos > lastPos) stallRestarts = 0 // снова играет — счётчик в ноль
                stuck = 0
                lastPos = pos
                lastBuf = buf
            }
        }
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
        // (как в Zona), глобальные действия пульта уступают.
        // ↑ настройки НЕ открывает — только шестерёнка ⚙ (жалоба владельца)
        if (showPanel) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return false
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                val target = (player.currentPosition + delta).coerceIn(0L, dur)
                com.torrenttv.app.data.AppLog.log(
                    "player", "перемотка ${if (delta > 0) "+" else "−"}10 с → ${target / 1000} с",
                )
                player.seekTo(target)
                seekInfo = if (delta > 0) "⏩︎ +10 сек" else "⏪︎ −10 сек"
                panelTick++
                true
            }
            // ↑/↓ вне панели — показываем панель (без настроек)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { panelTick++; true }
            // OK — пауза/продолжить (как в YouTube TV)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player.isPlaying) {
                    com.torrenttv.app.data.AppLog.log("player", "пауза на ${player.currentPosition / 1000} с")
                    player.pause()
                    seekInfo = "▮▮ Пауза"
                } else {
                    com.torrenttv.app.data.AppLog.log("player", "продолжение с ${player.currentPosition / 1000} с")
                    player.play()
                    seekInfo = "▶︎ Продолжаем"
                }
                panelTick++; true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                com.torrenttv.app.data.AppLog.log("player", "play (кнопка пульта)")
                player.play(); panelTick++; true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                com.torrenttv.app.data.AppLog.log("player", "pause (кнопка пульта)")
                player.pause(); panelTick++; true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val t = player.currentPosition + 30_000L
                com.torrenttv.app.data.AppLog.log("player", "перемотка +30 с → ${t / 1000} с")
                player.seekTo(t); seekInfo = "⏩︎ +30 сек"; panelTick++; true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val t = (player.currentPosition - 30_000L).coerceAtLeast(0)
                com.torrenttv.app.data.AppLog.log("player", "перемотка −30 с → ${t / 1000} с")
                player.seekTo(t); seekInfo = "⏪︎ −30 сек"; panelTick++; true
            }
            KeyEvent.KEYCODE_MENU -> { showTrackMenu = !showTrackMenu; true }
            // BACK: панель скрыта — СНАЧАЛА показываем панель (защита от
            // случайного нажатия: выход только вторым BACK при видимой панели).
            // Открытые оверлеи отдают false — их закрывают BackHandler'ы ниже
            KeyEvent.KEYCODE_BACK -> {
                if (!showPanel && !showSettings && !showQuality && !showTrackMenu && !showFiles && !showJump && !showGear) {
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
    DisposableEffect(player) {
        MainActivity.remoteKeyHandler = { keyCode -> handleRemoteKey(keyCode) }
        onDispose { MainActivity.remoteKeyHandler = null }
    }

    // Выход из плеера: сначала ПАУЗА — иначе после возврата в меню звук
    // продолжал играть (жалоба «плеер работает в фоне»)
    fun exitPlayer() {
        runCatching { player.pause() }
        com.torrenttv.app.data.AppLog.log("player", "выход: пауза, возвращаюсь в меню")
        onBack()
    }

    // BACK-иерархия: в Compose активен ПОСЛЕДНИЙ зарегистрированный enabled —
    // поэтому базовый выход первым, оверлеи — после (перекрывают его).
    // Выход из плеера — только двойным BACK (3 сек): первый показ панели /
    // подсказку, второй — выход (жалоба: «назад сразу закрывает плеер»)
    // BACK при видимой панели: если панель только что показал BACK (armed) —
    // второй BACK выходит; если панель открылась сама (действие пульта) —
    // ОДИН BACK её прячет, без «ещё раз — выход» (жалоба владельца)
    BackHandler(enabled = showPanel && !showSettings && !showQuality && !showTrackMenu && !showFiles && !showJump && !showGear) {
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
    BackHandler(enabled = showTrackMenu) { showTrackMenu = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showQuality) { showQuality = false }
    BackHandler(enabled = showGear) { showGear = false }

    // Галочка «Всегда внешний плеер»: сразу открываем во внешнем плеере и выходим
    val storePrefs = remember { com.torrenttv.app.data.LocalStore(context) }
    var extMsg by remember { mutableStateOf("") }
    var extOpened by remember { mutableStateOf(false) }
    // Список установленных плееров для разового выбора; после выбора пакет
    // запоминается — дальше плеер открывается СРАЗУ, без системного меню
    // «Открыть с помощью» (оно выскакивало при каждом запуске и после закрытия).
    var showPlayerPick by remember { mutableStateOf(false) }
    var playerApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    fun buildIntent(pkg: String?): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(
                android.net.Uri.parse(link.url),
                if (link.url.contains(".m3u8", true)) "application/x-mpegURL" else "video/*",
            )
            if (pkg != null) setPackage(pkg)
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
    fun launchIn(pkg: String, auto: Boolean): Boolean {
        val ok = runCatching { context.startActivity(buildIntent(pkg)) }.isSuccess
        if (ok && auto) exitPlayer()
        return ok
    }
    fun openExternal(auto: Boolean = false) {
        val saved = storePrefs.externalPlayerPackage()
        if (saved.isNotBlank()) {
            // Плеер уже выбран владельцем — открываем сразу, бесшовно
            if (!launchIn(saved, auto)) {
                storePrefs.setExternalPlayerPackage("") // плеер удалён — спросим заново
                extMsg = "Выбранный плеер не найден — выберите другой"
                showPlayerPick = true
                playerApps = runCatching {
                    context.packageManager.queryIntentActivities(buildIntent(null), 0)
                        .map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
                        .sortedBy { it.first.lowercase() }
                }.getOrDefault(emptyList())
            }
            return
        }
        val apps = runCatching {
            context.packageManager.queryIntentActivities(buildIntent(null), 0)
                .map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
                .sortedBy { it.first.lowercase() }
        }.getOrDefault(emptyList())
        when {
            apps.isEmpty() -> {
                extMsg = "Внешний плеер не найден — установите VLC или MX Player, либо выключите «Всегда внешний плеер» в настройках"
            }
            apps.size == 1 -> {
                storePrefs.setExternalPlayerPackage(apps.first().second)
                launchIn(apps.first().second, auto)
            }
            else -> {
                playerApps = apps
                showPlayerPick = true
            }
        }
    }
    LaunchedEffect(Unit) {
        if (storePrefs.useExternalPlayer()) {
            extOpened = true
            openExternal(auto = false)
        }
    }
    // Бесшовный возврат: закрыли VLC/MX Player → оказались прямо в приложении
    // (на карточке фильма), без меню внешнего плеера и без чёрного экрана.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && extOpened) {
                extOpened = false
                exitPlayer()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (storePrefs.useExternalPlayer()) {
            // Режим внешнего плеера: наш плеер не играет. Пока открыт VLC —
            // этот экран не виден; после закрытия сразу уходим в приложение.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(40.dp),
            ) {
                Text("🎬 Играет во внешнем плеере", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Закройте плеер кнопкой Назад на пульте — вернётесь в приложение",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
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

        // 🎧 Аудио-режим: видео не рисуем, звук играет, экран ТВ может гаснуть
        if (audioMode) {
            val audioFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); runCatching { audioFocus.requestFocus() } }
            androidx.tv.material3.Surface(
                onClick = { audioMode = false; seekInfo = "🎬 Видео включено"; panelTick++ },
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(audioFocus),
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
                    Text(titleWithEp, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 60.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Экран ТВ может гаснуть — звук продолжится\nOK — вернуть видео",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 16.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(26.dp))
                    Text("↩ Показать видео", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Color(0x99151B26), RoundedCornerShape(10.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp))
                }
            }
        }

        // Центр: полупрозрачные ⏪︎/▮▮/⏩︎ при показе панели (как в PlayerScreen)
        if (showPanel && !audioMode && nextCountdown == null) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                @Composable fun CenterBtn(label: String, action: () -> Unit) {
                    androidx.tv.material3.Surface(
                        onClick = action,
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0x99151B26),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text(label, fontSize = 30.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 15.dp)) }
                }
                CenterBtn("⏪︎") {
                    val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                    player.seekTo((player.currentPosition - 10_000L).coerceIn(0L, dur))
                    seekInfo = "⏪︎ −10 сек"; panelTick++
                }
                androidx.tv.material3.Surface(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                        playing = !player.isPlaying
                        panelTick++
                    },
                    modifier = Modifier.focusRequester(playBtnFocus),
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x99151B26),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color.Black,
                    ),
                ) {
                    Text(
                        if (playing) "▮▮" else "▶︎",
                        fontSize = 34.sp,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                }
                CenterBtn("⏩︎") {
                    val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 10_000L).coerceIn(0L, dur))
                    seekInfo = "⏩︎ +10 сек"; panelTick++
                }
            }
        }
        // Название и серия — без технических пометок источника («torrserve»,
        // «filmix» и т.п.). Видно только при открытой панели: владелец просил,
        // чтобы заголовок уходил вместе с меню.
        if (showPanel) {
            Text(
                titleWithEp,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )
        }

        // Индикатор перемотки пультом: скрыт при открытых меню (не наезжает)
        if (seekInfo.isNotBlank() && !showQuality && !showSettings) {
            LaunchedEffect(seekInfo) {
                kotlinx.coroutines.delay(900)
                seekInfo = ""
            }
            Text(
                seekInfo,
                color = Color(0xFFE8ECF4),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE0151B26), RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        // ⏳ Буферизация: спиннер + прогресс-бар + подсказки, как на макете.
        // Чёрный экран — значит «грузим», а не «зависло».
        if (playState == Player.STATE_BUFFERING && errorMsg.isBlank()) {
            // Вращающийся спиннер: дуга бежит по кругу (Compose-анимация,
            // без material-зависимостей)
            val spin = rememberInfiniteTransition(label = "spinner")
            val angle by spin.animateFloat(
                0f, 360f,
                infiniteRepeatable(tween(1100, easing = LinearEasing)),
                label = "angle",
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 720.dp),
            ) {
                // Круг с бегущей дугой
                Box(Modifier.size(84.dp)) {
                    Canvas(Modifier.fillMaxSize().rotate(angle)) {
                        val stroke = 7.dp.toPx()
                        val inset = stroke / 2
                        drawArc(
                            color = Color(0xFF2A3240),
                            startAngle = 0f, sweepAngle = 360f, useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                        )
                        drawArc(
                            color = Color(0xFFE8ECF4),
                            startAngle = -60f, sweepAngle = 110f, useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                        )
                    }
                }
                Text(
                    "Загружаю видео…",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 22.dp),
                )
                Text(
                    titleWithEp,
                    color = Color(0xFFE8ECF4),
                    fontSize = 17.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // Прогресс-бар буфера: жёлтая полоса на тёмном фоне
                Box(
                    Modifier
                        .padding(top = 18.dp)
                        .width(420.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF2A3240)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((bufPct / 100f).coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFE8ECF4)),
                    )
                }
                Text(
                    "Буферизация $bufPct%",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (bufPct < 25) {
                    Text(
                        "Поток качает сервер — первые секунды могут занять минуту",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
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
                            focusedContainerColor = Color(0xFFE8ECF4),
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
                            focusedContainerColor = Color(0xFFFFFFFF),
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
                onSubPicked = { label -> store.setPlayerSubLabel(label) },
            )
        }

        // Панель «как в сетевых плеерах» (YouTube TV/Zona): внизу сикбар
        // с буфером и временем — никаких кнопок. Управление пультом:
        // OK — пауза, ←/→ — перемотка, ↑ — меню настроек, MENU — дорожки
        if (showPanel || showSettings || showQuality || showTrackMenu) {
            var posMs by remember { mutableStateOf(0L) }
            var durMs by remember { mutableStateOf(0L) }
            var bufFrac by remember { mutableStateOf(0f) }
            // «Пропустить интро» — только в первые 90 сек (ROADMAP №6)
            LaunchedEffect(panelTick) {
                while (true) {
                    posMs = player.currentPosition
                    durMs = player.duration
                    bufFrac = player.bufferedPercentage / 100f
                    playing = player.isPlaying
                    kotlinx.coroutines.delay(400)
                }
            }
            if (posMs in 15_000L..90_000L) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 32.dp, bottom = 110.dp),
                ) {
                    androidx.tv.material3.Surface(
                        onClick = {
                            player.seekTo(90_000L)
                            seekInfo = "⏭︎ Интро пропущено"
                            panelTick++
                        },
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xCC1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                    ) { Text("⏭︎ Пропустить интро", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                // Заголовок над сикбаром: название + серия (S01E08)
                Text(
                    titleWithEp,
                    color = Color(0xFFB8C0CC),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                // Прокрутка по линии времени: фокус на полосе → ←/→ листают
                // позицию по 1 минуте, OK — прыжок. Раньше таймлайн был
                // только индикатором без прокрутки
                var jumpTarget by remember { mutableStateOf<Long?>(null) }
                // MX-стиль: шаг растёт при удержании стрелки
                // (10с → 30с → 1мин → 5мин), отпускание сбрасывает
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
                        .height(26.dp)
                        .focusable()
                        .onKeyEvent { e: androidx.compose.ui.input.key.KeyEvent ->
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
                    shape = clickableSurfaceShape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color(0x33FFFFFF),
                        focusedContentColor = Color.White,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        val shownMs = jumpTarget ?: posMs
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(Color(0xFF2A3242)),
                        ) {
                            if (durMs > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth((shownMs.toFloat() / durMs).coerceIn(0f, 1f))
                                        .height(5.dp)
                                        .background(if (jumpTarget != null) Color(0xFFFFFFFF) else Color(0xFFE8ECF4)),
                                )
                            }
                        }
                        if (jumpTarget != null) {
                            Text(
                                (if (shownMs >= 3_600_000L)
                                    "⏱ %d:%02d:%02d — OK чтобы перейти".format(shownMs / 3600000, (shownMs / 60000) % 60, (shownMs / 1000) % 60)
                                else
                                    "⏱ %d:%02d — OK чтобы перейти".format(shownMs / 60000, (shownMs / 1000) % 60)),
                                color = Color(0xFFFFFFFF),
                                fontSize = 12.sp,
                            )
                        }
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
                        "⏳ $bufPct%" + if (bufPct >= 100) "" else " · полоса: ← → перемотка · OK переход",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (durMs > 0) "-%d:%02d".format((durMs - posMs) / 60000, ((durMs - posMs) / 1000) % 60) else "—",
                        color = Color.White, fontSize = 14.sp,
                    )
                }
                // Панель минимальная (владелец): только ⚙ — все действия в ней
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    androidx.tv.material3.Surface(
                        onClick = { showGear = !showGear; panelTick++ },
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                    ) { Text(if (showGear) "✕" else "⚙", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
                }
            }
        }

        // ⚙ Меню шестерёнки: все действия плеера в одном месте (стиль PlayerScreen)
        if (showGear) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 120.dp)
                    .background(Color(0xF2151B26), RoundedCornerShape(14.dp))
                    .padding(10.dp),
            ) {
                @Composable fun GItem(label: String, action: () -> Unit) {
                    androidx.tv.material3.Surface(
                        onClick = { showGear = false; action() },
                        modifier = Modifier
                            .width(280.dp)
                            .padding(vertical = 2.dp),
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text(label, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                }
                if (files.size > 1) GItem("☰ Серии (${files.size})") { showFiles = true; panelTick++ }
                GItem("⏱ Перейти к…") { showJump = true; panelTick++ }
                GItem(speedLabel + " Скорость") {
                    val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
                    speedIdx = (speedIdx + 1) % speeds.size
                    player.setPlaybackSpeed(speeds[speedIdx])
                    speedLabel = if (speeds[speedIdx] == 1f) "1×" else "${speeds[speedIdx]}×"
                    com.torrenttv.app.data.AppLog.log("player", "скорость → ${speeds[speedIdx]}×")
                    seekInfo = "Скорость ${speeds[speedIdx]}×"; panelTick++
                }
                GItem("🎵 Дорожки") { showTrackMenu = true; panelTick++ }
                GItem("📶 Качество потока") { showQuality = true; panelTick++ }
                GItem("🎧 Аудио-режим") { audioMode = true; seekInfo = "🎧 Аудио-режим"; panelTick++ }
                GItem("⤴ Внешний плеер") { openExternal() }
                GItem("🧲 Другая раздача") {
                    com.torrenttv.app.data.AppLog.log("player", "нажал: «Другая раздача» в онлайн-плеере")
                    onPickTorrent()
                }
                GItem("🔍 Поиск") {
                    runCatching { player.pause() }
                    onSearch()
                }
            }
        }

        // «Следующая серия через N…» — оверлей с выбором (пункт ROADMAP №4)
        if (nextCountdown != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 32.dp, bottom = 60.dp)
                    .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Серия закончилась",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Следующая через $nextCountdown сек",
                        color = Color(0xFFE8ECF4),
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        androidx.tv.material3.Surface(
                            onClick = { nextCountdown = null; if (currentFile + 1 < files.size) onSelectFile(currentFile + 1) },
                            shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF2E4A22),
                                contentColor = Color.White,
                                focusedContainerColor = Color(0xFF7FB84F),
                                focusedContentColor = Color(0xFF0E1116),
                            ),
                        ) { Text("▶︎ Следующая", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
                        Spacer(Modifier.width(8.dp))
                        androidx.tv.material3.Surface(
                            onClick = { nextCountdown = null },
                            shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF1F2430),
                                contentColor = Color.White,
                                focusedContainerColor = Color(0xFFE8ECF4),
                                focusedContentColor = Color(0xFF0E1116),
                            ),
                        ) { Text("✕ Отмена", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
                    }
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
            // Автоскролл к текущей серии: на сериале с 50 файлами фокус
            // стартует с E01 — листать 39 позиций с пульта мучение
            val filesListState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(showFiles) {
                val cur = files.indexOfFirst { it.first == currentFile }
                if (cur > 0) runCatching { filesListState.scrollToItem(cur) }
            }
            androidx.compose.foundation.lazy.LazyColumn(
                state = filesListState,
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(560.dp)
                    .height(420.dp)
                    .background(Color(0xF0151B26), RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                // Ключ просмотренности по файлу: тот же формат, что onlineKey
                fun watchedKeyOf(fileIdx: Int): String? {
                    val path = files.getOrNull(fileIdx)?.second ?: return null
                    val ep = Regex("[Ee](\\d{1,3})").find(path.substringAfterLast('/'))
                        ?.groupValues?.get(1)?.toIntOrNull() ?: return null
                    return if (link.season > 0)
                        ":s%02de%02d".format(link.season, ep)
                    else ""
                }
                items(files.size) { n ->
                    val (idx, name) = files[n]
                    val current = idx == currentFile
                    // Галочка: серия отмечена просмотренной (кнопкой или досмотрена) —
                    // смотрим оба ключа (эта карточка + нормализованный на тайтл)
                    @Suppress("UNUSED_EXPRESSION") watchedTick
                    val watched = store.isWatchedAny(watchedKeys(watchedKeyOf(idx) ?: ""))
                    androidx.tv.material3.Surface(
                        onClick = {
                            showFiles = false
                            if (!current) {
                                com.torrenttv.app.data.AppLog.log(
                                    "player", "выбрана серия: файл #$idx «${name.substringAfterLast('/')}»",
                                )
                                onSelectFile(idx)
                            }
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
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) {
                        androidx.tv.material3.Text(
                            (if (watched) "✓ " else "") + (if (current) "▶︎ " else "") +
                                episodeBadgeOf(name) + name.substringAfterLast('/'),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                // Ручная отметка: кнопка — текущая серия просмотрена / снять отметку
                item {
                    val curSuffix = watchedKeyOf(currentFile)
                    if (curSuffix != null) {
                        @Suppress("UNUSED_EXPRESSION") watchedTick
                        val watched = store.isWatchedAny(watchedKeys(curSuffix))
                        androidx.tv.material3.Surface(
                            onClick = {
                                store.markWatchedKeys(watchedKeys(curSuffix), !watched)
                                com.torrenttv.app.data.AppLog.log(
                                    "player",
                                    if (!watched) "серия отмечена просмотренной" else "отметка просмотренной снята",
                                )
                                watchedTick++
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                                shape = RoundedCornerShape(8.dp),
                            ),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = if (watched) Color(0xFF3A5A2E) else Color(0xFF1F2430),
                                contentColor = Color.White,
                                focusedContainerColor = Color(0xFF3A5A2E),
                                focusedContentColor = Color.White,
                            ),
                        ) {
                            androidx.tv.material3.Text(
                                if (watched) "✓ Серия просмотрена (нажмите — снять)" else "✓ Отметить серию просмотренной",
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }

        // Раздача не качается: плашка с быстрым выходом к выбору раздачи
        if (showStalled) {
            val stalledFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); runCatching { stalledFocus.requestFocus() } }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xF0151B26), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("📡 Стрим не загружается", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Раздача не отдаёт данные — попробуйте другую",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.tv.material3.Surface(
                        onClick = { showStalled = false; onPickTorrent() },
                        modifier = Modifier.focusRequester(stalledFocus),
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0x99151B26),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color.Black,
                        ),
                    ) { Text("🧲 Другая раздача", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                    androidx.tv.material3.Surface(
                        onClick = { showStalled = false; retryCount = 0; stallRestarts = 0; retryTick++ },
                        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1F2430),
                            contentColor = Color.White,
                            focusedContainerColor = Color(0xFFE8ECF4),
                            focusedContentColor = Color(0xFF0E1116),
                        ),
                    ) { Text("↻ Ещё раз", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
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
                        onClick = {
                            com.torrenttv.app.data.AppLog.log("player", "качество потока → $label")
                            maxQuality = q; showQuality = false
                        },
                        modifier = Modifier
                            .width(220.dp)
                            .padding(vertical = 3.dp)
                            .mouseClick { maxQuality = q; showQuality = false },
                        shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (maxQuality == q) Color(0xFFE8ECF4) else Color(0xFF1F2430),
                            contentColor = if (maxQuality == q) Color(0xFF0E1116) else Color.White,
                            focusedContainerColor = Color(0xFFFFFFFF),
                            focusedContentColor = Color(0xFF0E1116),
                            pressedContainerColor = Color(0xFFD5DAE4), // без жёлтого (v0.9.4)
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "Скрытие панели",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                val hideOptions = listOf(10 to "10 сек", 20 to "20 сек", 30 to "30 сек", 60 to "1 мин", 0 to "Не прятать")
                for ((sec, label) in hideOptions) {
                    androidx.tv.material3.Surface(
                        onClick = { panelHideSec = sec; store.setPanelHideSec(sec); panelTick++ },
                        modifier = Modifier
                            .width(220.dp)
                            .padding(vertical = 3.dp)
                            .mouseClick { panelHideSec = sec; store.setPanelHideSec(sec); panelTick++ },
                        shape = clickableSurfaceShape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (panelHideSec == sec) Color(0xFFE8ECF4) else Color(0xFF1F2430),
                            contentColor = if (panelHideSec == sec) Color(0xFF0E1116) else Color.White,
                            focusedContainerColor = Color(0xFFFFFFFF),
                            focusedContentColor = Color(0xFF0E1116),
                            pressedContainerColor = Color(0xFFD5DAE4), // без жёлтого (v0.9.4)
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
            focusedContainerColor = Color(0xFFE8ECF4),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFFD5DAE4), // без жёлтого (v0.9.4)
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
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) { Text("← Назад", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) }
            } else {
                Text("🎬 Ищу трейлер на Rutube…", color = Color(0xFFE8ECF4), fontSize = 18.sp)
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
                        focusedContainerColor = Color(0xFFE8ECF4),
                        focusedContentColor = Color(0xFF0E1116),
                    ),
                ) { Text("← Назад", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) }
            } else {
                Text("📺 Подключаюсь к Rutube…", color = Color(0xFFE8ECF4), fontSize = 18.sp)
            }
        }
    }
}


/**
 * Номер серии из имени файла: «Specy.S01.E05.2026…» → «S01E05 · ».
 * Без него в списке серий были только длинные имена файлов — не понять,
 * какая серия сейчас играет.
 */
private fun episodeBadgeOf(name: String): String {
    val file = name.substringAfterLast('/')
    Regex("""[Ss](\d{1,2})[.\s_-]?[Ee](\d{1,3})""").find(file)?.let {
        return "S%02dE%02d · ".format(it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    Regex("""(?:^|[.\s_-])[Ee](\d{1,3})(?:[.\s_-]|$)""").find(file)?.let {
        return "E%02d · ".format(it.groupValues[1].toInt())
    }
    Regex("""(\d{1,2})(?:-?[Ss]eason)?[.\s_-]+(\d{1,3})[.\s_-]""").find(file)?.let {
        return "S%02dE%02d · ".format(it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    return ""
}
