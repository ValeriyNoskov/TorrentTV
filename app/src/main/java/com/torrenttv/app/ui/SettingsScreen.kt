package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import kotlinx.coroutines.Dispatchers
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
    Triple("filmix", "🌐 Filmix", "Онлайн-просмотр без торрентов"),
    Triple("epg", "📆 Телепрограмма", "EPG для телеканалов (XMLTV)"),
)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var section by remember { mutableStateOf("search") }

    var excluded by remember { mutableStateOf(store.excludedGenreIds()) }
    var excludedCountries by remember { mutableStateOf(store.excludedCountryIds()) }
    var keywords by remember { mutableStateOf(store.excludedKeywords()) }
    var movieGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var tvGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var countries by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var jackettUrl by remember { mutableStateOf(store.jackettUrl()) }
    var jackettKey by remember { mutableStateOf(store.jackettApiKey()) }
    var rtCookie by remember { mutableStateOf(store.trackerCookie(PrivateTrackers.RUTRACKER)) }
    var kzCookie by remember { mutableStateOf(store.trackerCookie(PrivateTrackers.KINOZAL)) }
    var nnmCookie by remember { mutableStateOf(store.trackerCookie(PrivateTrackers.NNMCLUB)) }
    var fxToken by remember { mutableStateOf(store.filmixToken()) }
    var fxLogin by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf(store.epgUrl()) }

    // Полноэкранный редактор текста: target = "jurl"|"jkey"|"rt"|"kz"|"nnm"|"epg"
    var editTarget by remember { mutableStateOf<String?>(null) }
    var editTmp by remember { mutableStateOf("") }
    // Под-экраны выбора (жанры/страны/стоп-слова)
    var subscreen by remember { mutableStateOf<String?>(null) }
    // Ожидание активации кода Filmix
    var fxWaiting by remember { mutableStateOf<String?>(null) } // user_code
    var fxMsg by remember { mutableStateOf("") }
    var fxPoll by remember { mutableStateOf(0) } // тик запуска поллинга

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
                "rt" -> { store.setTrackerCookie(PrivateTrackers.RUTRACKER, editTmp); rtCookie = editTmp }
                "kz" -> { store.setTrackerCookie(PrivateTrackers.KINOZAL, editTmp); kzCookie = editTmp }
                "nnm" -> { store.setTrackerCookie(PrivateTrackers.NNMCLUB, editTmp); nnmCookie = editTmp }
                "epg" -> { store.setEpgUrl(editTmp); epgUrl = editTmp }
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

    Row(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(24.dp),
    ) {
        // ---------- Разделы (слева) ----------
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Button(onClick = onBack) { Text("← Назад") }
            Text(
                "Настройки",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
            )
            for ((id, title, _) in SECTIONS) {
                SectionItem(title, section == id) {
                    section = id
                    subscreen = null
                }
            }
        }

        Spacer(Modifier.width(24.dp))

        // ---------- Параметры (справа) ----------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(sTitle, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                sDesc,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 14.sp,
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
                        }
                        SettingsNote(
                            "Как получить Cookie: откройте сайт трекера в браузере, " +
                                "войдите, нажмите F12 → вкладка Network → кликните любой " +
                                "запрос → в заголовках (Request Headers) найдите Cookie " +
                                "и скопируйте всё значение целиком."
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
                            "Filmix — онлайн-каталог фильмов и сериалов. Нажмите «Подключение " +
                                "аккаунта»: приложение покажет код — введите его на странице " +
                                "filmix.ac/consoles (в браузере, в своём аккаунте Filmix). " +
                                "Приложение само дождётся активации."
                        )
                    }

                    "epg" -> {
                        SettingsRow(
                            title = "Источник программы",
                            value = epgUrl.ifBlank { "teleguide.info (Россия) — по умолчанию" },
                            valueOk = true,
                        ) { openEdit("epg", epgUrl, "URL XMLTV-источника") }
                        SettingsNote(
                            "Программа («Сейчас/Далее») показывается в списке телеканалов " +
                                "и плашкой в плеере; обновляется раз в 6 часов. Можно указать " +
                                "свой XMLTV-источник (файл xmltv.xml.gz), например для другой " +
                                "страны. Оставить пустым — вернётся teleguide.info."
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
}

// ---------- Компоненты ----------

/** Пункт списка разделов слева. */
@Composable
private fun SectionItem(title: String, selected: Boolean, onClick: () -> Unit) {
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
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
                    fontSize = 14.sp,
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
        fontSize = 14.sp,
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
        TvKeyboard(
            onChar = { ch -> onChange(value + ch) },
            onBackspace = { if (value.isNotEmpty()) onChange(value.dropLast(1)) },
            onClear = { onChange("") },
            firstKeyFocus = kbFocus,
        )
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
            .padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onClose) { Text("← Готово") }
            Spacer(Modifier.width(16.dp))
            Text(
                "Скрытые жанры (золотые чипы — исключены)",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp),
        ) {
            Text("Фильмы:", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            GenreChipsMulti(movieGenres, excluded, onToggle)
            Text(
                "Сериалы:",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
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
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (countries.isEmpty()) {
            Text(
                "Список стран загрузится после первого открытия каталогов",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }
        var page by remember { mutableStateOf(0) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Text(
                "${page * COUNTRIES_PAGE + 1}–${page * COUNTRIES_PAGE + countries.drop(page * COUNTRIES_PAGE).take(COUNTRIES_PAGE).size} из ${countries.size}",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 14.sp,
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
                fontSize = 20.sp,
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
                fontSize = 15.sp,
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
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
