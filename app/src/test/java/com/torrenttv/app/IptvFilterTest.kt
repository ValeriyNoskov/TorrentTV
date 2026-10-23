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
