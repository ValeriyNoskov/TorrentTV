@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.data.Genre
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaApi
import com.torrenttv.app.data.PrivateTrackers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Настройки — по образцу лучших TV-приложений (OTT Navigator, Kodi):
 *  слева — разделы, справа — строки параметров (название · значение · описание).
 * ОК на строке — действие: редактирование открывается отдельным полноэкранным
 * шагом с клавиатурой и кнопкой «Сохранить».
 */
private const val COUNTRIES_PAGE = 12

/** Разделы настроек: id, заголовок, краткое описание. */
private val SECTIONS = listOf(
    Triple("update", "⬆ Обновление", "Проверить и установить новую версию"),
    Triple("search", "🔍 Поиск и фильтры", "Что скрывать из каталогов и поиска"),
    Triple("tv", "📡 ТВ-каналы", "Свой плейлист добавляется к встроенному списку"),
    Triple("stats", "📊 Статистика", "Сколько смотрите: время, серии, топ"),
    Triple("jackett", "🔗 Jackett", "Дополнительный провайдер автопоиска торрентов"),
    Triple("netcheck", "🩺 Проверка сети", "Какие источники доступны с этого устройства"),
    Triple("logs", "📝 Логи", "Запись событий и выгрузка лога в Gist"),
    Triple("torrserve", "🚀 TorrServe", "Внешний движок торрентов — надёжнее внутреннего"),
    Triple("player", "📺 Плеер", "Встроенный или внешний плеер (VLC, MX Player)"),
    Triple("input", "⌨ Ввод", "Клавиатура: внешняя (системная) или встроенная"),
    Triple("mdbase", "🎞 База фильмов", "Свой ключ каталогов — свои лимиты, не общие"),
)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var section by remember { mutableStateOf(store.lastSettingsSection()) }

    var excluded by remember { mutableStateOf(store.excludedGenreIds()) }
    var excludedCountries by remember { mutableStateOf(store.excludedCountryIds()) }
    var keywords by remember { mutableStateOf(store.excludedKeywords()) }
    var movieGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var tvGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var countries by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var jackettUrl by remember { mutableStateOf(store.jackettUrl()) }
    var jackettKey by remember { mutableStateOf(store.jackettApiKey()) }
    var extPlayer by remember { mutableStateOf(store.useExternalPlayer()) }
    var extKb by remember { mutableStateOf(store.useExternalKeyboard()) }

    // Полноэкранный редактор текста: target = "jurl"|"jkey"|"ts"|...
    var editTarget by remember { mutableStateOf<String?>(null) }
    var editTmp by remember { mutableStateOf("") }
    // Сообщение о результате входа на частный трекер
    var loginMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Под-экраны выбора (жанры/страны/стоп-слова/проверка сети)
    var subscreen by remember { mutableStateOf<String?>(null) }
    var kpKey by remember { mutableStateOf(store.kpApiKey()) }
    var tmdbKey by remember { mutableStateOf(store.tmdbApiKey()) }
    var ghToken by remember { mutableStateOf(store.githubToken()) }

    fun openEdit(target: String, current: String, title: String) {
        editTmp = current
        editTarget = target
    }

    fun saveEdit() {
        val t = editTarget ?: return
        if (editTmp.isNotBlank()) {
            when (t) {
                "jurl" -> { store.setJackettUrl(editTmp); jackettUrl = editTmp }
                "jkey" -> { store.setJackettApiKey(editTmp); jackettKey = editTmp }
                "ts" -> store.setTorrServeHost(editTmp)
                "kpkey" -> { store.setKpApiKey(editTmp); kpKey = editTmp }
                "tmdbkey" -> { store.setTmdbApiKey(editTmp); tmdbKey = editTmp }
                "ghtoken" -> { store.setGithubToken(editTmp); ghToken = editTmp }
                "bkurl" -> store.setBackupServerUrl(editTmp)
                "bktoken" -> store.setBackupServerToken(editTmp)
                "iptvurl" -> store.setIptvPlaylistUrl(editTmp)
            }
        }
        editTarget = null
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            movieGenres = MediaApi.movieGenres()
            tvGenres = MediaApi.tvGenres()
            countries = MediaApi.countries()
        }
    }

    val (sTitle, sDesc) = SECTIONS.first { it.first == section }.let { it.second to it.third }

    Box(Modifier.fillMaxSize()) {
    Row(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(20.dp),
    ) {
        // ---------- Разделы (слева): компактно, всё видно без прокрутки ----------
        // Фокус-якоря: 0 = «← Назад», 1..n = разделы, n+1 = «Выход».
        // LEFT из правой панели возвращает к АКТИВНОМУ разделу, а не в начало.
        val itemCount = SECTIONS.size + 2
        val sectionReqs = remember { List(itemCount) { FocusRequester() } }
        // Куда попадает фокус по RIGHT из разделов — первый контрол правой панели
        val contentFocus = remember { FocusRequester() }
        var activeSection by remember { mutableStateOf(1) }
        val sectionMod: (Int) -> Modifier = { idx ->
            Modifier
                .focusRequester(sectionReqs[idx])
                .onFocusChanged { if (it.hasFocus) activeSection = idx }
        }
        // Стартовый фокус — на пункте активного раздела, а не на первом сверху
        LaunchedEffect(Unit) {
            val idx = SECTIONS.indexOfFirst { it.first == section } + 1
            if (idx > 0) {
                activeSection = idx
                runCatching { sectionReqs[idx].requestFocus() }
            }
        }
        Column(
            Modifier
                .width(252.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                // Фокус не должен убегать влево в главный сайдбар:
                // OK там переключает вкладку («сброс на главный экран»)
                .focusProperties { left = FocusRequester.Cancel }
                // RIGHT из разделов — в правую панель, а не вниз по колонке
                .onPreviewKeyEvent {
                    if (it.key == androidx.compose.ui.input.key.Key.DirectionRight &&
                        it.type == androidx.compose.ui.input.key.KeyEventType.KeyUp
                    ) {
                        runCatching { contentFocus.requestFocus() }.getOrDefault(Unit)
                        true
                    } else false
                },        ) {
            SectionItem("← Назад", false, sectionMod(0)) { onBack() }
            Spacer(Modifier.height(8.dp))
            for ((i, item) in SECTIONS.withIndex()) {
                val (id, title, _) = item
                SectionItem(title, section == id, sectionMod(i + 1)) {
                    section = id
                    store.setLastSettingsSection(id)
                    subscreen = null
                }
            }
            SectionItem("⛔ Выход", false, sectionMod(SECTIONS.size + 1)) {
                (context as? android.app.Activity)?.finishAffinity()
            }
        }

        Spacer(Modifier.width(24.dp))

        // ---------- Параметры (справа) ----------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(sTitle, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                sDesc,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    // RIGHT → первый контрол панели; LEFT → к активному разделу.
                    // Прямой перехват: focusProperties{left} здесь не ловит
                    // направление из дочерних Surface
                    .onPreviewKeyEvent {
                        if (it.type != androidx.compose.ui.input.key.KeyEventType.KeyUp) false
                        else when (it.key) {
                            androidx.compose.ui.input.key.Key.DirectionRight -> {
                                runCatching { contentFocus.requestFocus() }.getOrDefault(Unit); true
                            }
                            androidx.compose.ui.input.key.Key.DirectionLeft -> {
                                runCatching { sectionReqs[activeSection].requestFocus() }.getOrDefault(Unit); true
                            }
                            else -> false
                        }
                    },
            ) {
                when (section) {
                    "tv" -> {
                        var ownUrl by remember { mutableStateOf(store.iptvPlaylistUrl()) }
                        var useOwn by remember { mutableStateOf(store.iptvUseOwnPlaylist()) }
                        SettingsRow(
                            title = "Свой плейлист (m3u)",
                            value = ownUrl.ifBlank { "Не задан — только встроенный список" },
                            valueOk = ownUrl.isNotBlank(),
                            modifier = Modifier.focusRequester(contentFocus),
                        ) { openEdit("iptvurl", ownUrl, "Ссылка на плейлист m3u") }
                        SettingsRow(
                            title = "Подключать свой плейлист",
                            value = if (useOwn) "Да — каналы добавятся к встроенным" else "Нет — только встроенные",
                            valueOk = useOwn,
                        ) {
                            useOwn = !useOwn
                            store.setIptvUseOwnPlaylist(useOwn)
                        }
                        if (ownUrl.isNotBlank()) {
                            SettingsRow(title = "🗑 Очистить свой плейлист", value = "", danger = true) {
                                ownUrl = ""
                                store.setIptvPlaylistUrl("")
                            }
                        }
                        SettingsNote(
                            "Встроенный список — каналы, проверенные из Москвы (Москва 24, " +
                                "федеральные, музыка). Ace Stream и NoxBit не поддерживаются: " +
                                "это не HLS-потоки.",
                        )
                        SettingsNote(
                            "Телепрограмма берётся из открытого источника teleguide.info " +
                                "и обновляется сама раз в 12 часов.",
                        )
                    }

                    "stats" -> {
                        StatsPanel(store)
                    }

                    "jackett" -> {
                        SettingsRow(
                            title = "Адрес сервера",
                            value = jackettUrl.ifBlank { "Не задан" },
                            valueOk = jackettUrl.isNotBlank(),
                            modifier = Modifier.focusRequester(contentFocus),
                        ) { openEdit("jurl", jackettUrl, "Адрес Jackett") }
                        SettingsRow(
                            title = "API-ключ",
                            value = if (jackettKey.isNotBlank()) "••••••••" else "Не задан",
                            valueOk = jackettKey.isNotBlank(),
                        ) { openEdit("jkey", jackettKey, "API-ключ Jackett") }
                        if (jackettUrl.isNotBlank() || jackettKey.isNotBlank()) {
                            SettingsRow(title = "🗑 Отключить Jackett", value = "", danger = true) {
                                jackettUrl = ""; jackettKey = ""
                                store.setJackettUrl(""); store.setJackettApiKey("")
                            }
                        }
                        SettingsNote("Без Jackett работают встроенные apibay и Torrentio.")
                    }

                    "update" -> {
                        // Проверка обновлений — отдельный раздел, не прячем в «Плеере»
                        var updInfo by remember { mutableStateOf<com.torrenttv.app.update.UpdateManager.UpdateInfo?>(null) }
                        var updMsg by remember { mutableStateOf("") }
                        var updBusy by remember { mutableStateOf(false) }
                        SettingsRow(
                            title = "⬆ Проверить обновления",
                            modifier = Modifier.focusRequester(contentFocus),
                            value = when {
                                updBusy -> "Проверяю…"
                                updInfo != null ->
                                    if (com.torrenttv.app.update.UpdateManager.readyToInstall(updInfo!!))
                                        "v${updInfo!!.version} скачано — нажмите для установки"
                                    else
                                        "Доступна v${updInfo!!.version} — нажмите для установки"
                                updMsg.isNotBlank() -> updMsg
                                else -> "Текущая: v" + com.torrenttv.app.BuildConfig.VERSION_NAME
                            },
                            valueOk = updInfo != null,
                        ) {
                            if (updInfo != null) {
                                val info = updInfo!!
                                updBusy = true
                                com.torrenttv.app.data.AppLog.log("ui", "нажал: «Проверить обновления» → установка v${info.version}")
                                com.torrenttv.app.update.UpdateManager.downloadAndInstall(
                                    context,
                                    info,
                                    onProgress = { updMsg = it },
                                    onError = { updMsg = it; updBusy = false },
                                )
                            } else if (!updBusy) {
                                updBusy = true
                                updMsg = ""
                                com.torrenttv.app.data.AppLog.log("ui", "нажал: «Проверить обновления» → проверка")
                                com.torrenttv.app.update.UpdateManager.checkForUpdate(
                                    com.torrenttv.app.BuildConfig.VERSION_NAME,
                                    com.torrenttv.app.update.UpdateManager.ghToken(context),
                                    { info ->
                                        updInfo = info
                                        updBusy = false
                                        // Найдено — качаем сразу в фоне, установка будет мгновенной
                                        if (info != null) {
                                            com.torrenttv.app.update.UpdateManager.startBackgroundDownload(context, info)
                                        }
                                    },
                                    { err ->
                                        updInfo = null
                                        updBusy = false
                                        // Честный статус: не «у вас последняя», а реальная ошибка
                                        updMsg = "Не удалось проверить ($err)"
                                    },
                                )
                            }
                        }
                        SettingsNote("Обновления проверяются при запуске и качаются в фоне.")
                    }

                    "player" -> {
                        SettingsRow(
                            title = if (extPlayer) "✅ Всегда внешний плеер" else "⬜ Всегда внешний плеер",
                            value = if (extPlayer) "ВКЛ — видео открывается в VLC/MX Player" else "ВЫКЛ — встроенный плеер",
                            valueOk = extPlayer,
                            modifier = Modifier.focusRequester(contentFocus),
                        ) {
                            extPlayer = !extPlayer
                            store.setUseExternalPlayer(extPlayer)
                        }
                        SettingsNote("Видео будет открываться в VLC/MX Player, если он установлен.")
                        // Размер карточек: три варианта, циклом
                        SettingsRow(
                            title = "Размер карточек",
                            value = when (cardWidthDp) {
                                140 -> "Мелкие"
                                210 -> "Крупные"
                                else -> "Обычные"
                            },
                            valueOk = true,
                        ) {
                            cardWidthDp = when (cardWidthDp) {
                                140 -> 170
                                170 -> 210
                                else -> 140
                            }
                            store.setCardWidthDp(cardWidthDp)
                        }
                        SettingsNote("Размер постеров во всех каталогах.")
                    }

                    "logs" -> {
                        // Галочка записи: включил → пользовался приложением → отправил
                        var recOn by remember { mutableStateOf(com.torrenttv.app.data.AppLog.recording) }
                        var uploading by remember { mutableStateOf(false) }
                        var uploadState by remember { mutableStateOf("") }
                        var uploadUrl by remember { mutableStateOf("") }
                        var backupMsg by remember { mutableStateOf("") }
                        var logTail by remember { mutableStateOf(com.torrenttv.app.data.AppLog.recent(14).joinToString("\n")) }

                        SettingsRow(
                            title = if (recOn) "✅ Запись логов" else "⬜ Запись логов",
                            value = if (recOn) "Ведётся" else "Выключена",
                            valueOk = recOn,
                            modifier = Modifier.focusRequester(contentFocus),
                        ) {
                            recOn = !recOn
                            com.torrenttv.app.data.AppLog.recording = recOn
                            store.setLoggingEnabled(recOn)
                        }

                        SettingsRow(
                            title = "📤 Выгрузить лог в Gist",
                            value = if (uploading) "…" else "Выложить и показать адрес",
                        ) {
                            if (!uploading) {
                                uploading = true
                                uploadState = "Отправляю лог…"
                                uploadUrl = ""
                                com.torrenttv.app.data.AppLog.log("ui", "нажал: «Выгрузить лог в Gist» (Логи)")
                                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                    val url = com.torrenttv.app.data.AppLog.sendToPastebin(
                                        store.githubToken().ifBlank {
                                            com.torrenttv.app.update.UpdateManager.ghToken(context).ifBlank { null }
                                        },
                                    )
                                    // Состояние Compose пишем только из главного потока —
                                    // запись из IO роняла рекомпозицию и процесс
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        com.torrenttv.app.data.AppLog.log(
                                            "upload",
                                            if (url.isNotBlank()) "выгрузка ок" else "выгрузка FAIL",
                                        )
                                        uploadUrl = url
                                        uploadState = if (url.isNotBlank()) "" else
                                            "Не удалось отправить (нет доступа к pastebin-сайтам)"
                                        uploading = false
                                    }
                                }
                            }
                        }

                        SettingsRow(
                            title = "🗑 Очистить логи",
                            value = "",
                            danger = true,
                        ) {
                            com.torrenttv.app.data.AppLog.clear()
                            logTail = com.torrenttv.app.data.AppLog.recent(14).joinToString("\n")
                        }

                        // ---------- Бэкап: свой сервер (домашний Mac) ----------
                        // Настройки и кеш серий не должны жить только на телевизоре:
                        // «очистить данные» их стирает. Адрес и токен уже прописаны,
                        // поля — чтобы сменить сервер, не пересобирая приложение
                        SettingsRow(
                            title = "💾 Сервер бэкапа",
                            value = store.backupServerUrl(),
                            valueOk = store.backupServerUrl().isNotBlank(),
                        ) { openEdit("bkurl", store.backupServerUrl(), "http://192.168.3.78:8091") }
                        SettingsRow(
                            title = "🔑 Токен сервера",
                            value = if (store.backupServerToken().isNotBlank()) "••••••••" else "Не задан",
                            valueOk = store.backupServerToken().isNotBlank(),
                        ) { openEdit("bktoken", store.backupServerToken(), "Токен сервера бэкапа") }
                        SettingsRow(
                            title = "⬆ Сохранить сейчас",
                            value = if (backupMsg.isBlank()) "Настройки + кеш серий" else backupMsg,
                            valueOk = backupMsg.startsWith("✓"),
                        ) {
                            backupMsg = "Отправляю…"
                            com.torrenttv.app.data.AppLog.log("ui", "нажал: «Сохранить сейчас» (бэкап)")
                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                val ok = com.torrenttv.app.data.SettingsBackup.saveNow(context)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    backupMsg = if (ok) "✓ Отправлено на ${store.backupServerUrl()}"
                                    else "⚠ Сервер и Gist не ответили"
                                }
                            }
                        }
                        SettingsNote("Копия уходит на домашний сервер; если он недоступен — в приватный Gist. Восстанавливается сама после сброса данных.")

                        if (uploadState.isNotBlank()) {
                            SettingsNote(uploadState)
                        }
                        if (uploadUrl.isNotBlank()) {
                            SettingsNote("✓ Лог отправлен")
                        }
                        SettingsNote("Включите запись, воспроизведите проблему и нажмите «Отправить логи».")
                        // Превью последних строк — видно, что запись идёт
                        Text(
                            text = logTail.ifBlank { "(лог пуст — включите запись и подёргайте приложение)" },
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    "input" -> {
                        SettingsRow(
                            title = if (extKb) "✅ Внешняя клавиатура" else "⬜ Внешняя клавиатура",
                            value = if (extKb) "ВКЛ — системный ввод (пульт с клавиатурой, Air Mouse, USB)" else "ВЫКЛ — встроенная экранная клавиатура",
                            valueOk = extKb,
                            modifier = Modifier.focusRequester(contentFocus),
                        ) {
                            extKb = !extKb
                            store.setUseExternalKeyboard(extKb)
                        }
                        SettingsNote("ВКЛ — системная клавиатура ТВ, ВЫКЛ — экранная клавиатура приложения.")
                    }

                    "mdbase" -> {
                        SettingsRow(
                            title = "Ключ Кинопоиск API",
                            value = if (kpKey.isNotBlank()) "•••••••• (свой)" else "Общий (вшит в приложение)",
                            valueOk = kpKey.isNotBlank(),
                            modifier = Modifier.focusRequester(contentFocus),
                        ) { openEdit("kpkey", kpKey, "Ключ Кинопоиск API") }
                        SettingsRow(
                            title = "Ключ TMDB",
                            value = if (tmdbKey.isNotBlank()) "•••••••• (свой)" else "Общий (вшит в приложение)",
                            valueOk = tmdbKey.isNotBlank(),
                        ) { openEdit("tmdbkey", tmdbKey, "Ключ TMDB") }
                        SettingsRow(
                            title = "GitHub-токен (логи мне)",
                            value = if (ghToken.isNotBlank()) "•••••••• (вписан)" else "Не задан — логи уходят на pastebin",
                            valueOk = ghToken.isNotBlank(),
                        ) { openEdit("ghtoken", ghToken, "GitHub токен (право Gists)") }
                        if (kpKey.isNotBlank() || tmdbKey.isNotBlank()) {
                            SettingsRow(title = "🗑 Вернуть общие ключи", value = "", danger = true) {
                                store.setKpApiKey(""); store.setTmdbApiKey("")
                                kpKey = ""; tmdbKey = ""
                            }
                        }
                        SettingsNote("Свой ключ бесплатен: КП — kinopoiskapiunofficial.tech, TMDB — themoviedb.org.")
                    }

                    "netcheck" -> {
                        SettingsRow(
                            title = "🩺 Запустить проверку",
                            value = "Проверить все источники",
                            valueOk = true,
                            modifier = Modifier.focusRequester(contentFocus),
                        ) {
                            com.torrenttv.app.data.AppLog.log("ui", "нажал: «Запустить проверку» (Проверка сети)")
                            subscreen = "netcheck"
                        }
                        SettingsNote("Крестик у источника — его блокирует провайдер, это не неисправность.")
                    }

                    "torrserve" -> {
                        SettingsRow(
                            title = "Адрес сервера",
                            value = store.torrServeHost(),
                            valueOk = true,
                            modifier = Modifier.focusRequester(contentFocus),
                        ) { openEdit("ts", store.torrServeHost(), "http://127.0.0.1:8090") }
                        SettingsNote("Необязательно: стрим через TorrServe быстрее. Без него работает встроенный движок.")
                    }

                    else -> { // search
                        SettingsRow(
                            title = "Скрытые жанры",
                            value = "${excluded.size} выбрано",
                            valueOk = excluded.isNotEmpty(),
                        ) { subscreen = "genres" }
                        SettingsRow(
                            title = "Скрытые страны",
                            value = "${excludedCountries.size} выбрано",
                            valueOk = excludedCountries.isNotEmpty(),
                        ) { subscreen = "countries" }
                        SettingsRow(
                            title = "Стоп-слова",
                            value = if (keywords.isEmpty()) "Нет" else keywords.take(3).joinToString(", ") +
                                if (keywords.size > 3) " …" else "",
                            valueOk = keywords.isNotEmpty(),
                        ) { subscreen = "keywords" }
                        SettingsNote("Фильтры скрывают контент в каталогах, трендах и поиске.")
                    }
                }
            }
        }
    }

    // ---------- Под-экраны ----------
    subscreen?.let { sub ->
        BackHandler { subscreen = null }
        when (sub) {
            "netcheck" -> NetCheckScreen(onClose = { subscreen = null })

            "genres" -> SubscreenGenres(
                movieGenres, tvGenres, excluded,
                onToggle = { id ->
                    excluded = if (id in excluded) excluded - id else excluded + id
                    store.setExcludedGenreIds(excluded)
                },
                onClose = { subscreen = null },
            )

            "countries" -> SubscreenCountries(
                countries, excludedCountries,
                onToggle = { c ->
                    excludedCountries =
                        if (c.id in excludedCountries) excludedCountries - c.id
                        else excludedCountries + c.id
                    store.setExcludedCountryIds(excludedCountries)
                },
                onClose = { subscreen = null },
            )

            "keywords" -> SubscreenKeywords(
                keywords,
                onAdd = { w ->
                    if (w.isNotBlank() && w !in keywords) {
                        keywords = keywords + w
                        store.setExcludedKeywords(keywords)
                    }
                },
                onRemove = { w ->
                    keywords = keywords - w
                    store.setExcludedKeywords(keywords)
                },
                onClose = { subscreen = null },
            )
        }
    }

    // ---------- Полноэкранный редактор текста ----------
    editTarget?.let { target ->
        val title = when (target) {
            "jurl" -> "Адрес Jackett"
            "jkey" -> "API-ключ Jackett"
            "kpkey" -> "Ключ Кинопоиск API"
            "tmdbkey" -> "Ключ TMDB"
            "ghtoken" -> "GitHub токен (право Gists)"
            "rt" -> "Cookie для RuTracker"
            "kz" -> "Cookie для Kinozal"
            "nnm" -> "Cookie для NNM-Club"
            else -> "URL XMLTV-источника"
        }
        BackHandler { editTarget = null }
        EditScreen(
            title = title,
            value = editTmp,
            onChange = { editTmp = it },
            onSave = { saveEdit() },
            onCancel = { editTarget = null },
        )
    }

    } // Box поверх всего: подэкраны рисаются ПОВЕРХ настроек, а не «в строку»
}

// ---------- Компоненты ----------

/** Пункт списка разделов слева. */
@Composable
private fun SectionItem(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF161C26),
            contentColor = Color.White,
            focusedContainerColor = if (selected) Color(0xFFF5B942) else Color(0xFF2A3648),
            focusedContentColor = if (selected) Color(0xFF0E1116) else Color.White,
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
            disabledContainerColor = Color(0xFF161C26),
            disabledContentColor = Color.White,
        ),
    ) {
        Text(
            text = (if (selected) "▸ " else "") + title,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Строка параметра: название · значение справа, ОК — действие. */
@Composable
private fun SettingsRow(
    title: String,
    value: String,
    valueOk: Boolean = false,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .mouseClick { onClick() },
        shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF161C26),
            contentColor = Color.White,
            focusedContainerColor = if (danger) Color(0xFFB0483C) else Color(0xFFF5B942),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = if (danger) Color(0xFF93392F) else Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
            disabledContainerColor = Color(0xFF161C26),
            disabledContentColor = Color.White,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(240.dp),
                    textAlign = TextAlign.End,
                    color = when {
                        danger -> Color.White.copy(alpha = 0.9f)
                        valueOk -> Color(0xFF9CCC65)
                        else -> Color.White.copy(alpha = 0.55f)
                    },
                )
            }
        }
    }
}

/** Пояснение под параметрами. */
@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
    )
}

/** Полноэкранный редактор текста: значение + клавиатура + Сохранить/Отмена. */
@Composable
private fun EditScreen(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val kbFocus = remember { FocusRequester() }
    val externalKb = androidx.compose.ui.platform.LocalContext.current.let {
        remember { com.torrenttv.app.data.LocalStore(it).useExternalKeyboard() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(32.dp),
    ) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            text = value.ifBlank { "Введите значение…" },
            color = if (value.isBlank()) Color.White.copy(alpha = 0.35f) else Color(0xFFF5B942),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSave) { Text("💾 Сохранить") }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onCancel) { Text("Отмена") }
        }
        if (externalKb) {
            runCatching { LaunchedEffect(Unit) { kbFocus.requestFocus() } }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = false,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color(0xFFF5B942),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFF5B942)),
                modifier = Modifier
                    .focusRequester(kbFocus)
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(Color(0xFF1F2430), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isBlank()) {
                            Text("Печатайте с внешней клавиатуры…", color = Color.White.copy(alpha = 0.35f), fontSize = 16.sp)
                        }
                        inner()
                    }
                },
            )
        } else {
            TvKeyboard(
                onChar = { ch -> onChange(value + ch) },
                onBackspace = { if (value.isNotEmpty()) onChange(value.dropLast(1)) },
                onClear = { onChange("") },
                firstKeyFocus = kbFocus,
            )
        }
    }
}

// ---------- Под-экраны выбора ----------

/** Результат проверки одного источника. */
private data class NetCheckRow(val name: String, val hint: String, val ok: Boolean?, val ms: Long)

/** Проверка доступности источников: торренты, онлайн, ТВ, обновления. */
@Composable
private fun NetCheckScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rows by remember { mutableStateOf<List<NetCheckRow>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var uploadState by remember { mutableStateOf("") }
    var uploadUrl by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = remember { com.torrenttv.app.data.LocalStore(ctx) }
    val targets = remember {
        buildList {
            addAll(listOf(
                "apibay (торренты)" to "https://apibay.org/q.php?q=test&cat=201",
                "Torrentio (торренты)" to "https://torrentio.strem.fun/stream/movie/tt0133093.json",
                "Рутрекер (каталог «Торренты»)" to "https://rutrackerr.ru/popular/",
                "Rutube API (трейлеры)" to "https://rutube.ru/api/search/video/?query=test&page=1",
                "GitHub (обновления)" to "https://api.github.com/repos/ValeriyNoskov/TorrentTV/releases/latest",
            ))
            // База фильмов (каталоги, постеры, поиск) — только если настроен ключ
            if (com.torrenttv.app.data.KinopoiskClient.isConfigured) {
                add("Кинопоиск API (база фильмов)" to "https://kinopoiskapiunofficial.tech/api/v2.1/films/filters")
            }
            if (com.torrenttv.app.data.TmdbClient.isConfigured) {
                add("TMDB (база фильмов)" to "https://api.themoviedb.org/3/configuration?api_key=" + com.torrenttv.app.BuildConfig.TMDB_API_KEY)
            }
            if (com.torrenttv.app.data.TorrServeService.isInstalled(ctx)) {
                add("TorrServe (движок)" to com.torrenttv.app.data.LocalStore(ctx).torrServeHost() + "/echo")
            }
        }
    }

    suspend fun checkAll() {
        running = true
        rows = emptyList()
        com.torrenttv.app.data.AppLog.log("netcheck", "start")
        done = 0
        // Параллельно: каждый источник с жёстким таймаутом 8 сек, строки
        // появляются по мере готовности — экран не «зависает»
        coroutineScope {
            for ((name, url) in targets) {
                launch {
                    val t0 = System.currentTimeMillis()
                    val ok = withContext(Dispatchers.IO) {
                        // Отдельный клиент: callTimeout 8 сек — не ждать блокированный хост по 30 сек
                        val shortClient = com.torrenttv.app.data.TorrentHttp.client.newBuilder()
                            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        runCatching {
                            val rb = okhttp3.Request.Builder().url(url)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            // КП без ключа отвечает 401 — проверка должна ходить с ключом,
                            // иначе рабочий API показывает FAIL
                            if (url.contains("kinopoiskapiunofficial.tech")) {
                                rb.header("X-API-KEY", com.torrenttv.app.data.KinopoiskClient.currentApiKey())
                            }
                            shortClient.newCall(rb.build())
                                .execute().use { it.isSuccessful }
                        }.getOrDefault(false)
                    }
                    val row = NetCheckRow(name, url.substringAfter("//").substringBefore("/"), ok, System.currentTimeMillis() - t0)
                    rows = rows + row
                    done++
                    com.torrenttv.app.data.AppLog.log("netcheck", "$name: ${if (ok) "OK" else "FAIL"} (${row.ms} ms)")
                }
            }
        }
        com.torrenttv.app.data.AppLog.log("netcheck", "done: ok=${rows.count { it.ok == true }}/${rows.size}")
        running = false
    }

    LaunchedEffect(Unit) { checkAll() }

    // Автопроверка идёт при открытии экрана; кнопка — для повторного прогона
    if (!running && rows.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                com.torrenttv.app.data.AppLog.log("ui", "нажал: «Проверить снова» (Проверка сети)")
                kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { checkAll() }
            }) { Text("🔄 Проверить снова") }
            // TorrServe установлен, но не отвечает — открыть его, чтобы нажать UPDATE/Start
            val tsRow = rows.firstOrNull { it.name.startsWith("TorrServe") }
            if (com.torrenttv.app.data.TorrServeService.isInstalled(ctx) && tsRow?.ok != true) {
                Spacer(Modifier.width(16.dp))
                Button(onClick = {
                    runCatching {
                        ctx.packageManager.getLaunchIntentForPackage(com.torrenttv.app.data.TorrServeService.PACKAGE)?.let {
                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(it)
                        }
                    }
                }) { Text("🚀 Открыть TorrServe") }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onClose) { Text("← Готово") }
            Spacer(Modifier.width(16.dp))
            Text(
                "Проверка сети",
                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Крестик = источник заблокирован вашим провайдером (это не поломка приложения)",
            color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            for (r in rows) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF161C26), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                r.ok == true -> "✓"
                                r.ok == false -> "✗"
                                else -> "…"
                            },
                            color = when (r.ok) {
                                true -> Color(0xFF9CCC65)
                                false -> Color(0xFFEF7B6D)
                                null -> Color.White.copy(alpha = 0.4f)
                            },
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(40.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = Color.White, fontSize = 16.sp)
                            Text(r.hint, color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                        }
                        if (r.ok == true) {
                            Text(
                                "${r.ms} мс",
                                color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                running -> "Проверяю… $done из ${targets.size}"
                rows.size < 8 -> ""
                else -> "Проверка завершена: доступно ${rows.count { it.ok == true }} из ${rows.size}"
            },
            color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (!uploading) {
                        uploading = true
                        uploadState = "Отправляю лог…"
                        uploadUrl = ""
                        com.torrenttv.app.data.AppLog.log("ui", "нажал: «Выгрузить лог в Gist» (Проверка сети)")
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            val url = com.torrenttv.app.data.AppLog.sendToPastebin(store.githubToken().ifBlank { com.torrenttv.app.update.UpdateManager.ghToken(context).ifBlank { null } })
                            // Состояние Compose пишем только из главного потока
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                com.torrenttv.app.data.AppLog.log("upload", if (url.isNotBlank()) "выгрузка ок" else "выгрузка FAIL")
                                uploadUrl = url
                                uploadState = if (url.isNotBlank()) "" else
                                    "Не удалось отправить (нет доступа к pastebin-сайтам)"
                                uploading = false
                            }
                        }
                    }
                },
                enabled = !uploading,
            ) { Text(if (uploading) "⏳ Отправляю…" else "📤 Выгрузить лог в Gist") }
            if (uploadState.isNotBlank()) {
                Spacer(Modifier.width(16.dp))
                Text(uploadState, color = Color(0xFFF5B942), fontSize = 17.sp)
            }
        }
        if (uploadUrl.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "✓ Лог отправлен",
                color = Color(0xFF8AB4F8), fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Выбор скрываемых жанров: фильмы и сериалы. */
@Composable
private fun SubscreenGenres(
    movieGenres: List<Genre>,
    tvGenres: List<Genre>,
    excluded: Set<Int>,
    onToggle: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(28.dp)
            .onGloballyPositioned { c ->
                com.torrenttv.app.data.AppLog.log(
                    "settings",
                    "genres screen size=${c.size.width}x${c.size.height} pos=${c.positionInRoot()}",
                )
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onClose) { Text("← Готово") }
            Spacer(Modifier.width(16.dp))
            Text(
                "Скрытые жанры (золотые чипы — исключены)",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp),
        ) {
            Text("Фильмы:", color = Color.White.copy(alpha = 0.7f), fontSize = 17.sp)
            GenreChipsMulti(movieGenres, excluded, onToggle)
            Text(
                "Сериалы:",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 17.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
            GenreChipsMulti(tvGenres, excluded, onToggle)
        }
    }
}

/** Выбор скрываемых стран (чипы, постранично). */
@Composable
private fun SubscreenCountries(
    countries: List<Genre>,
    excluded: Set<Int>,
    onToggle: (Genre) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onClose) { Text("← Готово") }
            Spacer(Modifier.width(16.dp))
            Text(
                "Скрытые страны (☑ — исключены)",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (countries.isEmpty()) {
            Text(
                "Список стран загрузится после первого открытия каталогов",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 17.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }
        var page by remember { mutableStateOf(0) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Text(
                "${page * COUNTRIES_PAGE + 1}–${page * COUNTRIES_PAGE + countries.drop(page * COUNTRIES_PAGE).take(COUNTRIES_PAGE).size} из ${countries.size}",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 16.sp,
                modifier = Modifier.width(120.dp),
            )
            Button(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text("←") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if ((page + 1) * COUNTRIES_PAGE < countries.size) page++ },
                enabled = (page + 1) * COUNTRIES_PAGE < countries.size,
            ) { Text("→") }
        }
        val visible = countries.drop(page * COUNTRIES_PAGE).take(COUNTRIES_PAGE)
        LazyRow(Modifier.padding(top = 8.dp)) {
            items(visible.size) { i ->
                val c = visible[i]
                CheckChip(c.name, c.id in excluded) { onToggle(c) }
            }
        }
    }
}

/** Стоп-слова: список (клик — удалить) + добавление с клавиатуры. */
@Composable
private fun SubscreenKeywords(
    keywords: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val kbFocus = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onClose) { Text("← Готово") }
            Spacer(Modifier.width(16.dp))
            Text(
                "Стоп-слова (нажмите на слово — удалится)",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (keywords.isNotEmpty()) {
            LazyRow(Modifier.padding(top = 12.dp)) {
                items(keywords.size) { i ->
                    KeywordChip(keywords[i]) { onRemove(keywords[i]) }
                }
            }
        } else {
            Text(
                "Пока нет стоп-слов",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 17.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Text(
                text = draft.ifBlank { "Новое стоп-слово…" },
                color = if (draft.isBlank()) Color.White.copy(alpha = 0.35f) else Color(0xFFF5B942),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(280.dp),
            )
            Spacer(Modifier.width(16.dp))
            Button(onClick = { onAdd(draft.trim()); draft = "" }) { Text("➕ Добавить") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { draft = "" }) { Text("Очистить") }
        }
        TvKeyboard(
            onChar = { ch -> draft += ch },
            onBackspace = { if (draft.isNotEmpty()) draft = draft.dropLast(1) },
            onClear = { draft = "" },
            firstKeyFocus = kbFocus,
        )
    }
}

/** Стоп-слово: золотой чип, клик — удалить. */
@Composable
private fun KeywordChip(word: String, onRemove: () -> Unit) {
    Surface(
        onClick = onRemove,
        modifier = Modifier.padding(4.dp).mouseClick { onRemove() },
        shape = clickableSurfaceShape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFFF5B942),
            contentColor = Color(0xFF0E1116),
            focusedContainerColor = Color(0xFFFFD07A),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFFE0A82E),
            pressedContentColor = Color(0xFF0E1116),
            disabledContainerColor = Color(0xFF1F2430),
            disabledContentColor = Color.White,
        ),
    ) {
        Text(
            text = "✕  $word",
            color = Color(0xFF0E1116),
            fontSize = 17.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Статистика просмотра: время, серии, активность по месяцам и топ.
 * Считается из уже сохранённых записей (LocalStore.watchStats) — сеть не нужна.
 */
@Composable
private fun StatsPanel(store: com.torrenttv.app.data.LocalStore) {
    val stats = remember { store.watchStats() }
    val hours = stats.totalSeconds / 3600
    val minutes = (stats.totalSeconds % 3600) / 60

    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        // Главные цифры — плитками
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            StatTile(
                if (hours > 0) "$hours ч $minutes мин" else "$minutes мин",
                "всего просмотрено",
                Color(0xFFF5B942),
            )
            StatTile("${stats.episodes}", "записей о сериях", Color(0xFF7FC8A9))
            StatTile("${stats.watched}", "отмечено просмотренным", Color(0xFF9FB0C0))
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            StatTile("${stats.last7}", "за 7 дней", Color(0xFFB8C0CC))
            StatTile("${stats.last30}", "за 30 дней", Color(0xFFB8C0CC))
            StatTile("${stats.inHistory}", "в истории", Color(0xFFB8C0CC))
        }

        if (stats.byMonth.isNotEmpty() && stats.byMonth.any { it.second > 0 }) {
            Text(
                "АКТИВНОСТЬ ПО МЕСЯЦАМ",
                color = Color(0xFF8B95A7),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
            )
            val maxMonth = stats.byMonth.maxOf { it.second }.coerceAtLeast(1)
            for ((month, count) in stats.byMonth) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(month, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.width(78.dp))
                    Box(
                        Modifier
                            .height(12.dp)
                            .fillMaxWidth(count.toFloat() / maxMonth * 0.75f)
                            .background(Color(0xFFF5B942), RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("$count", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }

        if (stats.top.isNotEmpty()) {
            Text(
                "БОЛЬШЕ ВСЕГО ВРЕМЕНИ",
                color = Color(0xFF8B95A7),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
            )
            for ((title, mins) in stats.top) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF1A1F29), RoundedCornerShape(8.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text("$mins мин", color = Color(0xFFF5B942), fontSize = 13.sp)
                }
            }
        }

        if (stats.totalSeconds == 0L && stats.inHistory == 0) {
            SettingsNote("Пока пусто: посмотрите что-нибудь — статистика появится здесь.")
        }
    }
}

/** Плитка с цифрой и подписью. */
@Composable
private fun StatTile(value: String, label: String, accent: Color) {
    Column(
        Modifier
            .background(Color(0xFF1A1F29), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .width(165.dp),
    ) {
        Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
    }
}
