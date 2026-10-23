package com.torrenttv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.torrenttv.app.data.LocalStore
import com.torrenttv.app.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ввод magnet-ссылки для карточки (экранная клавиатура).
 * Сохранённая ссылка привязывается к карточке и переиспользуется кнопкой «Смотреть».
 */
@Composable
fun MagnetEntryScreen(item: MediaItem, onDone: () -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalStore(context) }
    var magnet by remember { mutableStateOf(store.getMagnet(item) ?: "") }
    var error by remember { mutableStateOf("") }
    val kbFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(24.dp),
    ) {
        Text(item.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "Укажите magnet-ссылку торрента с этим ${item.typeLabel.lowercase()}ом",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            text = "magnet:?xt=… или прямая ссылка на .torrent файл (http…torrent)",
            color = if (magnet.isBlank()) Color.White.copy(alpha = 0.35f) else Color(0xFFF5B942),
            fontSize = 18.sp,
            maxLines = 3,
            modifier = Modifier.padding(top = 10.dp),
        )

        if (error.isNotBlank()) {
            Text(error, color = Color(0xFFEF9A9A), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Button(
                onClick = {
                    val t = magnet.trim()
                    when {
                        t.startsWith("magnet:") -> {
                            store.saveMagnet(item, t)
                            onDone()
                        }
                        t.startsWith("http") -> {
                            error = "Скачиваю .torrent…"
                            scope.launch {
                                val m = withContext(Dispatchers.IO) {
                                    val bytes = com.torrenttv.app.data.TorrentHttp.getBytes(t)
                                    bytes?.let { com.torrenttv.app.data.TorrentFileParser.parse(it) }
                                }
                                if (m != null) {
                                    store.saveMagnet(item, com.torrenttv.app.data.TorrentFileParser.toMagnet(m))
                                    onDone()
                                } else {
                                    error = "Не удалось прочитать .torrent — проверьте ссылку"
                                }
                            }
                        }
                        else -> error = "Ссылка должна начинаться с «magnet:» или «http»"
                    }
                },
            ) { Text("Сохранить и смотреть") }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onBack) { Text("← Назад") }
        }

        Spacer(Modifier.height(8.dp))

        TvKeyboard(
            onChar = { magnet += it },
            onBackspace = { if (magnet.isNotEmpty()) magnet = magnet.dropLast(1) },
            onClear = { magnet = "" },
            firstKeyFocus = kbFocus,
        )
    }
}
