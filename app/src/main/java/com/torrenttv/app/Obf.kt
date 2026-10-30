package com.torrenttv.app

/**
 * Расшифровка вшитых ключей: пары «соль + applicationId» больше нет в открытом
 * виде рядом с секретами — ключ собирается в BuildConfig из тех же компонент,
 * но в другом месте APK. Защита от jadx-поиска по строкам и скриптов-грепперов
 * (в APK нет ни одного ключа в открытом тексте), не от целевого реверса.
 */
object Obf {

    fun decode(hex: String, key: String): String {
        if (hex.isEmpty() || key.isEmpty()) return ""
        return try {
            val chars = CharArray(hex.length / 2)
            for (i in chars.indices) {
                val b = hex.substring(i * 2, i * 2 + 2).toInt(16)
                val c = key[i % key.length].code
                chars[i] = (b xor c).toChar()
            }
            String(chars)
        } catch (e: Exception) {
            ""
        }
    }
}
