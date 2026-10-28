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

    // 20 ГБ — слишком много для ТВ: владелец обнаружил 5 ГБ «непонятного»
    // кеша. 6 ГБ хватает на 2-3 серии 720p; недосмотренное живёт до лимита
    private const val LIMIT_BYTES = 6L * 1024 * 1024 * 1024
    private const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024 // держим ≥2 ГБ свободно
    private const val ACTIVE_MS = 6L * 3600 * 1000 // «активная» папка: 6 часов

    /**
     * Освободить место: root — папка downloads (можно передать её напрямую
     * rootDir — для чистки при старте приложения, без нового стрима).
     * Лимит соблюдается ВСЕГДА: если «активных» (моложе 6ч) папок слишком
     * много, старейшие из них тоже удаляются — раньше при активном тестировании
     * раздач подряд кеш уходил за 12 ГБ, потому что активные были неприкосновенны.
     */
    fun enforceLimit(currentDir: File? = null, rootDir: File? = null) {
        val root = rootDir ?: currentDir?.parentFile ?: return
        val dirs = root.listFiles { f -> f.isDirectory } ?: return
        val now = System.currentTimeMillis()

        fun sizeOf(dir: File): Long =
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

        val sizes = dirs.associateWith { sizeOf(it) }
        var total = sizes.values.sum()
        var free = root.usableSpace

        if (total <= LIMIT_BYTES && free >= MIN_FREE_BYTES) return

        // 1) старейшие неактивные (не трогаем текущую и свежие за 6ч)
        val candidates = dirs
            .filter { it != currentDir && now - it.lastModified() > ACTIVE_MS }
            .sortedBy { it.lastModified() }
        // 2) не хватило — в ход идут и «активные» (кроме текущей), старейшие первыми
        val queue = if (total > LIMIT_BYTES) {
            candidates + dirs
                .filter { it != currentDir && it !in candidates }
                .sortedBy { it.lastModified() }
        } else {
            candidates
        }
        var freed = 0L
        queue.forEach { d ->
            if (total - freed > LIMIT_BYTES || free + freed < MIN_FREE_BYTES) {
                freed += sizes[d] ?: 0
                runCatching { d.deleteRecursively() }
            }
        }
    }

    /** Досмотрено до конца? (позиция ≥ 95%) */
    fun watchedToEnd(posMs: Long, durMs: Long): Boolean =
        durMs > 0 && posMs >= durMs * 95 / 100
}
