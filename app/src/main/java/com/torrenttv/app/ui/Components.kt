package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.torrenttv.app.data.MediaItem

/** Карточка с постером — фокус берётся с пульта, по ОК открывается карточка. */
@Composable
fun PosterCard(item: MediaItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(170.dp)
            .padding(6.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column {
            if (item.posterUrl.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(Color(0xFF1B2A38)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("нет\nпостера", color = Color.White.copy(alpha = 0.4f), textAlign = TextAlign.Center)
                }
            } else {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp, 6.dp, 8.dp, 0.dp),
            )
            if (item.year.isNotBlank()) {
                Text(
                    text = "${item.year} · ${item.typeLabel}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp, 2.dp, 8.dp, 8.dp),
                )
            }
        }
    }
}

/** Горизонтальная полоса каталога. */
@Composable
fun MediaRow(
    title: String,
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    emptyText: String? = null,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
        )
        if (items.isEmpty() && emptyText != null) {
            Text(
                text = emptyText,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 28.dp)) {
                items(items.size) { i ->
                    PosterCard(items[i]) { onClick(items[i]) }
                }
            }
        }
    }
}
