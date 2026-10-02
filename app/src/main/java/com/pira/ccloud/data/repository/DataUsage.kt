package com.pira.ccloud.data.repository

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How much movie data the ranked sorts may fetch from Watchmode and OMDb, which cost credits:
 * a small daily allowance to try them out, or no limit of the app's own (chosen in Settings).
 */
object DataUsage {
    // Movies or series a day that may get new data in test mode; each costs about 2 to 5 Watchmode
    // credits and an OMDb request
    const val TEST_TITLES_PER_DAY = 20

    enum class Mode(val label: String, val description: String) {
        TEST(
            "Test (limited)",
            "New data for up to $TEST_TITLES_PER_DAY movies or series a day, the best rated first; " +
                "the rest use data already on the device"
        ),
        UNLIMITED(
            "Unlimited",
            "New data for every movie and series, until the API credits run out"
        )
    }

    private const val PREFS_NAME = "data_usage"
    private const val KEY_MODE = "mode"
    private const val KEY_DAY = "day"
    private const val KEY_USED = "used"

    private var prefs: SharedPreferences? = null

    @Volatile
    var mode: Mode = Mode.TEST
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        mode = p.getString(KEY_MODE, null)?.let { saved -> Mode.entries.firstOrNull { it.name == saved } } ?: Mode.TEST
    }

    fun setMode(context: Context, newMode: Mode) {
        init(context)
        mode = newMode
        prefs?.edit()?.putString(KEY_MODE, newMode.name)?.apply()
    }

    // Limits apply once set up on a device (unit tests and code without it aren't limited)
    private val isLimited: Boolean get() = prefs != null && mode == Mode.TEST

    // Unlimited also drops the credit reserve (only this year's movies once credits are low)
    val keepsCreditReserve: Boolean get() = mode != Mode.UNLIMITED

    /** Titles that got new data today in test mode. */
    val usedToday: Int
        get() {
            val p = prefs ?: return 0
            return if (p.getString(KEY_DAY, null) == today()) p.getInt(KEY_USED, 0) else 0
        }

    val isTestAllowanceUsedUp: Boolean get() = isLimited && usedToday >= TEST_TITLES_PER_DAY

    /** Takes one title from today's test allowance; false when test mode has used it up. */
    @Synchronized
    fun tryUseLookup(): Boolean {
        if (!isLimited) return true
        val p = prefs ?: return true
        val day = today()
        val used = if (p.getString(KEY_DAY, null) == day) p.getInt(KEY_USED, 0) else 0
        if (used >= TEST_TITLES_PER_DAY) return false
        p.edit().putString(KEY_DAY, day).putInt(KEY_USED, used + 1).apply()
        return true
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
