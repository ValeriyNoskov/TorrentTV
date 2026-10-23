package com.torrenttv.app

import com.torrenttv.app.data.ReleaseQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseQualityTest {

    @Test
    fun `2160p из названия`() {
        assertEquals("2160p", ReleaseQuality.parse("Movie 2023 2160p UHD BluRay"))
        assertEquals("2160p", ReleaseQuality.parse("Фильм 4K HDR"))
        assertEquals("2160p", ReleaseQuality.parse("Something UHD WEB-DL"))
    }

    @Test
    fun `1080p из названия`() {
        assertEquals("1080p", ReleaseQuality.parse("Movie 1080p BluRay x264"))
        assertEquals("1080p", ReleaseQuality.parse("Movie 1080i HDTV"))
    }

    @Test
    fun `720p из названия`() {
        assertEquals("720p", ReleaseQuality.parse("Movie S01 720p WEB-DL"))
    }

    @Test
    fun `SD из названия`() {
        assertEquals("SD", ReleaseQuality.parse("Movie DVDRip"))
        assertEquals("SD", ReleaseQuality.parse("Movie TS 700MB"))
        assertEquals("SD", ReleaseQuality.parse("Фильм CAMRip"))
    }

    @Test
    fun `нераспознанное — пустая строка`() {
        assertEquals("", ReleaseQuality.parse("Просто какое-то имя раздачи"))
    }

    @Test
    fun `более высокое качество побеждает`() {
        // в названии 2160p встречается раньше 1080p — берём максимум
        assertEquals("2160p", ReleaseQuality.parse("Movie 2160p remux not 1080p"))
    }
}
