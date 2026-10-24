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
            .crossfade(true)
            .build()
}
