package com.torrenttv.app.data

import java.io.File

/**
 * Атомарная запись файла: сначала во временный, затем rename.
 * Краш/kill во время записи не оставляет битый JSON/кеш (ревью данных:
 * «writeText = truncate+write, краш обнулял дневной кеш КП»).
 */
fun writeAtomic(file: File, content: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(content)
    if (tmp.renameTo(file)) return
    // На части файловых систем rename поверх существующего не срабатывает
    file.delete()
    if (tmp.renameTo(file)) return
    tmp.delete()
    throw java.io.IOException("не удалось записать ${file.name}")
}
