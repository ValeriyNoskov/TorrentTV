package com.torrenttv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Глобальный ImageLoader для Coil: постеры и фоны кэшируются на диск
 * (до 250 МБ) и в память — каталог листается мгновенно, повторные
 * запросы к КП/TMDB за одними и теми же картинками не тратят лимит API.
 * respectCacheHeaders(false) — кэшируем независимо от заголовков серверов.
 */
class TorrentTVApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15) // ~15% памяти приложения на постеры
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(500L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            // Wikimedia (постеры из Википедии, как у «Спецов») отдаёт 403 на
            // дефолтный UA okhttp — маскируемся под браузер
            .okHttpClient {
                okhttp3.OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                                .build(),
                        )
                    }
                    .build()
            }
            // Лог ошибок ВСЕХ картинок (постеры/бэкдропы/логотипы): без него
            // «слетел постер» не диагностируется — в логе просто тишина
            .eventListener(object : coil.EventListener {
                override fun onError(request: coil.request.ImageRequest, result: coil.request.ErrorResult) {
                    com.torrenttv.app.data.AppLog.log(
                        "posters",
                        "картинка FAIL: ${request.data} — ${result.throwable?.message ?: "без ошибки"}",
                    )
                }
            })
            .crossfade(true)
            .build()
}
