package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.MediaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Карточка фильма/сериала: описание, избранное, magnet, просмотр и «Похожее». */
@Composable
fun DetailsScreen(
    item: MediaItem,
    onBack: () -> Unit,
    onWatch: () -> Unit = {},
    onEnterMagnet: () -> Unit = {},
    onAutoSearch: () -> Unit = {},
    onOpenDetails: (MediaItem) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    var fav by remember { mutableStateOf(store.isFavorite(item)) }
    var magnet by remember { mutableStateOf(store.getMagnet(item) != null) }
    var status by remember { mutableStateOf("") }
    var similar by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    LaunchedEffect(item) {
        similar = withContext(Dispatchers.IO) { MediaApi.similar(item) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(32.dp),
    ) {
        Row(Modifier.weight(1f)) {
            // Постер
            if (item.posterUrl.isBlank()) {
                Box(
                    Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF1F2430), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("нет постера", color = Color.White.copy(alpha = 0.4f)) }
            } else {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight(),
                    contentScale = ContentScale.Crop,
                )
            }

            Spacer(Modifier.width(28.dp))

            Column {
                Text(item.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = buildString {
                        append(item.typeLabel)
                        if (item.year.isNotBlank()) append(" · ${item.year}")
                    },
                    color = Color(0xFFF5B942),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (item.overview.isNotBlank()) {
                    Text(
                        text = item.overview,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        maxLines = 8,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row {
                    Button(onClick = {
                        if (magnet) {
                            // Фиксируем начало просмотра и открываем плеер
                            store.saveProgress(item, positionSec = 0, durationSec = 0)
                            onWatch()
                        } else {
                            status = "Сначала укажите magnet-ссылку торрента"
                        }
                    }) { Text("▶ Смотреть") }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = onAutoSearch) { Text("🔍 Автопоиск") }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = onEnterMagnet) {
                        Text(if (magnet) "🔗 Заменить magnet" else "🔗 Указать magnet")
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = { fav = store.toggleFavorite(item) }) {
                        Text(if (fav) "★ В избранном" else "☆ В избранное")
                    }
                }

                if (status.isNotBlank()) {
                    Text(status, color = Color(0xFFFFD54F), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        // Похожее
        if (similar.isNotEmpty()) {
            Box(Modifier.weight(0.45f)) {
                MediaRow("Похожее", similar, onOpenDetails)
            }
        }

        Button(onClick = onBack) { Text("← Назад") }
    }
}
