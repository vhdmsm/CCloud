package com.pira.ccloud.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeysTest {
    private val none = Triple("", "", "")

    @Test
    fun keylessUpdateKeepsTheSavedKeys() {
        val saved = Triple("o1,o2", "w1", "s1")
        assertEquals(saved, ApiKeys.resolve(none, saved))
    }

    @Test
    fun buildWithKeysReplacesTheSavedOnes() {
        val build = Triple("o3", "w2,w3", "s2")
        assertEquals(build, ApiKeys.resolve(build, Triple("o1", "w1", "s1")))
    }

    @Test
    fun eachKeySetFallsBackOnItsOwn() {
        assertEquals(
            Triple("o3", "w1", "s1"),
            ApiKeys.resolve(Triple("o3", "", " "), Triple("o1", "w1", "s1"))
        )
    }

    @Test
    fun noKeysAnywhere() {
        assertEquals(none, ApiKeys.resolve(none, none))
    }
}
