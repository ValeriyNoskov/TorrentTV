package com.torrenttv.app.data

/**
 * TorBox — аналог Real-Debrid: торренты в облако, просмотр по прямой ссылке
 * без скачания на ТВ. Есть бесплатный тариф (лимиты: кеш ~8 ГБ, скорость,
 * очередь) — ключ: torbox.app → Settings → API → Generate API Key.
 *
 * Поток: createtorrent (magnet, multipart!) → poll mylist (ждём
 * download_finished) → requestdl (file_id) → прямая ссылка.
 * Каждый шаг пишется в лог [AppLog] тегом [tb].
 */
object TorBox {

    private const val API = "https://api.torbox.app/v1/api"

    data class TbFile(val id: Int, val name: String, val size: Long)
    data class Resolved(
        val url: String,
        val fileName: String,
        /** Все видеофайлы торрента (естественный порядок) — для списка серий. */
        val files: List<TbFile>,
        /** Индекс выбранного файла в files. */
        val fileIndex: Int,
    )

    private fun log(msg: String) = AppLog.log("tb", msg)

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

    private fun parseMylist(json: String, id: Long): Pair<TbState, List<TbFile>>? = runCatching {
        val root = org.json.JSONObject(json)
        if (!root.optBoolean("success")) return null
        val data = root.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val t = data.getJSONObject(i)
            if (t.optLong("id") != id) continue
            val state = when {
                t.optBoolean("download_finished") || t.optString("download_state") == "completed" ->
                    TbState.DONE
                t.optString("download_state") in setOf("error", "failed", "dead") ->
                    TbState.ERROR
                else -> TbState.WORKING
            }
            val files = mutableListOf<TbFile>()
            val farr = t.optJSONArray("files")
            if (farr != null) {
                for (k in 0 until farr.length()) {
                    val f = farr.getJSONObject(k)
                    val name = (f.optString("name").ifBlank { f.optString("short_name") }).trimStart('/')
                    if (isVideo(name)) files.add(TbFile(f.optInt("id"), name, f.optLong("size")))
                }
            }
            return state to files.sortedWith { x, y -> naturalCompare(x.name, y.name) }
        }
        null
    }.getOrNull()

    private enum class TbState { DONE, WORKING, ERROR }

    /**
     * Полный поток: магнит → прямая ссылка. pick — выбор файла по списку
     * (возвращает индекс; <0 — самый большой видеофайл).
     * timeoutMs: облако качает дольше — возвращаем null (следующий запуск
     * раздачи будет мгновенным: она дозреет в облаке TorBox).
     */
    fun resolve(
        key: String,
        magnet: String,
        pick: (List<TbFile>) -> Int = { -1 },
        timeoutMs: Long = 45_000,
    ): Resolved? {
        log("createtorrent: ${magnet.take(80)}…")
        val createResp = TorrentHttp.postMultipart(
            url = "$API/torrents/createtorrent",
            fields = emptyMap(),
            file = "file" to magnet,
            headers = auth(key),
        ) ?: run {
            log("createtorrent FAIL: TorBox не ответил (сеть/ключ?)")
            return null
        }
        val createObj = runCatching { org.json.JSONObject(createResp) }.getOrNull()
        if (createObj == null || !createObj.optBoolean("success")) {
            log("createtorrent FAIL: ${createResp.take(160)}")
            return null
        }
        val id = when (val d = createObj.opt("data")) {
            is Number -> d.toLong()
            is String -> d.toLongOrNull() ?: return null
            else -> return null
        }
        log("createtorrent ok: id=$id")

        // Ждём готовности раздачи в облаке
        val deadline = System.currentTimeMillis() + timeoutMs
        var state: TbState = TbState.WORKING
        var files: List<TbFile> = emptyList()
        var lastLog = ""
        while (System.currentTimeMillis() < deadline) {
            val listResp = TorrentHttp.get(
                "$API/torrents/mylist?bypass_cache=true",
                auth(key),
            )
            val parsed = listResp?.let { parseMylist(it, id) }
            if (parsed != null) {
                val (st, fs) = parsed
                if (st != state || fs.size != files.size) {
                    val msg = "статус: $st, файлов ${fs.size}"
                    if (msg != lastLog) {
                        log(msg)
                        lastLog = msg
                    }
                }
                state = st
                files = fs
            } else if (listResp != null) {
                // Раздачи нет в списке: TorBox ещё зарегистрировал — ждём
                log("mylist: раздача id=$id пока не видна")
            }
            if (state == TbState.DONE && files.isNotEmpty()) break
            if (state == TbState.ERROR) {
                log("resolve FAIL: раздача в статусе ошибки")
                return null
            }
            Thread.sleep(3000)
        }
        if (state != TbState.DONE || files.isEmpty()) {
            log("resolve FAIL: таймаут ${timeoutMs / 1000}с (облако качает; следующий запуск будет мгновенным)")
            return null
        }

        val idx = pick(files).takeIf { it in files.indices }
            ?: files.indices.maxByOrNull { files[it].size } ?: 0
        val chosen = files[idx]
        log("выбран файл [$idx]: ${chosen.name.take(60)} (${chosen.size / 1024 / 1024}МБ)")

        val dlResp = TorrentHttp.get(
            "$API/torrents/requestdl?torrent_id=$id&file_id=${chosen.id}&zip_link=false",
            auth(key),
        ) ?: run {
            log("requestdl FAIL: TorBox не ответил (бесплатный тариф может урезать прямые ссылки)")
            return null
        }
        val dlObj = runCatching { org.json.JSONObject(dlResp) }.getOrNull()
        if (dlObj == null || !dlObj.optBoolean("success")) {
            log("requestdl FAIL: ${dlResp.take(160)}")
            return null
        }
        val url = dlObj.optString("data")
        if (url.isBlank()) {
            log("requestdl FAIL: пустая ссылка в ответе")
            return null
        }
        log("resolve ok: прямая ссылка готова (${url.length} симв.)")
        return Resolved(url = url, fileName = chosen.name, files = files, fileIndex = idx)
    }
}
