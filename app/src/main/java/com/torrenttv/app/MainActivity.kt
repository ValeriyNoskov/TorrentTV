package com.torrenttv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Главный экран приложения. Пока — каркас: заголовок и подсказка.
 * Далее сюда ляжет экран поиска + список раздач + плеер.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HomeScreen()
            }
        }
    }
}

@Composable
fun HomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "TorrentTV",
            color = Color(0xFF4FC3F7),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Каркас приложения готов. Управление с пульта (D-pad).",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 20.sp,
        )
    }
}
