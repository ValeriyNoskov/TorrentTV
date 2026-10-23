package com.torrenttv.app.torrent

import java.io.File

/**
 * Политика хранения скачанного торрента (кэш):
 *  - недосмотренный фильм остаётся на диске → «Продолжить просмотр» стартует мгновенно;
 *  - досмотренный до конца удаляется сразу (вызывает плеер);
 *  - перед новым стримом чистим старьё: суммарный кэш не больше лимита,
 *    при нехватке места — самые старые папки первыми (LRU);
 *  - активное (изменялось за последние 12 часов) и текущая папка не трогаются.
 */
object TorrentCache {

    private const val LIMIT_BYTES = 20L * 1024 * 1024 * 1024 // 20 ГБ
    private const val MIN_FREE_BYTES = 3L * 1024 * 1024 * 1024 // держим ≥3 ГБ свободно
    private const val ACTIVE_MS = 12L * 3600 * 1000 // 12 часов

    /** Освободить место под новый стрим: root — папка downloads. */
    fun enforceLimit(currentDir: File? = null) {
        val root = currentDir?.parentFile ?: return
        val dirs = root.listFiles { f -> f.isDirectory } ?: return
        val now = System.currentTimeMillis()

        fun sizeOf(dir: File): Long =
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

        val sizes = dirs.associateWith { sizeOf(it) }
        var total = sizes.values.sum()
        var free = root.usableSpace

        if (total <= LIMIT_BYTES && free >= MIN_FREE_BYTES) return

        // Кандидаты на удаление: не текущая и не активная (12ч), старые первыми
        val candidates = dirs
            .filter { it != currentDir && now - it.lastModified() > ACTIVE_MS }
            .sortedBy { it.lastModified() }

        var freed = 0L
        for (d in candidates) {
            if (total - freed <= LIMIT_BYTES && free + freed >= MIN_FREE_BYTES) break
            freed += sizes[d] ?: 0
            runCatching { d.deleteRecursively() }
        }
    }

    /** Досмотрено до конца? (позиция ≥ 95%) */
    fun watchedToEnd(posMs: Long, durMs: Long): Boolean =
        durMs > 0 && posMs >= durMs * 95 / 100
}
