package com.torrenttv.app.data

import org.json.JSONObject

/**
 * Безопасное чтение строки из JSON.
 *
 * Ловушка org.json: optString возвращает СТРОКУ "null", если значение
 * в JSON — null (JSONObject.NULL). Такая строка не blank и просачивается
 * в названия/поисковые запросы (баг «q="null"»). Здесь явная проверка isNull.
 */
fun JSONObject.safeString(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

/** Не пустая и не «null» строка — годится для поиска/названия. */
fun String.isRealText(): Boolean =
    isNotBlank() && !equals("null", ignoreCase = true)
