package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Настройки поиска: исключаемые жанры и страны (фильмы/сериалы отдельно),
 * стоп-слова — контент с ними скрывается.
 * Действует на каталоги, тренды, hero-баннер и поиск.
 */
private const val COUNTRIES_PAGE = 12

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var excluded by remember { mutableStateOf(store.excludedGenreIds()) }
    var excludedCountries by remember { mutableStateOf(store.excludedCountryIds()) }
    var keywords by remember { mutableStateOf(store.excludedKeywords()) }
    var movieGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var tvGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var countries by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var kw by remember { mutableStateOf("") }
    var jackettUrl by remember { mutableStateOf(store.jackettUrl()) }
    var jackettKey by remember { mutableStateOf(store.jackettApiKey()) }
    // Cookie частных трекеров
    var rtCookie by remember { mutableStateOf(store.trackerCookie(PrivateTrackers.RUTRACKER)) }
    var kzCookie by remember { mutableStateOf(store.trackerCookie(PrivateTrackers.KINOZAL)) }
    var nnmCookie by remember { mutableStateOf(store.trackerCookie(PrivateTrackers.NNMCLUB)) }
    // Активное поле для экранной клавиатуры: "kw" | "jurl" | "jkey" | "rt" | "kz" | "nnm"
    var editTarget by remember { mutableStateOf("kw") }
    val kbFocus = remember { FocusRequester() }

    fun editValue(): String = when (editTarget) {
        "jurl" -> jackettUrl
        "jkey" -> jackettKey
        "rt" -> rtCookie
        "kz" -> kzCookie
        "nnm" -> nnmCookie
        else -> kw
    }

    fun editPlaceholder(): String = when (editTarget) {
        "jurl" -> "Адрес Jackett, напр. http://192.168.1.10:9117"
        "jkey" -> "API-ключ Jackett"
        "rt" -> "Cookie для rutracker.org, напр. bb_data=1%7C…"
        "kz" -> "Cookie для kinozal.tv, напр. uid=…; pass=…"
        "nnm" -> "Cookie для nnmclub.to"
        else -> "Новое стоп-слово…"
    }

    fun commitEdit() {
        val v = editValue()
        if (v.isBlank()) return
        when (editTarget) {
            "jurl" -> store.setJackettUrl(v)
            "jkey" -> store.setJackettApiKey(v)
            "rt" -> store.setTrackerCookie(PrivateTrackers.RUTRACKER, v)
            "kz" -> store.setTrackerCookie(PrivateTrackers.KINOZAL, v)
            "nnm" -> store.setTrackerCookie(PrivateTrackers.NNMCLUB, v)
        }
        editTarget = "kw"
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            movieGenres = MediaApi.movieGenres()
            tvGenres = MediaApi.tvGenres()
            countries = MediaApi.countries()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.width(20.dp))
            Text("Настройки поиска", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        // ---------- Жанры ----------
        Text(
            text = "СКРЫВАЕМЫЕ ЖАНРЫ — золотые чипы исключены из каталогов и поиска",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
        )
        Text("Фильмы:", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        GenreChipsMulti(movieGenres, excluded) { id ->
            excluded = if (id in excluded) excluded - id else excluded + id
            store.setExcludedGenreIds(excluded)
        }
        Text("Сериалы:", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        GenreChipsMulti(tvGenres, excluded) { id ->
            excluded = if (id in excluded) excluded - id else excluded + id
            store.setExcludedGenreIds(excluded)
        }

        // ---------- Страны ----------
        Text(
            text = "СТРАНЫ — отмеченные галочкой ☑ страны исключены из каталогов и поиска",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
        )
        if (countries.isEmpty()) {
            Text(
                "Список стран загрузится после первого открытия каталогов",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 14.sp,
            )
        } else {
            var countryPage by remember { mutableStateOf(0) }
            val visible = countries.drop(countryPage * COUNTRIES_PAGE).take(COUNTRIES_PAGE)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${countryPage * COUNTRIES_PAGE + 1}–${countryPage * COUNTRIES_PAGE + visible.size} из ${countries.size}",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    modifier = Modifier.width(110.dp),
                )
                Button(
                    onClick = { if (countryPage > 0) countryPage-- },
                    enabled = countryPage > 0,
                ) { Text("←") }
                Button(
                    onClick = { if ((countryPage + 1) * COUNTRIES_PAGE < countries.size) countryPage++ },
                    enabled = (countryPage + 1) * COUNTRIES_PAGE < countries.size,
                ) { Text("→") }
            }
            LazyRow {
                items(visible.size) { i ->
                    val c = visible[i]
                    CheckChip(c.name, c.id in excludedCountries) {
                        excludedCountries =
                            if (c.id in excludedCountries) excludedCountries - c.id
                            else excludedCountries + c.id
                        store.setExcludedCountryIds(excludedCountries)
                    }
                }
            }
        }

        // ---------- Стоп-слова ----------
        Text(
            text = "СТОП-СЛОВА — не искать контент, где слово есть в названии/описании (нажмите на слово, чтобы убрать)",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
        )
        if (keywords.isEmpty()) {
            Text("Пока нет стоп-слов", color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp)
        } else {
            LazyRow {
                items(keywords.size) { i ->
                    val word = keywords[i]
                    Surface(
                        onClick = {
                            keywords = keywords - word
                            store.setExcludedKeywords(keywords)
                        },
                        modifier = Modifier.padding(4.dp).mouseClick {
                            keywords = keywords - word
                            store.setExcludedKeywords(keywords)
                        },
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
            }
        }

        // ---------- Jackett ----------
        Text(
            text = "JACKETT — дополнительный провайдер автопоиска (опционально). " +
                "Укажите адрес сервера и API-ключ, поиск пойдёт по всем вашим индексерам",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
        val jackettOk = jackettUrl.isNotBlank() && jackettKey.isNotBlank()
        Text(
            text = when {
                jackettOk -> "Подключён: $jackettUrl"
                jackettUrl.isNotBlank() -> "Не хватает API-ключа"
                else -> "Не настроен (работает встроенный apibay + Torrentio)"
            },
            color = if (jackettOk) Color(0xFF9CCC65) else Color.White.copy(alpha = 0.4f),
            fontSize = 14.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { editTarget = "jurl"; kw = ""; kbFocus.requestFocus() }) {
                Text(if (jackettUrl.isBlank()) "🔗 Указать адрес" else "🔗 Изменить адрес")
            }
            Spacer(Modifier.width(16.dp))
            Button(onClick = { editTarget = "jkey"; kw = ""; kbFocus.requestFocus() }) {
                Text(if (jackettKey.isBlank()) "🔑 Указать ключ" else "🔑 Изменить ключ")
            }
            if (jackettOk || jackettUrl.isNotBlank()) {
                Spacer(Modifier.width(16.dp))
                Button(onClick = {
                    jackettUrl = ""; jackettKey = ""
                    store.setJackettUrl(""); store.setJackettApiKey("")
                }) { Text("🗑 Убрать") }
            }
        }

        // ---------- Частные трекеры ----------
        Text(
            text = "ЧАСТНЫЕ ТРЕКЕРЫ — прямой поиск и скачивание без Jackett. " +
                "Вставьте строку Cookie из браузера: откройте сайт, войдите, F12 → Network → " +
                "любой запрос → заголовок Cookie (всё значение целиком)",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            for ((id, title, _) in PrivateTrackers.ALL) {
                val configured = store.trackerCookie(id).isNotBlank()
                Button(onClick = { editTarget = id; kw = ""; kbFocus.requestFocus() }) {
                    Text(if (configured) "✓ $title" else "🔑 $title")
                }
                Spacer(Modifier.width(12.dp))
            }
        }
        Text(
            text = "Подключено трекеров: " +
                PrivateTrackers.ALL.count { (id, _, _) -> store.trackerCookie(id).isNotBlank() } +
                " из ${PrivateTrackers.ALL.size}",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            text = if (editValue().isBlank()) editPlaceholder() else editValue(),
            color = when {
                editTarget != "kw" && editTarget.isNotEmpty() -> Color(0xFF64B5F6)
                kw.isBlank() -> Color.White.copy(alpha = 0.35f)
                else -> Color(0xFFF5B942)
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            if (editTarget == "kw") {
                Button(onClick = {
                    val w = kw.trim()
                    if (w.isNotBlank() && w !in keywords) {
                        keywords = keywords + w
                        store.setExcludedKeywords(keywords)
                        kw = ""
                    }
                }) { Text("➕ Добавить") }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { kw = "" }) { Text("Очистить поле") }
            } else {
                Button(onClick = { commitEdit() }) { Text("💾 Сохранить") }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { editTarget = "kw" }) { Text("Отмена") }
            }
        }

        Spacer(Modifier.height(8.dp))

        TvKeyboard(
            onChar = { ch ->
                when (editTarget) {
                    "jurl" -> jackettUrl += ch
                    "jkey" -> jackettKey += ch
                    "rt" -> rtCookie += ch
                    "kz" -> kzCookie += ch
                    "nnm" -> nnmCookie += ch
                    else -> kw += ch
                }
            },
            onBackspace = {
                when (editTarget) {
                    "jurl" -> if (jackettUrl.isNotEmpty()) jackettUrl = jackettUrl.dropLast(1)
                    "jkey" -> if (jackettKey.isNotEmpty()) jackettKey = jackettKey.dropLast(1)
                    "rt" -> if (rtCookie.isNotEmpty()) rtCookie = rtCookie.dropLast(1)
                    "kz" -> if (kzCookie.isNotEmpty()) kzCookie = kzCookie.dropLast(1)
                    "nnm" -> if (nnmCookie.isNotEmpty()) nnmCookie = nnmCookie.dropLast(1)
                    else -> if (kw.isNotEmpty()) kw = kw.dropLast(1)
                }
            },
            onClear = {
                when (editTarget) {
                    "jurl" -> jackettUrl = ""
                    "jkey" -> jackettKey = ""
                    "rt" -> rtCookie = ""
                    "kz" -> kzCookie = ""
                    "nnm" -> nnmCookie = ""
                    else -> kw = ""
                }
            },
            firstKeyFocus = kbFocus,
        )
    }
}
