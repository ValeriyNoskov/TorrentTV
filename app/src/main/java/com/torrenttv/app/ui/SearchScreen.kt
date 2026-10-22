package com.torrenttv.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.torrenttv.app.data.MediaItem
import com.torrenttv.app.data.TmdbClient
import kotlinx.coroutines.delay

// Раскладка экранной клавиатуры (ЙЦУКЕН)
private val KEY_ROWS = listOf(
    listOf('Й', 'Ц', 'У', 'К', 'Е', 'Н', 'Г', 'Ш', 'Щ', 'З', 'Х', 'Ъ'),
    listOf('Ф', 'Ы', 'В', 'А', 'П', 'Р', 'О', 'Л', 'Д', 'Ж', 'Э'),
    listOf('Я', 'Ч', 'С', 'М', 'И', 'Т', 'Ь', 'Б', 'Ю'),
    listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0'),
    listOf('-', ' '),
)

/**
 * Экран поиска: экранная клавиатура для пульта + голосовой ввод микрофоном.
 * Результаты ищутся в TMDB (фильмы + сериалы) с дебаунсом 500 мс.
 */
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenDetails: (MediaItem) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val firstKeyFocus = remember { FocusRequester() }

    // Голосовой ввод: системный распознаватель речи
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val text = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                query = text.trim()
                status = ""
            }
        }
    }

    fun startVoice() {
        status = ""
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите название фильма или сериала")
            }
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            status = "Голосовой ввод недоступен на этом устройстве — используйте клавиатуру"
        }
    }

    // Поиск с дебаунсом
    LaunchedEffect(query) {
        if (query.length >= 2) {
            loading = true
            delay(500)
            results = TmdbClient.search(query)
            loading = false
        } else {
            results = emptyList()
            loading = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(24.dp),
    ) {
        // ---------- Верхняя строка ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.width(16.dp))
            Text(
                text = if (query.isBlank()) "Введите название…" else query,
                color = if (query.isBlank()) Color.White.copy(alpha = 0.4f) else Color(0xFF4FC3F7),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { startVoice() }) { Text("🎤 Голос") }
        }
        if (status.isNotBlank()) {
            Text(status, color = Color(0xFFFFD54F), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }

        // ---------- Результаты ----------
        if (results.isNotEmpty()) {
            Text(
                text = if (loading) "Результаты (обновление…)" else "Найдено: ${results.size}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            LazyRow(contentPadding = PaddingValues(vertical = 6.dp)) {
                items(results.size) { i -> PosterCard(results[i]) { onOpenDetails(results[i]) } }
            }
        } else if (loading) {
            Text("Ищем…", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
        }

        Spacer(Modifier.height(8.dp))

        // ---------- Экранная клавиатура ----------
        TvKeyboard(
            onChar = { query += it },
            onBackspace = { if (query.isNotEmpty()) query = query.dropLast(1) },
            onClear = { query = "" },
            firstKeyFocus = firstKeyFocus,
        )
    }
}

/** TV-клавиатура: сетка фокусируемых клавиш, навигация с пульта. */
@Composable
fun TvKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    firstKeyFocus: FocusRequester? = null,
) {
    LaunchedEffect(Unit) { firstKeyFocus?.requestFocus() }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        KEY_ROWS.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rowIndex == 3) {
                    KeyboardKey('␣', wide = true, onClick = { onChar(' ') })
                }
                row.forEachIndexed { colIndex, ch ->
                    KeyboardKey(
                        ch,
                        focusRequester = if (rowIndex == 0 && colIndex == 0) firstKeyFocus else null,
                    ) { onChar(ch) }
                }
                if (rowIndex == 3) {
                    KeyboardKey('⌫', wide = true, onClick = onBackspace)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyboardKey('О', wide = true, label = "Очистить", onClick = onClear)
        }
    }
}

@Composable
private fun KeyboardKey(
    symbol: Char,
    wide: Boolean = false,
    label: String? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(if (wide) 140.dp else 58.dp)
            .height(52.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1B2A38),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label ?: symbol.toString(),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
