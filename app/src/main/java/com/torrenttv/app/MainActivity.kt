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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import com.torrenttv.app.update.UpdateManager

/**
 * Главный экран приложения. Пока — каркас: заголовок и подсказка.
 * При старте проверяет обновления через GitHub Releases.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HomeScreen(currentVersion = BuildConfig.VERSION_NAME)
            }
        }
    }
}

@Composable
fun HomeScreen(currentVersion: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var status by remember { mutableStateOf("") }

    // Проверка обновлений при старте
    LaunchedEffect(Unit) {
        UpdateManager.checkForUpdate(currentVersion) { info -> updateInfo = info }
    }

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
            text = "v$currentVersion — каркас готов. Управление с пульта (D-pad).",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 20.sp,
        )
        Text(
            text = status,
            color = Color(0xFFFFD54F),
            fontSize = 16.sp,
        )

        updateInfo?.let { info ->
            Surface(
                modifier = Modifier.padding(top = 24.dp),
                color = Color(0xFF1B2A38),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Доступно обновление v${info.version}",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (info.notes.isNotBlank()) {
                        Text(
                            text = info.notes,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                        )
                    }
                    Button(
                        onClick = {
                            UpdateManager.downloadAndInstall(
                                context,
                                info,
                                onProgress = { status = it },
                                onError = { status = it },
                            )
                        },
                    ) {
                        Text("Обновить")
                    }
                }
            }
        }
    }
}
