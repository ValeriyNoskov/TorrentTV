@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Разделы настроек: id, заголовок, краткое описание.
 * Пользовательские видны всем; разделы разработки спрятаны за пунктом
 * «🛠 Для разработчика» (код 0301) — пользователю для просмотра не нужны.
 */
private val USER_SECTIONS = listOf(
    Triple("update", "⬆ Обновление", "Проверить и установить новую версию"),
    Triple("keywords", "🚫 Стоп-слова", "Слова-исключения для поиска раздач"),
    Triple("tv", "📡 ТВ-каналы", "Свой плейлист добавляется к встроенному списку"),
    Triple("torrserve", "🚀 TorrServe", "Внешний движок торрентов — надёжнее внутреннего"),
    Triple("player", "📺 Плеер", "Встроенный или внешний плеер (VLC, MX Player)"),
    Triple("input", "⌨ Ввод", "Клавиатура: внешняя (системная) или встроенная"),
)

/** Только для разработчика: диагностика, логи, ключи, расход лимитов. */
private val DEV_SECTIONS = listOf(
    Triple("netcheck", "🩺 Проверка сети", "Какие источники доступны с этого устройства"),
    Triple("logs", "📝 Логи", "Запись событий и выгрузка лога в Gist"),
    Triple("mdbase", "🎞 База фильмов", "Свой ключ каталогов — свои лимиты, не общие"),
    Triple("usage", "📊 Запросы к API", "Расход лимитов за сегодня — ключи не показываются"),
)

private val SECTIONS = USER_SECTIONS + DEV_SECTIONS

/** Код входа в режим разработчика (запрашивается при каждом открытии). */
private const val DEV_CODE = "0301"

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenWebServer: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var section by remember { mutableStateOf(store.lastSettingsSection()) }
    // Режим разработчика: скрыт по умолчанию, открывается кодом 0301;
    // сохранённый раздел из «скрытой» зоны сбрасываем на первый пользовательский
    var devMode by remember { mutableStateOf(false) }
    if (DEV_SECTIONS.any { it.first == section } && !devMode) {
        section = USER_SECTIONS.first().first
    }

    var excluded by remember { mutableStateOf(store.excludedGenreIds()) }
    var excludedCountries by remember { mutableStateOf(store.excludedCountryIds()) }
    var keywords by remember { mutableStateOf(store.excludedKeywords()) }
    var movieGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var tvGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var countries by remember { mutableStateOf<List<Genre>>(emptyList()) }
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

    // Пункты левого меню: пользовательские всегда, девелоперские — за кодом
    val menuItems = remember(devMode) {
        buildList {
            addAll(USER_SECTIONS)
            if (devMode) {
                add(Triple("—devhead—", "— разработка —", ""))
                addAll(DEV_SECTIONS)
                add(Triple("devhide", "🙈 Скрыть разработчика", ""))
            } else {
                add(Triple("devtool", "🛠 Для разработчика", ""))
            }
        }
    }

    val (sTitle, sDesc) = (
        SECTIONS.firstOrNull { it.first == section }
            ?: menuItems.firstOrNull { !it.first.startsWith("—") && it.first != "devtool" && it.first != "devhide" }
            ?: USER_SECTIONS.first()
        ).let { it.second to it.third }

    Box(Modifier.fillMaxSize()) {
    Row(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(20.dp),
    ) {
        // ---------- Разделы (слева): компактно, всё видно без прокрутки ----------
        // Фокус-якоря: 0 = «← Назад», 1..n = пункты меню, n+1 = «Выход».
        // LEFT из правой панели возвращает к АКТИВНОМУ разделу, а не в начало.
        val itemCount = menuItems.size + 2
        val sectionReqs = remember(itemCount) { List(itemCount) { FocusRequester() } }
        // Куда попадает фокус по RIGHT из разделов — первый контрол правой панели
        val contentFocus = remember { FocusRequester() }
        var activeSection by remember { mutableStateOf(1) }
        val sectionMod: (Int) -> Modifier = { idx ->
            Modifier
                .focusRequester(sectionReqs[idx])
                .onFocusChanged { if (it.hasFocus) activeSection = idx }
        }
        // Стартовый фокус — на пункте активного раздела, а не на первом сверху
        LaunchedEffect(menuItems) {
            val idx = menuItems.indexOfFirst { it.first == section } + 1
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
            for ((i, item) in menuItems.withIndex()) {
                val (id, title, _) = item
                when {
                    // Заголовок блока «— разработка —»: некликабельная метка
                    id == "—devhead—" -> Text(
                        title,
                        color = Color(0xFFA9D7F5).copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp),
                    )
                    // «🛠 Для разработчика»: открывает ввод кода, разделом не становится
                    id == "devtool" -> SectionItem(title, false, sectionMod(i + 1)) {
                        com.torrenttv.app.data.AppLog.log("ui", "нажал: «Для разработчика» — запрос кода")
                        subscreen = "devcode"
                    }
                    // «🙈 Скрыть разработчика»: спрятать блок обратно
                    id == "devhide" -> SectionItem(title, false, sectionMod(i + 1)) {
                        devMode = false
                        if (DEV_SECTIONS.any { it.first == section }) {
                            section = USER_SECTIONS.first().first
                            store.setLastSettingsSection(section)
                        }
                        subscreen = null
                    }
                    else -> SectionItem(title, section == id, sectionMod(i + 1)) {
                        section = id
                        store.setLastSettingsSection(id)
                        subscreen = null
                    }
                }
            }
            SectionItem("⛔ Выход", false, sectionMod(menuItems.size + 1)) {
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
                        var skipDb by remember { mutableStateOf(store.skipDbEnabled()) }
                        SettingsRow(
                            title = if (skipDb) "✅ Пропуск заставок и титров по базам" else "⬜ Пропуск заставок и титров по базам",
                            value = if (skipDb) "ВКЛ — заставка/пересказ сами, титры/анонс кнопкой" else "ВЫКЛ — всё смотрим как есть",
                            valueOk = skipDb,
                            modifier = Modifier.focusRequester(contentFocus),
                        ) {
                            skipDb = !skipDb
                            store.setSkipDbEnabled(skipDb)
                        }
                        SettingsNote("Точные таймкоды из открытых баз разметки (SkipDB/IntroDB) по IMDb — то, что знает сообщество. Не перемотка по догадкам.")
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

                    "usage" -> {
                        // Счётчики сетевых запросов за сегодня; ключи не показываем,
                        // только сколько израсходовано каждым
                        val snap = remember { com.torrenttv.app.data.ApiUsage.snapshot() }
                        if (snap.kp.isEmpty() && snap.trakt == 0 && snap.tmdb == 0) {
                            val noteFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                            Text(
                                "Счётчик появится после открытия каталога или карточки.",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .focusRequester(noteFocus)
                                    .padding(top = 4.dp),
                            )
                            LaunchedEffect(Unit) {
                                runCatching { noteFocus.requestFocus() }
                            }
                        } else {
                            var focused by remember { mutableStateOf(false) }
                            Column(
                                Modifier
                                    .focusRequester(contentFocus)
                                    .onFocusChanged { focused = it.hasFocus }
                                    .background(
                                        if (focused) Color(0xFF31435A) else Color.Transparent,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .padding(14.dp),
                            ) {
                                snap.kp.forEachIndexed { i, n ->
                                    Text(
                                        "КП №${i + 1} = $n" + if (n >= 450) " ⚠ близко к лимиту" else "",
                                        color = if (n >= 450) Color(0xFFA9D7F5) else Color.White,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Text(
                                    "Trakt = ${snap.trakt}",
                                    color = if (snap.trakt >= 900) Color(0xFFA9D7F5) else Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "TMDB = ${snap.tmdb}",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 17.sp,
                                )
                                Text(
                                    "Из кеша (лимит не тратится) = ${snap.cacheHits}",
                                    color = Color(0xFF9CCC65),
                                    fontSize = 15.sp,
                                )
                                Text(
                                    // Сайт КП считает запросы ключа СО ВСЕХ устройств:
                                    // ТВ + телефон/эмулятор + ручные проверки. Наш
                                    // счётчик — только это устройство, числа не совпадут.
                                    "Счётчик — только по этому устройству. " +
                                        "На сайте КП расход ключа суммарный.",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
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
                        SettingsRow(
                            title = "🧪 Запустить тест-кейсы",
                            value = "Полный самотест: хранилище, парсеры, сеть, поиск",
                            valueOk = true,
                        ) {
                            com.torrenttv.app.data.AppLog.log("ui", "нажал: «Запустить тест-кейсы» (самотест)")
                            subscreen = "selftest"
                        }
                        SettingsNote(
                            "Тест-кейсы проверяют приложение изнутри: настройки, магниты, парсеры, " +
                                "доступность TorrServe/GitHub/КП, поиск раздач. Отчёт автоматически уйдёт в Gist."
                        )
                        SettingsNote("Крестик у источника — его блокирует провайдер, это не неисправность.")
                    }

                    "torrserve" -> {
                        // Сводка о сервере: статус, число раздач, занятый кеш —
                        // запрашивается при входе в раздел (запрос в IO-потоке)
                        var tsSummary by remember { mutableStateOf("проверяю…") }
                        LaunchedEffect(Unit) {
                            val h = store.torrServeHost()
                            tsSummary = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.torrenttv.app.data.TorrServeService.serverSummary(h)
                            }
                        }
                        SettingsRow(
                            title = "Сервер",
                            value = tsSummary,
                            valueOk = tsSummary.startsWith("✓"),
                            modifier = Modifier.focusRequester(contentFocus),
                        ) { }
                        SettingsRow(
                            title = "Адрес сервера",
                            value = store.torrServeHost(),
                            valueOk = true,
                        ) { openEdit("ts", store.torrServeHost(), "http://127.0.0.1:8090") }
                        // Веб-интерфейс TorrServe: встроенный браузер с пультом
                        var webTip by remember { mutableStateOf<String?>(null) }
                        SectionItem(
                            title = "🌐 Веб-интерфейс сервера (${store.torrServeHost()})",
                            selected = false,
                        ) {
                            val host = store.torrServeHost()
                            if (host.isBlank()) {
                                webTip = "адрес сервера не задан"
                            } else {
                                com.torrenttv.app.data.AppLog.log("ui", "нажал: «Веб-интерфейс сервера» $host — встроенный браузер")
                                // Встроенный WebView с управлением пультом — без
                                // поиска браузера на устройстве
                                onOpenWebServer(host, "Сервер")
                            }
                        }
                        webTip?.let { SettingsNote("🌐 $it") }
                        SettingsNote(
                            "Веб-интерфейс TorrServe: все раздачи на сервере, сколько скачано, " +
                                "скорость, удаление вручную. Открывается внутри приложения, управление пультом."
                        )
                        // Очистка старого кеша: сносим с сервера раздачи, которых
                        // нет в избранном (разовые просмотры, старые проверки).
                        // Деструктивное — подтверждение вторым нажатием.
                        var confirmClean by remember { mutableStateOf(false) }
                        var cleanResult by remember { mutableStateOf<String?>(null) }
                        SectionItem(
                            title = if (confirmClean) "⚠ Точно снести НЕ избранные раздачи? (нажмите ещё раз)"
                            else "🧹 Очистить старый кеш (раздачи не из избранного)",
                            selected = false,
                        ) {
                            if (!confirmClean) {
                                confirmClean = true
                                com.torrenttv.app.data.AppLog.log("ui", "нажал: «Очистить старый кеш» — ждёт подтверждения")
                            } else {
                                confirmClean = false
                                val host = store.torrServeHost()
                                com.torrenttv.app.data.AppLog.log("ui", "нажал: «Очистить старый кеш» — ПОДТВЕРЖДЕНО")
                                cleanResult = "чистлю…"
                                Thread {
                                    val saved = HashSet<String>()
                                    for (fav in store.favorites()) {
                                        for (m in store.savedMagnets(fav)) {
                                            com.torrenttv.app.data.TorrServeService.hashOf(m)
                                                ?.let { saved.add(it.lowercase()) }
                                        }
                                    }
                                    val list = com.torrenttv.app.data.TorrServeService.listTorrents(host) ?: emptyList()
                                    val stale = list.filter { it.hash.lowercase() !in saved }
                                    var freed = 0L
                                    for (t in stale) {
                                        freed += t.preloadedBytes
                                        com.torrenttv.app.data.TorrServeService.removeTorrent(host, t.hash)
                                    }
                                    val msg = if (list.isEmpty()) "сервер не ответил" else
                                        "снесено раздач: ${stale.size} из ${list.size}, освобождено ~${freed / 1024 / 1024 / 1024.0} ГБ"
                                    com.torrenttv.app.data.AppLog.log("serverdl", "очистка кеша сервера: $msg")
                                    cleanResult = msg
                                    tsSummary = com.torrenttv.app.data.TorrServeService.serverSummary(host)
                                }.apply { isDaemon = true }.start()
                            }
                        }
                        cleanResult?.let { SettingsNote("🧹 $it") }
                        SettingsNote(
                            "Очистка сносит раздачи, которых нет в избранном — избранные и их докачка " +
                                "не трогаются. Место на сервере освобождается сразу."
                        )
                        SectionItem(
                            title = (if (store.fallbackInternal()) "✓" else "○") +
                                " Качать на ТВ, если TorrServe не отдаёт поток",
                            selected = false,
                        ) {
                            store.setFallbackInternal(!store.fallbackInternal())
                            com.torrenttv.app.data.AppLog.log(
                                "ui", "настройка «Качать на ТВ»: " + if (store.fallbackInternal()) "вкл" else "выкл",
                            )
                        }
                        SettingsNote(
                            "Выкл (рекомендуется с сервером TorrServe): телевизор не качает сам — " +
                                "при отказе сервера показываем ошибку. Вкл: при сбое TorrServe фильм " +
                                "скачается на ТВ (занимает место, лимит 20 ГБ)."
                        )
                        SettingsNote("Необязательно: стрим через TorrServe быстрее. Без него работает встроенный движок.")
                    }

                    else -> { // keywords
                        SettingsRow(
                            title = "Стоп-слова",
                            value = if (keywords.isEmpty()) "Нет" else keywords.take(3).joinToString(", ") +
                                if (keywords.size > 3) " …" else "",
                            valueOk = keywords.isNotEmpty(),
                        ) { subscreen = "keywords" }
                        SettingsNote("Слова-исключения для поиска раздач. Жанры и страны скрываются кнопкой «⚙ Фильтры» прямо в каталогах.")
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
            "selftest" -> SelfTestScreen(onClose = { subscreen = null })

            // Ввод кода разработчика: 4 цифры с пульта, верно — показать блок
            "devcode" -> DevCodeScreen(
                onOk = {
                    devMode = true
                    section = DEV_SECTIONS.first().first
                    subscreen = null
                    com.torrenttv.app.data.AppLog.log("ui", "код принят — режим разработчика включён")
                },
                onClose = { subscreen = null },
            )

            "genres" -> FilterPicker(
                title = "Скрытые жанры",
                groups = listOf("Фильмы:" to movieGenres, "Сериалы:" to tvGenres),
                excluded = excluded,
                onToggle = { g ->
                    excluded = if (g.id in excluded) excluded - g.id else excluded + g.id
                    store.setExcludedGenreIds(excluded)
                },
                onClearAll = { excluded = emptySet(); store.setExcludedGenreIds(excluded) },
                onClose = { subscreen = null },
            )

            "countries" -> FilterPicker(
                title = "Скрытые страны",
                groups = listOf("Страны:" to countries),
                excluded = excludedCountries,
                onToggle = { c ->
                    excludedCountries =
                        if (c.id in excludedCountries) excludedCountries - c.id
                        else excludedCountries + c.id
                    store.setExcludedCountryIds(excludedCountries)
                },
                onClearAll = {
                    excludedCountries = emptySet()
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
                onClearAll = { keywords = emptyList(); store.setExcludedKeywords(keywords) },
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
            focusedContainerColor = if (selected) Color(0xFFA9D7F5) else Color(0xFF2A3648),
            focusedContentColor = if (selected) Color(0xFF0E1116) else Color.White,
            pressedContainerColor = Color(0xFF7FBDE8),
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
            focusedContainerColor = if (danger) Color(0xFFB0483C) else Color(0xFFA9D7F5),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = if (danger) Color(0xFF93392F) else Color(0xFF7FBDE8),
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
    var showKb by remember { mutableStateOf(true) }
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
            color = if (value.isBlank()) Color.White.copy(alpha = 0.35f) else Color(0xFFA9D7F5),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSave) { Text("💾 Сохранить") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { showKb = true }) { Text("⌨ Ввести") }
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
                    color = Color(0xFFA9D7F5),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFA9D7F5)),
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
            KeyboardSheet(
                value = value,
                title = title,
                onChar = { ch -> onChange(value + ch) },
                onBackspace = { if (value.isNotEmpty()) onChange(value.dropLast(1)) },
                onClear = { onChange("") },
                onDismiss = { showKb = false },
                onSuggest = { onChange(it) },
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
                Text(uploadState, color = Color(0xFFA9D7F5), fontSize = 17.sp)
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
private fun SubscreenKeywords(
    keywords: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit = {},
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showKb by remember { mutableStateOf(false) }
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
            Button(onClick = { showKb = true }) { Text("⌨ Ввести слово") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onClearAll, enabled = keywords.isNotEmpty()) { Text("🗑 Удалить все") }
        }
        KeyboardSheet(
            value = draft,
            title = "Новое стоп-слово",
            onChar = { ch -> draft += ch },
            onBackspace = { if (draft.isNotEmpty()) draft = draft.dropLast(1) },
            onClear = { draft = "" },
            onDismiss = { showKb = false },
            onSuggest = { draft = it },
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
            containerColor = Color(0xFFA9D7F5),
            contentColor = Color(0xFF0E1116),
            focusedContainerColor = Color(0xFFCDE9FA),
            focusedContentColor = Color(0xFF0E1116),
            pressedContainerColor = Color(0xFF7FBDE8),
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
 * Ввод кода разработчика (DEV_CODE): экранная цифровая панель, управляемая
 * стрелками пульта (у ТВ-пульта цифровых кнопок нет), плюс поддержка
 * аппаратных цифр — если клавиатура/пульт с цифрами подключены.
 * Верно — onOk (блок «разработка» появляется в меню), иначе «Неверный код»
 * и очистка. Код запрашивается при каждом открытии — не запоминаем.
 */
@Composable
private fun DevCodeScreen(onOk: () -> Unit, onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    // Панель на экране — только при встроенном вводе; внешняя клавиатура
    // (галочка в «⌨ Ввод») — цифры напрямую с неё
    val externalKb = LocalContext.current.let {
        remember { com.torrenttv.app.data.LocalStore(it).useExternalKeyboard() }
    }
    // Общий обработчик цифры: экранная панель и аппаратные клавиши ведут одинаково
    fun press(d: String) {
        when {
            d == "⌫" -> { code = code.dropLast(1); wrong = false }
            d != "OK" && code.length < 4 -> {
                code += d
                if (code.length == 4) {
                    if (code == DEV_CODE) onOk()
                    else { wrong = true; code = "" }
                }
            }
        }
    }
    BackHandler { onClose() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            // Аппаратные цифры (внешняя клавиатура / пульт с цифрами)
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyUp) return@onPreviewKeyEvent false
                val digit = when (e.key) {
                    androidx.compose.ui.input.key.Key.Zero, androidx.compose.ui.input.key.Key.NumPad0 -> "0"
                    androidx.compose.ui.input.key.Key.One, androidx.compose.ui.input.key.Key.NumPad1 -> "1"
                    androidx.compose.ui.input.key.Key.Two, androidx.compose.ui.input.key.Key.NumPad2 -> "2"
                    androidx.compose.ui.input.key.Key.Three, androidx.compose.ui.input.key.Key.NumPad3 -> "3"
                    androidx.compose.ui.input.key.Key.Four, androidx.compose.ui.input.key.Key.NumPad4 -> "4"
                    androidx.compose.ui.input.key.Key.Five, androidx.compose.ui.input.key.Key.NumPad5 -> "5"
                    androidx.compose.ui.input.key.Key.Six, androidx.compose.ui.input.key.Key.NumPad6 -> "6"
                    androidx.compose.ui.input.key.Key.Seven, androidx.compose.ui.input.key.Key.NumPad7 -> "7"
                    androidx.compose.ui.input.key.Key.Eight, androidx.compose.ui.input.key.Key.NumPad8 -> "8"
                    androidx.compose.ui.input.key.Key.Nine, androidx.compose.ui.input.key.Key.NumPad9 -> "9"
                    androidx.compose.ui.input.key.Key.Backspace -> "⌫"
                    else -> null
                }
                if (digit != null && code.length <= 4) { press(digit); true } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒 Код доступа", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                if (externalKb) "Введите 4 цифры на клавиатуре" else "Стрелками выберите 4 цифры, OK — ввести",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 20.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(width = 56.dp, height = 64.dp)
                            .background(Color(0xFF1F2430), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (i < code.length) "●" else "",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (wrong) {
                Text(
                    "Неверный код",
                    color = Color(0xFFE57373),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            // Экранная панель 0–9 + ⌫ — только при встроенном вводе
            if (!externalKb) {
                // Фокус — сразу на «1»: без этого фокус непредсказуем и код
                // «не набирается» (проверено на эмуляторе)
                val padFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { padFocus.requestFocus() } }
                val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 20.dp),
                ) {
                    for (row in digits.chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                            for (d in row) {
                                androidx.tv.material3.Surface(
                                    onClick = { press(d) },
                                    modifier = if (d == "1") Modifier.focusRequester(padFocus) else Modifier,
                                    shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = Color(0xFF1F2430),
                                        contentColor = Color.White,
                                        focusedContainerColor = Color(0xB3FFFFFF),
                                        focusedContentColor = Color(0xFF0E1116),
                                        pressedContainerColor = Color(0xFF273548),
                                        pressedContentColor = Color.White,
                                    ),
                                ) {
                                    Text(
                                        d,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Button(onClick = onClose, modifier = Modifier.padding(top = 24.dp)) { Text("← Отмена") }
        }
    }
}

/** Экран самотеста: прогон тест-кейсов с пульта, отчёт — автоматически в Gist. */
@Composable
private fun SelfTestScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { com.torrenttv.app.data.LocalStore(context) }
    var results by remember { mutableStateOf<List<com.torrenttv.app.data.SelfTest.Result>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var progressText by remember { mutableStateOf("") }
    var reportUrl by remember { mutableStateOf("") }
    var uploadState by remember { mutableStateOf("") }

    fun fmtEta(sec: Int): String = if (sec >= 60) "~${sec / 60} мин ${sec % 60} с" else "~$sec с"

    LaunchedEffect(Unit) {
        running = true
        com.torrenttv.app.data.AppLog.log("selftest", "=== старт самотеста ===")
        results = com.torrenttv.app.data.SelfTest.run(context, store) { done, total, etaSec, name ->
            // Прогресс и оценка времени: без них «то ли висит, то ли делает»
            current = name
            progressText = "Проверка ${done + 1} из $total (${done * 100 / total}%) · осталось ${fmtEta(etaSec)}"
        }
        val fails = results.count { !it.ok }
        com.torrenttv.app.data.AppLog.log(
            "selftest", "готово: ${results.size - fails}/${results.size} пройдено",
        )
        // Отчёт — сразу в Gist: разработчику можно прислать одну ссылку
        uploadState = "Выгружаю отчёт…"
        val url = kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.torrenttv.app.data.AppLog.sendToPastebin(
                githubToken = store.githubToken().ifBlank {
                    com.torrenttv.app.update.UpdateManager.ghToken(context).ifBlank { null }
                },
                overrideContent = com.torrenttv.app.data.SelfTest.report(results),
                overrideTitle = "ВНосКино самотест",
            )
        }
        com.torrenttv.app.data.AppLog.log("selftest", if (url.isNotBlank()) "отчёт выгружен" else "выгрузка отчёта FAIL")
        reportUrl = url
        uploadState = if (url.isNotBlank()) "" else "Не удалось выгрузить отчёт — вышлите лог вручную"
        running = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(32.dp),
    ) {
        Text("🧪 Тест-кейсы", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        if (running && results.isEmpty()) {
            Text(current.ifBlank { "Запускаю проверки…" }, color = Color(0xFFBFE3F7), fontSize = 17.sp)
            if (progressText.isNotBlank()) {
                Text(progressText, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
            }
        }
        for (r in results) {
            Column(Modifier.padding(vertical = 5.dp)) {
                Text(
                    (if (r.ok) "✓ " else "✗ ") + r.name,
                    color = if (r.ok) Color(0xFF7ED49A) else Color(0xFFF28B82),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${r.detail} · ${r.ms} мс",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 22.dp),
                )
            }
        }
        if (running && results.isNotEmpty()) {
            Text("⏳ Следующая проверка…", color = Color(0xFFBFE3F7), fontSize = 15.sp)
        }
        Spacer(Modifier.height(20.dp))
        if (!running && results.isNotEmpty()) {
            val okN = results.count { it.ok }
            Text(
                "Итог: $okN/${results.size} пройдено",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            if (reportUrl.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "✓ Отчёт выгружен — пришлите разработчику ссылку:",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
                )
                Text(reportUrl, color = Color(0xFFBFE3F7), fontSize = 14.sp)
            }
            if (uploadState.isNotBlank()) SettingsNote(uploadState)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, enabled = !running) { Text(if (running) "Идёт проверка…" else "← Назад") }
    }
}
