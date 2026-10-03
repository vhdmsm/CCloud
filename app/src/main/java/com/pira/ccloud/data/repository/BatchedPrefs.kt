package com.pira.ccloud.data.repository

import android.content.Context
import android.content.SharedPreferences
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A SharedPreferences file whose writes are saved together a moment later. The Watchmode and OMDb
 * caches hold thousands of answers, and every save rewrites the whole file: saving each answer on
 * its own (hundreds while a list loads) kept the disk busy and could stall the app when it went
 * to the background. Unsaved writes are read back from memory meanwhile.
 */
class BatchedPrefs(private val name: String) {
    @Volatile
    private var prefs: SharedPreferences? = null
    // Writes not saved yet: the value, or empty for a removal
    private val pending = ConcurrentHashMap<String, Optional<String>>()
    private val scheduled = AtomicBoolean(false)

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    fun getString(key: String): String? {
        pending[key]?.let { return it.orElse(null) }
        return prefs?.getString(key, null)
    }

    fun putString(key: String, value: String) {
        pending[key] = Optional.of(value)
        scheduleSave()
    }

    fun remove(key: String) {
        pending[key] = Optional.empty()
        scheduleSave()
    }

    private fun scheduleSave() {
        if (prefs != null && scheduled.compareAndSet(false, true)) {
            saver.schedule({ save() }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun save() {
        scheduled.set(false)
        val p = prefs ?: return
        val batch = HashMap(pending)
        if (batch.isEmpty()) return
        val editor = p.edit()
        for ((key, value) in batch) {
            if (value.isPresent) editor.putString(key, value.get()) else editor.remove(key)
        }
        // apply() updates the in-memory values at once, so the pending ones can go; a write that
        // came in meanwhile stays for the next save
        editor.apply()
        for ((key, value) in batch) pending.remove(key, value)
        if (pending.isNotEmpty()) scheduleSave()
    }

    private companion object {
        const val SAVE_DELAY_MS = 2_000L
        val saver = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "prefs-saver").apply { isDaemon = true }
        }
    }
}
