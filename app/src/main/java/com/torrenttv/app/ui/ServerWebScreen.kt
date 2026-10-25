package com.torrenttv.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler

/**
 * Встроенный браузер для веб-интерфейса сервера (TorrServe и т.п.) —
 * управление с ПУЛЬТА: ↑/↓/←/→ навигация по элементам и скролл, OK — клик,
 * BACK — назад по истории (или закрыть). WebView сам обрабатывает DPAD.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ServerWebScreen(
    url: String,
    title: String,
    onBack: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0E1116))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Мобильный UA: TorrServe UI адаптирован под узкие экраны —
                    // на ТВ-широком читабельнее мобильная вёрстка
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // Серверный UI мелкий на ТВ — стартуем вписанным
                    setInitialScale(150)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            canGoBack = view?.canGoBack() ?: false
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { view ->
                // canGoBack обновляем при каждом кадре дёшево
                canGoBack = view.canGoBack()
            },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC0E1116))
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                ("← " + title + " ") .repeat(1) + "· ↑↓←→ — навигация, OK — выбрать, BACK — " +
                    if (canGoBack) "назад" else "закрыть",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
    }
}
