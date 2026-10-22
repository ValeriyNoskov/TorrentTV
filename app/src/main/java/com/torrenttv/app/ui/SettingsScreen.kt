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
import com.torrenttv.app.data.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Настройки поиска: исключаемые жанры (фильмы/сериалы отдельно)
 * и стоп-слова — контент с ними в названии/описании скрывается.
 * Действует на каталоги, тренды, hero-баннер и поиск.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var excluded by remember { mutableStateOf(store.excludedGenreIds()) }
    var keywords by remember { mutableStateOf(store.excludedKeywords()) }
    var movieGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var tvGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var kw by remember { mutableStateOf("") }
    val kbFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            movieGenres = TmdbClient.movieGenres()
            tvGenres = TmdbClient.tvGenres()
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
                        modifier = Modifier.padding(4.dp),
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

        Text(
            text = if (kw.isBlank()) "Новое стоп-слово…" else kw,
            color = if (kw.isBlank()) Color.White.copy(alpha = 0.35f) else Color(0xFFF5B942),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
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
        }

        Spacer(Modifier.height(8.dp))

        TvKeyboard(
            onChar = { kw += it },
            onBackspace = { if (kw.isNotEmpty()) kw = kw.dropLast(1) },
            onClear = { kw = "" },
            firstKeyFocus = kbFocus,
        )
    }
}
