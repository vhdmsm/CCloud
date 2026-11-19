package com.pira.ccloud.utils

import com.pira.ccloud.data.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityOrderTest {
    private fun source(id: Int, quality: String, url: String = "https://example.com/$id.mp4") =
        Source(id = id, quality = quality, type = "mp4", url = url)

    @Test
    fun readsHeights() {
        assertEquals(1080, QualityOrder.height("1080p x265"))
        assertEquals(720, QualityOrder.height("720P"))
        assertEquals(2160, QualityOrder.height("4K HDR"))
        assertEquals(480, QualityOrder.height("کیفیت ۴۸۰"))
        assertNull(QualityOrder.height("x265 Dubbed"))
    }

    @Test
    fun bestFirstThenUnknownThenTrailers() {
        val sorted = QualityOrder.bestFirst(
            listOf(
                source(1, "تیزر", "https://example.com/tizer/1.mp4"),
                source(2, "480p"),
                source(3, "Dubbed"),
                source(4, "1080p"),
                source(5, "720p"),
                source(6, "1080p x265")
            )
        )
        assertEquals(listOf(4, 6, 5, 2, 3, 1), sorted.map { it.id })
    }
}
