package com.pira.ccloud.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRulesTest {
    @Test
    fun readsVersions() {
        assertEquals(listOf(2, 2, 3), AppUpdateRules.parseVersion("v2.2.3"))
        assertEquals(listOf(2, 0, 2), AppUpdateRules.parseVersion("v2.0.2-dual-subtitles"))
        assertEquals(listOf(2, 2, 2), AppUpdateRules.parseVersion("2.2.2"))
        assertNull(AppUpdateRules.parseVersion("metadata-snapshot"))
    }

    @Test
    fun comparesVersions() {
        assertTrue(AppUpdateRules.isNewer("v2.2.3", "2.2.2"))
        assertTrue(AppUpdateRules.isNewer("v2.2.10", "2.2.9"))
        assertTrue(AppUpdateRules.isNewer("v2.3", "2.2.9"))
        assertFalse(AppUpdateRules.isNewer("v2.2.2", "2.2.2"))
        assertFalse(AppUpdateRules.isNewer("v2.2.0", "2.2"))
        assertFalse(AppUpdateRules.isNewer("v2.2.1", "2.2.2"))
        assertFalse(AppUpdateRules.isNewer("metadata-snapshot", "2.2.2"))
    }

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, apk: String? = "CCloud-universal.apk") = """
        {"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,"body":"Notes for $tag",
         "assets":[${apk?.let { """{"name":"$it","size":123,"browser_download_url":"https://example.com/$tag/$it"}""" } ?: ""}]}
    """.trimIndent()

    private val releases = "[" + listOf(
        release("metadata-snapshot", prerelease = true, apk = "snapshot.json.gz"),
        release("v2.2.2"),
        release("v2.3.0", draft = true),
        release("v2.2.4", prerelease = true),
        release("v2.2.3"),
        release("v2.0.2-dual-subtitles")
    ).joinToString(",") + "]"

    @Test
    fun picksHighestPublishedRelease() {
        val update = AppUpdateRules.pickUpdate(releases, "2.2.2")!!
        assertEquals("v2.2.3", update.tag)
        assertEquals("2.2.3", update.version)
        assertEquals("https://example.com/v2.2.3/CCloud-universal.apk", update.apkUrl)
        assertEquals(123L, update.apkSize)
        assertEquals("Notes for v2.2.3", update.notes)
    }

    @Test
    fun nothingWhenUpToDateOrSkipped() {
        assertNull(AppUpdateRules.pickUpdate(releases, "2.2.3"))
        assertNull(AppUpdateRules.pickUpdate(releases, "2.2.2", skippedTags = setOf("v2.2.3")))
        assertNull(AppUpdateRules.pickUpdate("[" + release("v9.0.0", apk = null) + "]", "2.2.2"))
        assertNull(AppUpdateRules.pickUpdate("not json", "2.2.2"))
    }
}
