package com.pira.ccloud.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SiteListCacheTest {
    private fun waitFor(file: File, root: File, asked: List<Int>) {
        val until = System.currentTimeMillis() + 10_000
        while (!file.exists() && System.currentTimeMillis() < until) Thread.sleep(20)
        val files = root.walkTopDown().filter { it.isFile }.joinToString { it.relativeTo(root).path }
        assertTrue("$file missing; files: $files; asked: $asked", file.exists())
    }

    @Test
    fun keepsTheListAndReadsAnOldOneAgainInTheBackground() = runBlocking {
        val root = Files.createTempDirectory("site-lists").toFile()
        SiteListCache.useDirectory(root)
        var site = mapOf(0 to "a0", 1 to "a1")
        val asked = mutableListOf<Int>()
        suspend fun read(page: Int, maxAgeMs: Long) =
            SiteListCache.page("series", page, maxAgeMs) { synchronized(asked) { asked += page }; site.getValue(page) }

        // First read: from the site, kept
        assertEquals("a0", read(0, 60_000))
        assertEquals("a1", read(1, 60_000))
        assertEquals(listOf(0, 1), asked)
        // Young enough: from the device, the site isn't asked
        assertEquals("a0", read(0, 60_000))
        assertEquals("a1", read(1, 60_000))
        assertEquals(2, asked.size)

        // Too old: the kept pages show at once and are read again in the background
        site = mapOf(0 to "b0", 1 to "b1")
        assertEquals("a0", read(0, -1))
        assertEquals("a1", read(1, -1))
        waitFor(File(root, "series/next/complete"), root, synchronized(asked) { asked.toList() })
        assertEquals(4, asked.size)
        // The next read from the first page takes the new snapshot
        assertEquals("b0", read(0, 60_000))
        assertEquals("b1", read(1, 60_000))
        assertEquals(4, asked.size)
        root.deleteRecursively()
        Unit
    }
}
