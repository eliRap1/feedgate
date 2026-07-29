package dev.eli.feedgate

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * On-device block counter. Never leaves the phone, never shown outside the
 * app, no per-event history kept. Opt-out and resettable.
 *
 * Counts EPISODES, not events: a block can fire every 1.2s while you push
 * against the gate, which as a raw count would be noise.
 *
 * The window is TUMBLING, not sliding — `last_<surface>` is written ONLY
 * when a new episode opens. A sliding window (rewriting the marker on every
 * block) inverts the metric: a two-hour standoff would extend the window
 * forever and read as 1, while a light day of well-spaced visits read as 10.
 * Tumbling makes a sitting cost ceil(duration / 5 min), so the number can
 * only grow with usage.
 */
class Stats(context: Context) {

    private val sp = context.getSharedPreferences("feedgate_stats", Context.MODE_PRIVATE)

    /**
     * Record a block. Returns true if a new episode opened.
     * MUST be called only from paths that actually blocked (i.e. after the
     * service's own debounce), never per accessibility event.
     */
    fun record(surface: String): Boolean {
        if (!enabled) return false
        val now = System.currentTimeMillis()
        val last = sp.getLong("last_$surface", 0L)
        if (now - last <= EPISODE_MS) return false // same sitting — no write at all
        val day = dayKey(now)
        val e = sp.edit()
            .putLong("last_$surface", now)
            .putInt("d_$day", sp.getInt("d_$day", 0) + 1)
            .putInt("s_$surface", sp.getInt("s_$surface", 0) + 1)
            .putInt(KEY_TOTAL, sp.getInt(KEY_TOTAL, 0) + 1)
        if (sp.getLong(KEY_SINCE, 0L) == 0L) e.putLong(KEY_SINCE, now)
        e.apply()
        pruneOldDays()
        return true
    }

    /** Counting is opt-out; the switch lives in the stats card. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    fun today(): Int = sp.getInt("d_${dayKey(System.currentTimeMillis())}", 0)

    fun total(): Int = sp.getInt(KEY_TOTAL, 0)

    /** 0 when nothing has ever been recorded. */
    fun since(): Long = sp.getLong(KEY_SINCE, 0L)

    /** Last 7 days oldest-first, today last. */
    fun week(): List<Int> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        return (0 until 7).map {
            val v = sp.getInt("d_${dayKey(cal.timeInMillis)}", 0)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            v
        }
    }

    /** Day-of-week initials matching [week], device locale. */
    fun weekLabels(): List<String> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        return (0 until 7).map {
            val s = LABEL_FMT.format(cal.time).take(1)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            s
        }
    }

    /** Per-surface all-time totals. */
    fun countOf(surface: String): Int = sp.getInt("s_$surface", 0)

    fun reset() {
        val keepEnabled = enabled
        sp.edit().clear().putBoolean(KEY_ENABLED, keepEnabled).apply()
    }

    private fun dayKey(t: Long) = DAY_FMT.format(Date(t))

    /** Keep two months of daily buckets; older ones are never displayed. */
    private fun pruneOldDays() {
        val keep = mutableSetOf<String>()
        val cal = Calendar.getInstance()
        repeat(61) {
            keep.add("d_${dayKey(cal.timeInMillis)}")
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        val stale = sp.all.keys.filter { it.startsWith("d_") && it !in keep }
        if (stale.isEmpty()) return
        val e = sp.edit()
        stale.forEach { e.remove(it) }
        e.apply()
    }

    companion object {
        const val EPISODE_MS = 5 * 60_000L

        const val SURFACE_REELS = "reels"
        const val SURFACE_EXPLORE = "explore"
        const val SURFACE_FEED = "feed"
        const val SURFACE_TIKTOK = "tiktok"

        private const val KEY_TOTAL = "total"
        private const val KEY_SINCE = "since"
        private const val KEY_ENABLED = "enabled"
        private val DAY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val LABEL_FMT = SimpleDateFormat("EEE", Locale.getDefault())
    }
}
