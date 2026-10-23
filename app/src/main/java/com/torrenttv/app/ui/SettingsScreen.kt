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
import androidx.compose.ui.focus.focusRequester
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
import com.torrenttv.app.data.OnlineService
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
    Triple("search", "🔍 Поиск и фильтры", "Что скрывать из каталогов и поиска"),
    Triple("jackett", "🔗 Jackett", "Дополнительный провайдер автопоиска торрентов"),
    Triple("trackers", "🔑 Частные трекеры", "RuTracker, Kinozal, NNM-Club — без Jackett"),
    Triple("filmix", "🌐 Онлайн-источники", "Filmix и HDRezka — просмотр без торрентов"),
    Triple("epg", "📆 Телеканалы", "Каналы IPTV и телепрограмма"),
    Triple("netcheck", "🩺 Проверка сети", "Какие источники доступны с этого устройства"),
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
    var rtCookie by remember { mutableStateOf(store.trackerCookie(PrivateTrackers.RUTRACKER)) }
    var extPlayer by remember { mutableStateOf(store.useExternalPlayer()) }
    var extKb by remember { mutableStateOf(store.useExternalKeyboard()) }
    var kzCookie by remember { mutableStateOf(store.trackerCookie(PrivateTrackers.KINOZAL)) }
    var nnmCookie by remember { mutableStateOf(store.trackerCookie(PrivateTrackers.NNMCLUB)) }
    var fxToken by remember { mutableStateOf(store.filmixToken()) }
    var fxLogin by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf(store.epgUrl()) }
    var iptvAll by remember { mutableStateOf(store.iptvAllGroups()) }

    // Полноэкранный редактор текста: target = "jurl"|"jkey"|"rt"|"kz"|"nnm"|"epg"|"rtlogin"|"kzlogin"|"nnmlogin"
    var editTarget by remember { mutableStateOf<String?>(null) }
    var editTmp by remember { mutableStateOf("") }
    // Сообщение о результате входа на частный трекер
    var loginMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Под-экраны выбора (жанры/страны/стоп-слова/проверка сети)
    var subscreen by remember { mutableStateOf<String?>(null) }
    // Ожидание активации кода Filmix
    var fxWaiting by remember { mutableStateOf<String?>(null) } // user_code
    var fxMsg by remember { mutableStateOf("") }
    var fxPoll by remember { mutableStateOf(0) } // тик запуска поллинга
    var kpKey by remember { mutableStateOf(store.kpApiKey()) }
    var tmdbKey by remember { mutableStateOf(store.tmdbApiKey()) }
    var ghToken by remember { mutableStateOf(store.githubToken()) }

    fun openEdit(target: String, current: String, title: String) {
        editTmp = current
        editTarget = target
    }

    fun saveEdit() {
        val t = editTarget ?: return
        // Вход на частный трекер логином/паролем (формат "логин:пароль")
        if (t.endsWith("login")) {
            val id = t.removeSuffix("login")
            val parts = editTmp.split(':', limit = 2)
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                loginMsg = "Формат: логин:пароль (через двоеточие)"
                editTarget = null
                return
            }
            loginMsg = "Вхожу…"
            editTarget = null
            scope.launch {
                val cookie = withContext(Dispatchers.IO) {
                    PrivateTrackers.login(id, parts[0].trim(), parts[1].trim())
                }
                if (cookie != null) {
                    store.setTrackerCookie(id, cookie)
                    when (id) {
                        PrivateTrackers.RUTRACKER -> rtCookie = cookie
                        PrivateTrackers.KINOZAL -> kzCookie = cookie
                        else -> nnmCookie = cookie
                    }
                    loginMsg = "✓ Вход выполнен"
                } else {
                    loginMsg = "✗ Не удалось войти: проверьте логин/пароль (или трекер требует капчу — тогда используйте Cookie)"
                }
            }
            return
        }
        if (editTmp.isNotBlank()) {
            when (t) {
                "jurl" -> { store.setJackettUrl(editTmp); jackettUrl = editTmp }
                "jkey" -> { store.setJackettApiKey(editTmp); jackettKey = editTmp }
                "rt" -> { store.setTrackerCookie(PrivateTrackers.RUTRACKER, editTmp); rtCookie = editTmp }
                "kz" -> { store.setTrackerCookie(PrivateTrackers.KINOZAL, editTmp); kzCookie = editTmp }
                "nnm" -> { store.setTrackerCookie(PrivateTrackers.NNMCLUB, editTmp); nnmCookie = editTmp }
                "epg" -> { store.setEpgUrl(editTmp); epgUrl = editTmp }
                "ts" -> store.setTorrServeHost(editTmp)
                "kpkey" -> { store.setKpApiKey(editTmp); kpKey = editTmp }
                "tmdbkey" -> { store.setTmdbApiKey(editTmp); tmdbKey = editTmp }
                "ghtoken" -> { store.setGithubToken(editTmp); ghToken = editTmp }
            }
        }
        editTarget = null
    }

    // Filmix: код устройства → активация на filmix.ac/consoles → поллинг токена
    LaunchedEffect(fxPoll) {
        if (fxPoll == 0) return@LaunchedEffect
        fxMsg = "Получаю код устройства…"
        val dc = withContext(Dispatchers.IO) { OnlineService.requestDeviceCode() }
        if (dc == null) {
            fxMsg = "Filmix недоступен — проверьте сеть и попробуйте ещё раз"
            fxWaiting = null
            return@LaunchedEffect
        }
        fxWaiting = dc.userCode
        for (i in 0 until 36) { // ждём активацию до 6 минут
            kotlinx.coroutines.delay(10_000)
            if (fxWaiting == null) return@LaunchedEffect // пользователь отменил
            val user = withContext(Dispatchers.IO) { OnlineService.profile(dc.token) }
            if (user != null) {
                store.setFilmixToken(dc.token)
                fxToken = dc.token
                fxLogin = user.optString("login")
                fxWaiting = null
                return@LaunchedEffect
            }
        }
        fxMsg = "Код не введён (истёк) — попробуйте ещё раз"
        fxWaiting = null
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            movieGenres = MediaApi.movieGenres()
            tvGenres = MediaApi.tvGenres()
            countries = MediaApi.countries()
            val token = store.filmixToken()
            if (token.isNotBlank()) {
                OnlineService.profile(token)?.let { fxLogin = it.optString("login") }
            }
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
        Column(
            Modifier
                .width(252.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionItem("← Назад", false) { onBack() }
            Spacer(Modifier.height(8.dp))
            for ((id, title, _) in SECTIONS) {
                SectionItem(title, section == id) {
                    section = id
                    store.setLastSettingsSection(id)
                    subscreen = null
                }
            }
            SectionItem("⛔ Выход", false) {
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
                    .verticalScroll(rememberScrollState()),
            ) {
                when (section) {
                    "jackett" -> {
                        SettingsRow(
                            title = "Адрес сервера",
                            value = jackettUrl.ifBlank { "Не задан" },
                            valueOk = jackettUrl.isNotBlank(),
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
                        SettingsNote(
                            "Jackett ищет сразу по всем вашим индексерам (Torznab). " +
                                "Установите Jackett на компьютер/сервер в домашней сети — " +
                                "адрес и API-ключ будут на его главной странице. " +
                                "Без Jackett работают встроенные apibay и Torrentio."
                        )
                    }

                    "trackers" -> {
                        for ((id, title, hint) in PrivateTrackers.ALL) {
                            val cookie = when (id) {
                                PrivateTrackers.RUTRACKER -> rtCookie
                                PrivateTrackers.KINOZAL -> kzCookie
                                else -> nnmCookie
                            }
                            SettingsRow(
                                title = title,
                                value = if (cookie.isNotBlank()) "✓ Подключён" else "Не подключён",
                                valueOk = cookie.isNotBlank(),
                            ) { openEdit(id, cookie, "Cookie для $title") }
                            // Вход без браузера: логин и пароль прямо здесь
                            SettingsRow(
                                title = "🔑 Вход в $title (логин:пароль)",
                                value = "",
                            ) {
                                openEdit(
                                    "${id}login",
                                    "",
                                    "Логин и пароль для $title\nформат: логин:пароль",
                                )
                            }
                        }
                        if (loginMsg.isNotBlank()) {
                            SettingsNote(loginMsg)
                        }
                        SettingsNote(
                            "Есть аккаунт на трекере? Введите логин и пароль через двоеточие " +
                                "(например ivan:parol123) — приложение войдёт само, браузер не нужен. " +
                                "Нет аккаунта — зарегистрируйтесь на сайте трекера, либо оставьте " +
                                "только открытые трекеры: они работают без регистрации."
                        )
                    }

                    "player" -> {
                        // Проверка обновлений вручную (обычно она автоматом при старте)
                        var updInfo by remember { mutableStateOf<com.torrenttv.app.update.UpdateManager.UpdateInfo?>(null) }
                        var updMsg by remember { mutableStateOf("") }
                        var updBusy by remember { mutableStateOf(false) }
                        SettingsRow(
                            title = "⬆ Проверить обновления",
                            value = when {
                                updBusy -> "Проверяю…"
                                updInfo != null -> "Доступна v${updInfo!!.version} — нажмите для установки"
                                updMsg.isNotBlank() -> updMsg
                                else -> "Текущая: v" + com.torrenttv.app.BuildConfig.VERSION_NAME
                            },
                            valueOk = updInfo != null,
                        ) {
                            if (updInfo != null) {
                                val info = updInfo!!
                                updBusy = true
                                com.torrenttv.app.update.UpdateManager.downloadAndInstall(
                                    context,
                                    info,
                                    onProgress = { updMsg = it },
                                    onError = { updMsg = it; updBusy = false },
                                )
                            } else if (!updBusy) {
                                updBusy = true
                                updMsg = ""
                                com.torrenttv.app.update.UpdateManager.checkForUpdate(
                                    com.torrenttv.app.BuildConfig.VERSION_NAME,
                                ) { info ->
                                    updInfo = info
                                    updBusy = false
                                    updMsg = if (info == null) "✓ У вас последняя версия" else ""
                                }
                            }
                        }
                        SettingsRow(
                            title = if (extPlayer) "✅ Всегда внешний плеер" else "⬜ Всегда внешний плеер",
                            value = if (extPlayer) "ВКЛ — видео открывается в VLC/MX Player" else "ВЫКЛ — встроенный плеер",
                            valueOk = extPlayer,
                        ) {
                            extPlayer = !extPlayer
                            store.setUseExternalPlayer(extPlayer)
                        }
                        SettingsNote(
                            "Включите, если предпочитаете смотреть через установленный плеер " +
                                "(VLC, MX Player и т.п.) — приложение будет передавать видео ему. " +
                                "Если плеер не установлен, видео откроется как обычно во встроенном. " +
                                "Кнопка «⤴ Внешний плеер» поверх видео работает и без этой галочки."
                        )
                        // Размер карточек: как в Filmix — три варианта, циклом
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
                        SettingsNote(
                            "Размер постеров в каталогах: Мелкие — больше карточек в ряд, " +
                                "Крупные — как в Filmix HD. Применяется сразу ко всем экранам."
                        )
                    }

                    "input" -> {
                        SettingsRow(
                            title = if (extKb) "✅ Внешняя клавиатура" else "⬜ Внешняя клавиатура",
                            value = if (extKb) "ВКЛ — системный ввод (пульт с клавиатурой, Air Mouse, USB)" else "ВЫКЛ — встроенная экранная клавиатура",
                            valueOk = extKb,
                        ) {
                            extKb = !extKb
                            store.setUseExternalKeyboard(extKb)
                        }
                        SettingsNote(
                            "ВКЛ: в поиске и при вводе текста открывается системная клавиатура " +
                                "вашего ТВ/приставки — печатайте с пульта с микрофоном-клавиатурой, " +
                                "Air Mouse или подключённой USB/Bluetooth-клавиатуры. " +
                                "ВЫКЛ: буквы выбираются курсором на встроенной экранной клавиатуре."
                        )
                    }

                    "mdbase" -> {
                        SettingsRow(
                            title = "Ключ Кинопоиск API",
                            value = if (kpKey.isNotBlank()) "•••••••• (свой)" else "Общий (вшит в приложение)",
                            valueOk = kpKey.isNotBlank(),
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
                        SettingsNote(
                            "Каталоги и поиск фильмов берутся из базы Кинопоиска, при её сбое — из TMDB. " +
                                "Вшитый ключ общий на всех, и его дневной лимит может кончаться. " +
                                "Введите личный ключ (бесплатно): Кинопоиск — регистрация на kinopoiskapiunofficial.tech " +
                                "(200 запросов/сутки на ваш ключ), TMDB — themoviedb.org (лимитов почти нет). " +
                                "Со своим ключом каталоги перестанут зависеть от чужих лимитов."
                        )
                    }

                    "filmix" -> {
                        SettingsRow(
                            title = "Подключение аккаунта",
                            value = when {
                                fxLogin.isNotBlank() -> "✓ $fxLogin"
                                fxToken.isNotBlank() -> "✓ Подключено"
                                else -> "Не подключено"
                            },
                            valueOk = fxToken.isNotBlank(),
                        ) {
                            fxMsg = ""
                            fxPoll++
                        }
                        if (fxMsg.isNotBlank()) {
                            SettingsNote(fxMsg)
                        }
                        SettingsNote(
                            "Онлайн-каталоги: Filmix и HDRezka — приложение ищет в обоих. " +
                                "HDRezka работает без настроек. Filmix (больше переводов) — " +
                                "по коду устройства: нажмите «Подключение аккаунта», приложение " +
                                "покажет код — введите его на странице filmix.ac/consoles " +
                                "(в браузере, в своём аккаунте Filmix)."
                        )
                    }

                    "epg" -> {
                        SettingsRow(
                            title = "Источник программы",
                            value = epgUrl.ifBlank { "teleguide.info (Россия) — по умолчанию" },
                            valueOk = true,
                        ) { openEdit("epg", epgUrl, "URL XMLTV-источника") }
                        SettingsRow(
                            title = "Показывать все группы каналов",
                            value = if (iptvAll) "Все" else "Только тематические (без мусора)",
                            valueOk = !iptvAll,
                        ) {
                            iptvAll = !iptvAll
                            store.setIptvAllGroups(iptvAll)
                        }
                        SettingsNote(
                            "По умолчанию показываются федеральные и тематические каналы " +
                                "(кино, сериалы, детские, спорт, музыка, познавательные) — " +
                                "без регионального мусора. Включите «Все группы», если " +
                                "нужны каналы, которых нет в списке. Программа («Сейчас/Далее») " +
                                "обновляется раз в 6 часов; источник можно поменять выше."
                        )
                    }

                    "netcheck" -> {
                        SettingsRow(
                            title = "🩺 Запустить проверку",
                            value = "Проверить 7 источников",
                            valueOk = true,
                        ) { subscreen = "netcheck" }
                        SettingsNote(
                            "Проверка показывает, какие источники (торренты, онлайн, ТВ, " +
                                "обновления) доступны с этого устройства. Если напротив " +
                                "источника крестик — его блокирует ваш провайдер: это не " +
                                "неисправность приложения. Перешлите результат в поддержку."
                        )
                    }

                    "torrserve" -> {
                        SettingsRow(
                            title = "Адрес сервера",
                            value = store.torrServeHost(),
                            valueOk = true,
                        ) { openEdit("ts", store.torrServeHost(), "http://127.0.0.1:8090") }
                        SettingsNote(
                            "TorrServe (MatriX) — отдельное бесплатное приложение-движок " +
                                "торрент-стриминга. Установите его (F-Droid: ru.yourok.torrserve), " +
                                "запустите — и ВНосКино автоматически станет стримить через него: " +
                                "быстрее поиск пиров и стабильнее воспроизведение. Если TorrServe " +
                                "не установлен или не отвечает, приложение пользуется внутренним движком. " +
                                "На этом же ТВ-боксе оставьте адрес 127.0.0.1:8090."
                        )
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
                        SettingsNote(
                            "Фильтры действуют на каталоги, тренды, hero-баннер и поиск: " +
                                "контент с выбранными жанрами, странами и словами в названии " +
                                "скрывается."
                        )
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

    // ---------- Ожидание кода Filmix ----------
    fxWaiting?.let { code ->
        BackHandler { fxWaiting = null }
        FilmixCodeScreen(
            code = code,
            msg = fxMsg,
            onCancel = { fxWaiting = null },
        )
    }
    } // Box поверх всего: подэкраны рисаются ПОВЕРХ настроек, а не «в строку»
}

// ---------- Компоненты ----------

/** Пункт списка разделов слева. */
@Composable
private fun SectionItem(title: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
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
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
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
            LaunchedEffect(Unit) { kbFocus.requestFocus() }
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

/** Экран ожидания: код устройства Filmix крупно. */
@Composable
private fun FilmixCodeScreen(code: String, msg: String, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF00E1116))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.padding(top = 60.dp))
        Text(
            "Подключение Filmix",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "1. Откройте в браузере  filmix.ac/consoles  и войдите в аккаунт\n" +
                "2. Введите там код:",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            code,
            color = Color(0xFFF5B942),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            "Ожидаю активацию…",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 16.dp),
        )
        Spacer(Modifier.padding(top = 24.dp))
        Button(onClick = onCancel) { Text("Отмена") }
    }
}

// ---------- Под-экраны выбора ----------

/** Результат проверки одного источника. */
private data class NetCheckRow(val name: String, val hint: String, val ok: Boolean?, val ms: Long)

/** Проверка доступности источников: торренты, онлайн, ТВ, обновления. */
@Composable
private fun NetCheckScreen(onClose: () -> Unit) {
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
                "bt4g (торренты)" to "https://bt4gprx.com/api/search?q=test",
                "YTS (торренты)" to "https://yts.mx/api/v2/list_movies.json?query_term=test",
                "Filmix (онлайн)" to "http://filmixapp.cyou/api/v2/token_request?user_dev_apk=2.0.1&user_dev_id=&user_dev_name=Xiaomi&user_dev_os=11&user_dev_token=&user_dev_vendor=Xiaomi",
                "HDRezka (онлайн)" to "https://hdrezka.ag/",
                "IPTV-плейлист (ТВ)" to "https://iptv-org.github.io/iptv/countries/ru.m3u",
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
                            shortClient.newCall(
                                okhttp3.Request.Builder().url(url)
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                    .build()
                            ).execute().use { it.isSuccessful }
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
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            val url = com.torrenttv.app.data.AppLog.sendToPastebin(store.githubToken().ifBlank { null })
                            com.torrenttv.app.data.AppLog.log("upload", if (url.isNotBlank()) "sent -> $url" else "send FAILED")
                            uploadUrl = url
                            uploadState = if (url.isNotBlank()) "" else
                                "Не удалось отправить (нет доступа к pastebin-сайтам)"
                            uploading = false
                        }
                    }
                },
                enabled = !uploading,
            ) { Text(if (uploading) "⏳ Отправляю…" else "📤 Отправить лог разработчику") }
            if (uploadState.isNotBlank()) {
                Spacer(Modifier.width(16.dp))
                Text(uploadState, color = Color(0xFFF5B942), fontSize = 17.sp)
            }
        }
        if (uploadUrl.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Назовите разработчику этот адрес:",
                color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp,
            )
            Text(
                uploadUrl.trim(),
                color = Color(0xFF8AB4F8), fontSize = 24.sp, fontWeight = FontWeight.Bold,
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
