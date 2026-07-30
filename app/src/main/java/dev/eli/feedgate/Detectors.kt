package dev.eli.feedgate

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Screen detection for Instagram and TikTok.
 *
 * These selectors are best-effort and WILL drift as the apps update.
 * When something stops matching, enable Inspector Mode in the FeedGate app,
 * open the screen in question, and read the dumped node tree in logcat:
 *   adb logcat -s FeedGate
 * then update the IDs / content-descriptions below.
 *
 * Terminology trap worth knowing: Instagram internally calls STORIES "reel"
 * (reel_viewer...) and calls REELS "clips" (clips_viewer...). We allow the
 * former and block the latter.
 */
object Detectors {

    const val TAG = "FeedGate"

    const val PKG_INSTAGRAM = "com.instagram.android"
    // TikTok ships under two package names depending on region.
    val PKG_TIKTOK = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")

    // ---------- single-pass snapshot ----------

    /**
     * The detectors run on every accessibility event (80ms cadence) and each
     * lookup is a full tree walk over Instagram's process. One walk per event
     * collects every node the predicates need; everything below reads the
     * snapshot instead of re-walking.
     */
    class Snap(root: AccessibilityNodeInfo) {
        val byId = HashMap<String, AccessibilityNodeInfo>()
        var tray: AccessibilityNodeInfo? = null

        init {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 4000) {
                val n = stack.removeLast()
                val id = n.viewIdResourceName
                if (id != null) {
                    val key = id.substringAfterLast('/')
                    if (key in WANTED && key !in byId) byId[key] = n
                }
                if (tray == null) {
                    val d = n.contentDescription?.toString()
                    if (d != null && (d.equals("reels tray container", true) ||
                            d.equals("מכל מגש הסטוריז", true))
                    ) tray = n
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }

        fun sel(key: String) = byId[key]?.isSelected == true
        fun vis(key: String) = byId[key]?.isVisibleToUser == true
        fun has(key: String) = byId.containsKey(key)

        private companion object {
            val WANTED = setOf(
                "feed_tab", "clips_tab", "direct_tab", "search_tab", "profile_tab",
                "clips_viewer_view_pager", "clips_video_container",
                "reel_viewer_root", "reel_viewer_media_container",
                "inbox_refreshable_thread_list_recyclerview",
                "direct_thread_view", "direct_thread_toolbar",
                "explore_grid_recycler_view", "action_bar_title_view",
                // Home action-bar logo: present only on the home surface,
                // which is what identifies home while the feed is loading.
                "title_logo",
                "stories_tray_recyclerview", "tray_recyclerview",
            )
        }
    }

    fun snap(root: AccessibilityNodeInfo) = Snap(root)

    // Snapshot-based predicates — these are what the service should call.
    fun igClipsViewerOpen(s: Snap) =
        s.vis("clips_viewer_view_pager") || s.vis("clips_video_container")

    fun igReelsTabSelected(s: Snap) = s.sel("clips_tab")

    fun igTabBarPresent(s: Snap) = s.has("feed_tab")

    /**
     * Home feed on screen. Two accepted signatures, both dump-verified:
     *
     *  1. feed_tab selected + visible (the steady state).
     *  2. The bottom bar exists but NO tab carries the selected flag, while
     *     the home action-bar logo is in the tree. Instagram clears every
     *     tab's selection while the feed is still LOADING (shimmer) — 26 of
     *     89 snapshots in the 2026-07-30 dump looked exactly like this, and
     *     rule 1 alone left the feed uncovered for that whole window, which
     *     is the "doesn't black out when entering Instagram" report.
     *
     * Fullscreen surfaces (story viewer, shared reel, DM thread) drop the
     * bar entirely, so rule 2 cannot catch them.
     */
    fun igHomeFeedOpen(s: Snap): Boolean {
        if (igClipsViewerOpen(s) || igStoryViewerOpen(s)) return false
        if (!s.has("feed_tab")) return false
        if (s.sel("feed_tab") && s.vis("feed_tab")) return true
        val anySelected = s.sel("clips_tab") || s.sel("direct_tab") ||
            s.sel("search_tab") || s.sel("profile_tab")
        return !anySelected && s.has("title_logo") &&
            !igDirectOpen(s) && !igExploreOpen(s)
    }

    fun igStoryViewerOpen(s: Snap) =
        s.vis("reel_viewer_root") || s.vis("reel_viewer_media_container")

    fun igDirectOpen(s: Snap) =
        s.sel("direct_tab") || s.vis("inbox_refreshable_thread_list_recyclerview") ||
            s.vis("direct_thread_view") || s.vis("direct_thread_toolbar")

    fun igExploreOpen(s: Snap) =
        s.sel("search_tab") || s.vis("explore_grid_recycler_view")

    fun igFeedCoverTop(s: Snap, screenHeight: Int): Int {
        val tray = (s.byId["stories_tray_recyclerview"] ?: s.byId["tray_recyclerview"] ?: s.tray)
            ?.takeIf { it.isVisibleToUser }
        if (tray != null) {
            val r = Rect()
            tray.getBoundsInScreen(r)
            if (r.bottom > 0 && r.bottom < screenHeight / 2) return r.bottom
        }
        s.byId["action_bar_title_view"]?.takeIf { it.isVisibleToUser }?.let {
            val r = Rect()
            it.getBoundsInScreen(r)
            if (r.bottom > 0) return r.bottom
        }
        return (screenHeight * 0.12f).toInt()
    }

    fun igBottomNavTop(s: Snap, screenHeight: Int): Int {
        s.byId["feed_tab"]?.let {
            val r = Rect()
            it.getBoundsInScreen(r)
            if (r.top > 0) return r.top
        }
        return (screenHeight * 0.92f).toInt()
    }

    /** Verdict line for the Debug card / dumps — one snapshot, no re-walks. */
    fun debugState(s: Snap): String = buildString {
        append("home=").append(igHomeFeedOpen(s))
        append(" clipsVis=").append(igClipsViewerOpen(s))
        append(" clipsTab=").append(igReelsTabSelected(s))
        append(" direct=").append(igDirectOpen(s))
        append(" explore=").append(igExploreOpen(s))
        append(" story=").append(igStoryViewerOpen(s))
        listOf(
            "feed_tab", "clips_tab", "direct_tab", "search_tab",
            "clips_viewer_view_pager", "clips_video_container",
            "inbox_refreshable_thread_list_recyclerview", "action_bar_title_view",
            "title_logo",
        ).forEach { k ->
            append(' ').append(k).append('[')
            val n = s.byId[k]
            append(if (n == null) "absent" else "sel=${n.isSelected},vis=${n.isVisibleToUser}")
            append(']')
        }
    }

    // ---------- generic helpers ----------

    private fun findByIdSuffix(root: AccessibilityNodeInfo, suffix: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val id = n.viewIdResourceName
            if (id != null && id.endsWith(suffix)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /**
     * Find a node whose content-description matches. With [requireSelected]
     * the search continues past unselected matches — critical for tab checks,
     * where the first desc match in the tree may not be the selected node.
     */
    private fun findByDesc(
        root: AccessibilityNodeInfo,
        descs: Set<String>,
        requireSelected: Boolean = false,
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val d = n.contentDescription?.toString()
            if (d != null && descs.any { d.equals(it, ignoreCase = true) || d.startsWith("$it,") || d.startsWith("$it ") }) {
                if (!requireSelected || n.isSelected) return n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /** True if a tab with one of these descriptions is currently selected. */
    private fun selectedTab(root: AccessibilityNodeInfo, descs: Set<String>): Boolean =
        findByDesc(root, descs, requireSelected = true) != null

    /**
     * Instagram keeps ALL pager pages (feed, Reels viewer, DM inbox) in the
     * tree at once (verified by on-device dump, 2026-07-29) — presence means
     * nothing. Truth = bottom-tab selected state + isVisibleToUser.
     */
    private fun visibleById(root: AccessibilityNodeInfo, suffix: String): AccessibilityNodeInfo? =
        findByIdSuffix(root, suffix)?.takeIf { it.isVisibleToUser }

    private fun tabSel(root: AccessibilityNodeInfo, suffix: String): Boolean =
        findByIdSuffix(root, suffix)?.isSelected == true

    // Single DFS for all suffixes — this runs on every event, keep it one pass.
    private fun hasIdSuffix(root: AccessibilityNodeInfo, vararg suffixes: String): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val id = n.viewIdResourceName
            if (id != null && suffixes.any { id.endsWith(it) }) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return false
    }

    /** Build the node tree into [sb] — for the shareable inspector dump. */
    fun buildTree(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
        if (node == null || depth > 25) return
        sb.append(" ".repeat(depth * 2))
            .append(node.className).append(" id=").append(node.viewIdResourceName)
            .append(" desc=").append(node.contentDescription)
            .append(" sel=").append(node.isSelected)
            .append(" text=").append(node.text?.toString()?.take(40))
            .append('\n')
        for (i in 0 until node.childCount) buildTree(node.getChild(i), sb, depth + 1)
    }


    /** Dump the node tree to logcat (inspector mode). */
    fun dumpTree(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > 25) return
        val pad = " ".repeat(depth * 2)
        Log.d(
            TAG,
            "$pad${node.className} id=${node.viewIdResourceName} " +
                "desc=${node.contentDescription} sel=${node.isSelected} " +
                "text=${node.text?.toString()?.take(40)}"
        )
        for (i in 0 until node.childCount) dumpTree(node.getChild(i), depth + 1)
    }

    // ---------- Instagram ----------

    /**
     * True when this scroll event comes from the home feed list (not DMs, not stories).
     * Used to allow the story tray while punishing feed doomscrolling.
     */
    fun igIsFeedScroll(event: AccessibilityEvent, s: Snap): Boolean {
        val source = event.source ?: return false
        val id = source.viewIdResourceName ?: ""
        // Story tray is a horizontal list; feed scrolls are the vertical feed recycler.
        if (id.endsWith(":id/stories_tray_recyclerview") || id.endsWith(":id/tray_recyclerview")) return false
        // Horizontal-only scrolls are the story tray / post carousels — never
        // the feed. ID-independent, so it survives Instagram renames.
        if (event.scrollDeltaX != 0 && event.scrollDeltaY == 0) return false
        if (!igHomeFeedOpen(s)) return false
        // Heuristic: vertical scrollable container on the home surface.
        return source.className?.contains("RecyclerView") == true ||
            source.className?.contains("ListView") == true
    }

    // ---------- TikTok ----------

    // Hebrew descriptions included alongside English — TikTok/Instagram
    // localize content-descriptions with the app language.
    private val TT_HOME = setOf("Home", "בית")
    private val TT_FRIENDS = setOf("Friends", "חברים")
    private val TT_INBOX = setOf("Inbox", "Messages", "תיבת דואר נכנס", "הודעות", "דואר נכנס")
    private val TT_PROFILE = setOf("Profile", "Me", "פרופיל", "אני")

    /** True when the For You / Friends video feed is on screen. */
    fun ttFeedOpen(root: AccessibilityNodeInfo): Boolean {
        if (ttInboxOpen(root) || ttProfileOpen(root)) return false
        return selectedTab(root, TT_HOME) || selectedTab(root, TT_FRIENDS)
    }

    /** Inbox tab (DMs live here) — ALLOWED. */
    fun ttInboxOpen(root: AccessibilityNodeInfo): Boolean = selectedTab(root, TT_INBOX)

    fun ttProfileOpen(root: AccessibilityNodeInfo): Boolean = selectedTab(root, TT_PROFILE)

    /** The Inbox bottom-tab node, for auto-redirect clicking. */
    fun ttInboxTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByDesc(root, TT_INBOX)
}
