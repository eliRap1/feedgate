package dev.eli.feedgate

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple SharedPreferences wrapper for the per-rule toggles.
 * Everything defaults to ON except inspector mode.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("feedgate", Context.MODE_PRIVATE)

    var blockIgReels: Boolean
        get() = sp.getBoolean(KEY_IG_REELS, true)
        set(v) = sp.edit().putBoolean(KEY_IG_REELS, v).apply()

    var blockIgExplore: Boolean
        get() = sp.getBoolean(KEY_IG_EXPLORE, true)
        set(v) = sp.edit().putBoolean(KEY_IG_EXPLORE, v).apply()

    /** Block scrolling the home feed (story tray stays reachable). */
    var blockIgFeedScroll: Boolean
        get() = sp.getBoolean(KEY_IG_FEED_SCROLL, true)
        set(v) = sp.edit().putBoolean(KEY_IG_FEED_SCROLL, v).apply()

    var blockTikTokFeed: Boolean
        get() = sp.getBoolean(KEY_TT_FEED, true)
        set(v) = sp.edit().putBoolean(KEY_TT_FEED, v).apply()

    /** When TikTok lands on the feed, auto-click the Inbox tab instead of just blocking. */
    var tikTokAutoInbox: Boolean
        get() = sp.getBoolean(KEY_TT_AUTO_INBOX, true)
        set(v) = sp.edit().putBoolean(KEY_TT_AUTO_INBOX, v).apply()

    /**
     * Where a blocked feed scroll lands you: "wall" (overlay only),
     * "dms" (Instagram inbox), or "brief" (the Daybrief anti-feed).
     * Migrates from the old boolean auto-DMs toggle.
     */
    var igFeedDest: String
        get() = sp.getString(KEY_IG_FEED_DEST, null)
            ?: if (sp.getBoolean(KEY_IG_AUTO_DMS, true)) "dms" else "wall"
        set(v) = sp.edit().putString(KEY_IG_FEED_DEST, v).apply()

    /** Daybrief topic keys (see BriefRepo.TOPICS). */
    var briefTopics: Set<String>
        get() = sp.getStringSet(KEY_BRIEF_TOPICS, null) ?: setOf("tech", "israel")
        set(v) = sp.edit().putStringSet(KEY_BRIEF_TOPICS, v.toSet()).apply()

    /**
     * Allow a reel opened from a DM thread to play (one reel; swiping to the
     * next one re-blocks). Fixes "she sent me a reel and I can't watch it".
     */
    var dmGrace: Boolean
        get() = sp.getBoolean(KEY_DM_GRACE, true)
        set(v) = sp.edit().putBoolean(KEY_DM_GRACE, v).apply()

    /** Epoch millis until which ALL blocking is suspended (timed pass). 0 = none. */
    var passUntil: Long
        get() = sp.getLong(KEY_PASS_UNTIL, 0L)
        set(v) = sp.edit().putLong(KEY_PASS_UNTIL, v).apply()

    /** Dump the accessibility node tree to logcat on every window change (for fixing selectors). */
    var inspectorMode: Boolean
        get() = sp.getBoolean(KEY_INSPECTOR, false)
        set(v) = sp.edit().putBoolean(KEY_INSPECTOR, v).apply()

    /** Last automatic update-check time (epoch millis) — throttles the open-time check. */
    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()

    /** Latest detector verdicts from the service — shown on the Debug card. */
    var lastVerdict: String
        get() = sp.getString(KEY_LAST_VERDICT, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_VERDICT, v).apply()

    companion object {
        const val KEY_IG_REELS = "block_ig_reels"
        const val KEY_IG_EXPLORE = "block_ig_explore"
        const val KEY_IG_FEED_SCROLL = "block_ig_feed_scroll"
        const val KEY_TT_FEED = "block_tt_feed"
        const val KEY_TT_AUTO_INBOX = "tt_auto_inbox"
        const val KEY_IG_AUTO_DMS = "ig_auto_dms" // legacy, migration only
        const val KEY_IG_FEED_DEST = "ig_feed_dest"
        const val KEY_BRIEF_TOPICS = "brief_topics"
        const val KEY_INSPECTOR = "inspector_mode"
        const val KEY_DM_GRACE = "dm_grace"
        const val KEY_PASS_UNTIL = "pass_until"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_LAST_VERDICT = "last_verdict"
    }
}
