package com.torrenttv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.TorrentResult
import com.torrenttv.app.data.TorrentSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Автопоиск торрентов по названию карточки: «название + год»,
 * сортировка по сидам. Выбор результата привязывает magnet к карточке.
 */
@Composable
fun TorrentSearchScreen(item: MediaItem, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }

    var status by remember { mutableStateOf("Ищу торренты…") }
    var results by remember { mutableStateOf<List<TorrentResult>>(emptyList()) }

    val query = remember(item) {
        if (item.year.isNotBlank()) "${item.title} ${item.year}" else item.title
    }

    LaunchedEffect(item) {
        val found = withContext(Dispatchers.IO) {
            TorrentSearch.search(query, isTvShow = item.type != "movie", excludedKeywords = store.excludedKeywords().toSet())
        }
        results = found
        status = if (found.isEmpty()) {
            "Ничего не найдено — попробуйте «Указать magnet» вручную"
        } else {
            "Найдено: ${found.size}. Выберите раздачу (больше сидов — быстрее)"
        }
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(32.dp),
    ) {
        Text("🔍 Автопоиск", color = Color(0xFFF5B942), fontSize = 16.sp)
        Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(status, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))

        Spacer(Modifier.height(18.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(results) { r ->
                Surface(
                    onClick = {
                        store.saveMagnet(item, TorrentSearch.toMagnet(r))
                        onDone()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = clickableSurfaceShape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFF1F2430),
                        contentColor = Color.White,
                        focusedContainerColor = Color(0xFF31435A),
                        focusedContentColor = Color.White,
                        pressedContainerColor = Color(0xFF273548),
                        pressedContentColor = Color.White,
                        disabledContainerColor = Color(0xFF1F2430),
                        disabledContentColor = Color.White,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text(r.name, color = Color.White, fontSize = 17.sp, maxLines = 2)
                        Row(Modifier.padding(top = 4.dp)) {
                            Text(
                                text = "↑ ${r.seeds}",
                                color = Color(0xFF9CCC65),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = if (r.sizeMb >= 1024) "%.1f ГБ".format(r.sizeMb / 1024f) else "${r.sizeMb} МБ",
                                color = Color(0xFFF5B942),
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row {
            androidx.tv.material3.Button(onClick = onBack) { Text("← Назад") }
        }
    }
}

/** Экранирование для запроса (на будущее — URLEncoder используется в TorrentSearch). */
private fun encodeQuery(q: String): String = URLEncoder.encode(q, "UTF-8")
