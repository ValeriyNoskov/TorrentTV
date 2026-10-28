package com.torrenttv.app.data

/**
 * Real-Debrid: сервис-ускоритель — отдаёт magnet/info-hash как готовую
 * прямую HTTP-ссылку со своего кеша. Просмотр без скачания на ТВ: мгновенный
 * старт, перемотка без буферизации. Ключ — в Настройках («Real-Debrid ключ»).
 *
 * Поток: addMagnet → info (файлы) → selectFiles (один видеофайл) →
 * ждём status=downloaded (раздача уже в кеше RD — мгновенно; иначе RD качает
 * сам) → unrestrict/link → прямая ссылка для OnlinePlayerScreen.
 * Каждый шаг пишется в лог [AppLog] — диагностика без гаданий.
 */
object RealDebrid {

    private const val TAG = "rd"
    private const val API = "https://api.real-debrid.com/rest/1.0"

    data class RdFile(val id: Int, val name: String, val size: Long)
    data class Resolved(
        val url: String,
        val fileName: String,
        /** Все видеофайлы торрента (естественный порядок) — для списка серий. */
        val files: List<RdFile>,
        /** Индекс выбранного файла в files. */
        val fileIndex: Int,
    )

    private fun log(msg: String) = AppLog.log(TAG, msg)

    private fun auth(key: String) = mapOf("Authorization" to "Bearer $key")

    private fun isVideo(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in
            setOf("mkv", "mp4", "avi", "ts", "m2ts", "mov", "webm")

    /** Человеческий порядок: S01E02 раньше S01E10, «Часть 2» раньше «Часть 10». */
    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ia = i
                while (ia < a.length && a[ia].isDigit()) ia++
                var jb = j
                while (jb < b.length && b[jb].isDigit()) jb++
                val cmp = a.substring(i, ia).toLong().compareTo(b.substring(j, jb).toLong())
                if (cmp != 0) return cmp
                i = ia
                j = jb
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return a.length - b.length
    }

    private fun parseFiles(json: String): List<RdFile> = runCatching {
        val files = org.json.JSONObject(json).getJSONArray("files")
        (0 until files.length()).mapNotNull { i ->
            val f = files.getJSONObject(i)
            val name = f.getString("path").trimStart('/')
            if (isVideo(name)) RdFile(f.getInt("id"), name, f.getLong("bytes")) else null
        }
    }.getOrDefault(emptyList())

    /** Файлы торрента в естественном порядке. */
    fun files(key: String, id: String): List<RdFile> {
        val resp = TorrentHttp.get("$API/torrents/info/$id", auth(key)) ?: run {
            log("info/$id: RD не ответил")
            return emptyList()
        }
        val fs = parseFiles(resp).sortedWith { x, y -> naturalCompare(x.name, y.name) }
        log("info/$id: видеофайлов ${fs.size}: " + fs.take(5).joinToString { f -> f.name.take(40) })
        return fs
    }

    /**
     * Полный поток: магнит → прямая ссылка. pick — выбор файла по списку
     * (возвращает индекс; <0 — самый большой видеофайл).
     * timeoutMs: если RD качает раздачу дольше — возвращаем null (пользователь
     * продолжит торрентом; в облаке раздача дозреет — в следующий раз будет
     * мгновенно).
     */
    fun resolve(
        key: String,
        magnet: String,
        pick: (List<RdFile>) -> Int = { -1 },
        timeoutMs: Long = 45_000,
    ): Resolved? {
        log("addMagnet: ${magnet.take(80)}…")
        val addResp = TorrentHttp.postForm(
            url = "$API/torrents/addMagnet",
            fields = mapOf("magnet" to magnet),
            headers = auth(key),
        ) ?: run {
            log("addMagnet FAIL: RD не ответил (сеть/ключ/подписка?)")
            return null
        }
        val id = runCatching {
            org.json.JSONObject(addResp).getString("id")
        }.getOrElse {
            log("addMagnet FAIL: неожиданный ответ: ${addResp.take(120)}")
            return null
        }
        log("addMagnet ok: id=$id")

        val fs = files(key, id)
        if (fs.isEmpty()) {
            log("resolve FAIL: видеофайлов нет")
            return null
        }
        val idx = pick(fs).takeIf { it in fs.indices } ?: fs.indices.maxByOrNull { fs[it].size } ?: 0
        val chosen = fs[idx]
        log("выбран файл [$idx]: ${chosen.name.take(60)} (${chosen.size / 1024 / 1024}МБ)")

        TorrentHttp.postForm(
            url = "$API/torrents/selectFiles/$id",
            fields = mapOf("files" to chosen.id.toString()),
            headers = auth(key),
        ) ?: log("selectFiles: RD не ответил (продолжаю — может уже готово)")
        log("selectFiles ok: file id=${chosen.id}")

        // Ждём готовности: RD либо отдаёт из кеша сразу, либо качает сам
        val deadline = System.currentTimeMillis() + timeoutMs
        var link: String? = null
        var lastStatus = ""
        while (System.currentTimeMillis() < deadline) {
            val info = TorrentHttp.get("$API/torrents/info/$id", auth(key)) ?: break
            val obj = runCatching { org.json.JSONObject(info) }.getOrNull() ?: break
            val status = obj.optString("status")
            if (status != lastStatus) {
                lastStatus = status
                val prog = obj.optDouble("progress", 0.0)
                log("статус: $status ${(prog * 100).toInt()}%")
            }
            when (status) {
                "downloaded" -> {
                    link = obj.optJSONArray("links")?.optString(0)
                    if (link.isNullOrBlank()) {
                        log("downloaded, но links пуст — unrestrict невозможен")
                        link = null
                    }
                    break
                }
                "error", "magnet_error", "dead" -> {
                    log("resolve FAIL: статус RD=$status")
                    return null
                }
            }
            Thread.sleep(2500)
        }
        val rdLink = link ?: run {
            log("resolve FAIL: таймаут ${timeoutMs / 1000}с (RD качает раздачу; в следующий раз будет мгновенно)")
            return null
        }

        log("unrestrict…")
        val unResp = TorrentHttp.postForm(
            url = "$API/unrestrict/link",
            fields = mapOf("link" to rdLink),
            headers = auth(key),
        ) ?: run {
            log("unrestrict FAIL: RD не ответил")
            return null
        }
        val url = runCatching {
            org.json.JSONObject(unResp).getString("download")
        }.getOrElse {
            log("unrestrict FAIL: ${unResp.take(120)}")
            return null
        }
        log("resolve ok: прямая ссылка готова (${url.length} симв.)")
        return Resolved(url = url, fileName = chosen.name, files = fs, fileIndex = idx)
    }
}
