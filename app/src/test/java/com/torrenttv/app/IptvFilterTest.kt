package com.torrenttv.app

import com.torrenttv.app.data.IptvService
import com.torrenttv.app.data.TorrentResult
import com.torrenttv.app.ui.bestTorrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvFilterTest {

    // ---------- isRegional ----------

    @Test
    fun `regional channels are filtered out`() {
        assertTrue(IptvService.isRegional("Astrahan 24"))
        assertTrue(IptvService.isRegional("Astrahan.Ru TV"))
        assertTrue(IptvService.isRegional("Belgorod 24"))
        assertTrue(IptvService.isRegional("Don 24"))
        assertTrue(IptvService.isRegional("Bashkortostan 24"))
        assertTrue(IptvService.isRegional("Enisey"))
        assertTrue(IptvService.isRegional("Астрахань 24"))
        assertTrue(IptvService.isRegional("Дон 24"))
        // Free-TV: без мягкого знака, раньше проскакивали
        assertTrue(IptvService.isRegional("Астрахан 24"))
        assertTrue(IptvService.isRegional("Катунь 24"))
        assertTrue(IptvService.isRegional("Крым 24"))
        assertTrue(IptvService.isRegional("Луганск 24"))
        assertTrue(IptvService.isRegional("Урал 24"))
        assertTrue(IptvService.isRegional("Запад 24"))
        assertTrue(IptvService.isRegional("Сибирь 24"))
        assertTrue(IptvService.isRegional("Ветта 24"))
        assertTrue(IptvService.isRegional("Мир Белогорья"))
        assertTrue(IptvService.isRegional("Архыз 24"))
        assertTrue(IptvService.isRegional("Якутия 24"))
    }

    @Test
    fun `moscow and federal channels stay`() {
        assertFalse(IptvService.isRegional("Москва 24"))
        assertFalse(IptvService.isRegional("Москва. Доверие"))
        assertFalse(IptvService.isRegional("Говорит Москва"))
        assertFalse(IptvService.isRegional("Русский роман"))
        assertFalse(IptvService.isRegional("Первый канал"))
        assertFalse(IptvService.isRegional("360°"))
        assertFalse(IptvService.isRegional("Discovery Channel"))
        assertFalse(IptvService.isRegional("Подмосковье 360"))
    }

    // ---------- белый список групп (регистр) ----------

    @Test
    fun `shown groups match regardless of case`() {
        val shown = IptvService.SHOWN_GROUPS
        // В плейлисте iptv-org группы с заглавной: «Movies», «Kids»…
        assertTrue(shown.contains("movies"))
        // Фильтр показа: группа приводится к нижнему регистру перед сравнением
        fun isShown(group: String) = group.lowercase().trim() in shown || group.lowercase() == "federal"
        assertTrue(isShown("Movies"))
        assertTrue(isShown("Kids"))
        assertTrue(isShown("Entertainment"))
        assertTrue(isShown("Federal"))
        assertFalse(isShown("Shop"))
        assertFalse(isShown("Undefined"))
        assertFalse(isShown("General"))
        assertFalse(isShown("Legislative"))
    }

    // ---------- groupOrder ----------

    @Test
    fun `federal group goes first, shop and undefined last`() {
        val order = listOf("federal", "General", "Movies", "Series", "Shop", "Undefined")
        val sorted = order.sortedBy { IptvService.groupOrder(it) }
        assertEquals(listOf("federal", "General", "Movies", "Series", "Shop", "Undefined"), sorted)
        assertTrue(IptvService.groupOrder("federal") < IptvService.groupOrder("news"))
        assertTrue(IptvService.groupOrder("movies") < IptvService.groupOrder("shop"))
    }

    // ---------- bestTorrent (Zona-авто-подбор) ----------

    private fun r(seeds: Int, res: String = "") =
        TorrentResult(name = "t", infoHash = seeds.toString().repeat(3), seeds = seeds, sizeBytes = 0, resolution = res)

    @Test
    fun `prefers hd with decent seeds`() {
        val best = bestTorrent(
            listOf(r(100, "480p"), r(15, "1080p"), r(3, "2160p"))
        )
        assertEquals("1080p", best.resolution)
    }

    @Test
    fun `falls back to max seeds when hd is dead`() {
        val best = bestTorrent(
            listOf(r(200, "480p"), r(2, "1080p"), r(1, "720p"))
        )
        assertEquals(200, best.seeds)
    }

    @Test
    fun `single result returned as is`() {
        val best = bestTorrent(listOf(r(5)))
        assertEquals(5, best.seeds)
    }
}
