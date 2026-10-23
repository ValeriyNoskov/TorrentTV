package com.torrenttv.app

import com.torrenttv.app.data.EpgService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgServiceTest {

    // ---------- parseXmltvTime ----------

    @Test
    fun `xmltv time with offset converts to epoch`() {
        // 2026-10-23 12:00:00 +0300 == 2026-10-23 09:00:00 UTC
        val expected = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, 9, 23, 9, 0, 0)
        }.timeInMillis
        assertEquals(expected, EpgService.parseXmltvTime("20261023120000 +0300"))
    }

    @Test
    fun `xmltv time negative offset`() {
        // 2026-01-01 00:00:00 -0500 == 05:00 UTC
        val expected = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, 0, 1, 5, 0, 0)
        }.timeInMillis
        assertEquals(expected, EpgService.parseXmltvTime("20260101000000 -0500"))
    }

    @Test
    fun `xmltv time without offset is treated as UTC`() {
        val expected = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, 9, 23, 12, 0, 0)
        }.timeInMillis
        assertEquals(expected, EpgService.parseXmltvTime("20261023120000"))
    }

    @Test
    fun `xmltv time garbage returns zero`() {
        assertEquals(0L, EpgService.parseXmltvTime(null))
        assertEquals(0L, EpgService.parseXmltvTime(""))
        assertEquals(0L, EpgService.parseXmltvTime("abc"))
    }

    // ---------- normalizeName ----------

    @Test
    fun `normalize strips quality and brackets`() {
        assertEquals("первыйканал", EpgService.normalizeName("Первый канал (1080p)"))
        assertEquals("домашний", EpgService.normalizeName("Домашний [Not 24/7]"))
    }

    @Test
    fun `normalize is case and punctuation insensitive`() {
        assertEquals(EpgService.normalizeName("Матч! Арена"), EpgService.normalizeName("матч арена"))
        assertEquals(EpgService.normalizeName("Ёлки-Палки"), EpgService.normalizeName("елки палки"))
    }

    // ---------- parseXmltv (локальный XMLTV) ----------

    private fun xmltvSample() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="ChannelOne.ru"><display-name>Первый канал</display-name></channel>
          <channel id="Domashniy.ru"><display-name>Домашний</display-name></channel>
          <channel id="Unknown.tv"><display-name>Чужой</display-name></channel>
          <programme start="20261023120000 +0300" stop="20261023130000 +0300" channel="ChannelOne.ru">
            <title>Новости</title>
          </programme>
          <programme start="20261023130000 +0300" stop="20261023140000 +0300" channel="ChannelOne.ru">
            <title>Время покажет</title>
          </programme>
          <programme start="20261023120000 +0300" stop="20261023150000 +0300" channel="Domashniy.ru">
            <title>Шоу</title>
          </programme>
          <programme start="20261023120000 +0300" stop="20261023130000 +0300" channel="Unknown.tv">
            <title>Не наш канал</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun channelsForTest() = listOf(
        com.torrenttv.app.data.IptvChannel(
            name = "Первый канал",
            url = "http://stream/one",
            tvgId = "ChannelOne.ru",
        ),
        com.torrenttv.app.data.IptvChannel(
            name = "Домашний",
            url = "http://stream/dom",
        ),
    )

    private fun utcCal(y: Int, mo: Int, d: Int, h: Int, mi: Int): java.util.Calendar =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, mo, d, h, mi, 0)
        }

    @Test
    fun `parse keeps only our channels`() {
        // «Сейчас» — 13:30 +0300 (10:30 UTC), внутри окна
        val now = utcCal(2026, 9, 23, 10, 30).timeInMillis
        val progs = EpgService.parseXmltv(
            xmltvSample().byteInputStream(),
            channelsForTest(),
            now,
        )
        // наш канал по tvg-id + наш канал по названию; чужой отброшен
        assertEquals(setOf("http://stream/one", "http://stream/dom"), progs.keys)
    }

    @Test
    fun `parse collects programmes sorted by start`() {
        val now = utcCal(2026, 9, 23, 10, 30).timeInMillis
        val progs = EpgService.parseXmltv(
            xmltvSample().byteInputStream(),
            channelsForTest(),
            now,
        )
        val one = progs["http://stream/one"]!!
        assertEquals(2, one.size)
        assertEquals("Новости", one[0].title)
        assertEquals("Время покажет", one[1].title)
        // «Шоу» на канале по совпадению имени
        assertEquals(listOf("Шоу"), progs["http://stream/dom"]!!.map { it.title })
    }

    @Test
    fun `parse drops programmes outside window`() {
        // «Сейчас» — 2026-11-01: все передачи сэмпла уже прошли
        val now = utcCal(2026, 10, 1, 10, 0).timeInMillis
        val progs = EpgService.parseXmltv(
            xmltvSample().byteInputStream(),
            channelsForTest(),
            now,
        )
        assertTrue(progs.isEmpty())
    }
}
