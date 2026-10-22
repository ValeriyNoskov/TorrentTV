package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.torrenttv.app.data.Genre
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.MediaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Каталог с табом: Фильмы (isMovies = true) или Сериалы.
 * Полоса жанров + полки «Популярные» и «Топ» с бесконечной подгрузкой.
 */
@Composable
fun CatalogScreen(isMovies: Boolean, onOpenDetails: (MediaItem) -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    val noun = if (isMovies) "фильмы" else "сериалы"
    val title = if (isMovies) "Фильмы" else "Сериалы"

    var genres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var selected by remember { mutableStateOf<Genre?>(null) }

    LaunchedEffect(isMovies) {
        genres = withContext(Dispatchers.IO) {
            if (isMovies) MediaApi.movieGenres() else MediaApi.tvGenres()
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
    ) {
        item {
            Text(
                text = title,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
            )
        }
        if (!MediaApi.isConfigured) {
            item {
                Text(
                    text = "⚠ Метаданные не настроены. Получите бесплатный ключ на kinopoisk.dev и укажите kpApiKey= в gradle.properties.",
                    color = Color(0xFFFFD54F),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
        item {
            GenreChips(genres, selected) { selected = it }
        }
        selected?.let { g ->
            item {
                PagedMediaRow(
                    title = "$title · ${g.name}",
                    loader = { page ->
                        if (isMovies) MediaApi.moviesByGenre(g.id, page)
                        else MediaApi.tvByGenre(g.id, page)
                    },
                    onOpen = onOpenDetails,
                    keyTag = g.id,
                    filterFn = { store.isAllowed(it) },
                )
            }
        }
        item {
            PagedMediaRow(
                title = "Популярные $noun",
                loader = { page ->
                    if (isMovies) MediaApi.popularMovies(page) else MediaApi.popularTv(page)
                },
                onOpen = onOpenDetails,
                filterFn = { store.isAllowed(it) },
            )
        }
        item {
            PagedMediaRow(
                title = "Топ $noun",
                loader = { page ->
                    if (isMovies) MediaApi.topRatedMovies(page) else MediaApi.topRatedTv(page)
                },
                onOpen = onOpenDetails,
                filterFn = { store.isAllowed(it) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
