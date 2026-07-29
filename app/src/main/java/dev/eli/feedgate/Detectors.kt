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

    /** One-line detector verdicts + raw signals — prepended to every dump
     *  so a shared dump immediately shows WHICH predicate misfired. */
    fun debugState(root: AccessibilityNodeInfo): String = buildString {
        fun safe(name: String, f: () -> Boolean) {
            append(name).append('=')
            append(runCatching(f).getOrElse { "err" })
            append(' ')
        }
        safe("home") { igHomeFeedOpen(root) }
        safe("clipsVis") { igClipsViewerOpen(root) }
        safe("clipsTab") { igReelsTabSelected(root) }
        safe("direct") { igDirectOpen(root) }
        safe("explore") { igExploreOpen(root) }
        safe("story") { igStoryViewerOpen(root) }
        fun node(s: String) {
            append(s.substringAfterLast('/')).append('[')
            val n = findByIdSuffix(root, s)
            append(
                if (n == null) "absent"
                else "sel=${n.isSelected},vis=${n.isVisibleToUser}"
            )
            append("] ")
        }
        node(":id/feed_tab"); node(":id/clips_tab"); node(":id/direct_tab")
        node(":id/search_tab"); node(":id/clips_viewer_view_pager")
        node(":id/clips_video_container")
        node(":id/inbox_refreshable_thread_list_recyclerview")
        node(":id/action_bar_title_view")
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

    /** The clips viewer actually ON SCREEN (not the offscreen pager page). */
    fun igClipsViewerOpen(root: AccessibilityNodeInfo): Boolean =
        visibleById(root, ":id/clips_viewer_view_pager") != null ||
            visibleById(root, ":id/clips_video_container") != null

    /** Bottom-bar Reels tab selected — browsing Reels deliberately. */
    fun igReelsTabSelected(root: AccessibilityNodeInfo): Boolean =
        tabSel(root, ":id/clips_tab")

    /** True when the Reels (clips) viewer is on screen. */
    fun igReelsOpen(root: AccessibilityNodeInfo): Boolean = igClipsViewerOpen(root)

    /** True when Explore / search-grid is on screen. */
    fun igExploreOpen(root: AccessibilityNodeInfo): Boolean =
        tabSel(root, ":id/search_tab") ||
            visibleById(root, ":id/explore_grid_recycler_view") != null

    /**
     * The bottom tab bar exists in the tree only on tabbed surfaces —
     * fullscreen viewers (stories, shared reels, DM threads) drop it
     * entirely (dump-verified 21:11:54 / 21:11:57 / 21:12:30).
     */
    fun igTabBarPresent(root: AccessibilityNodeInfo): Boolean =
        findByIdSuffix(root, ":id/feed_tab") != null

    /**
     * Home feed on screen: Home tab selected and no clips viewer on top.
     * Deliberately does NOT require the top action bar — Instagram hides
     * it while scrolling (dump-verified 21:12:00: feed_tab selected, bar
     * gone, feed fully browsable).
     */
    fun igHomeFeedOpen(root: AccessibilityNodeInfo): Boolean {
        val tab = findByIdSuffix(root, ":id/feed_tab") ?: return false
        if (!tab.isSelected || !tab.isVisibleToUser) return false
        return !igClipsViewerOpen(root)
    }

    /** Stories viewer — ALLOWED. (Instagram internally names stories "reel".) */
    fun igStoryViewerOpen(root: AccessibilityNodeInfo): Boolean =
        visibleById(root, ":id/reel_viewer_root") != null ||
            visibleById(root, ":id/reel_viewer_media_container") != null

    /** DM inbox / thread — ALLOWED. */
    fun igDirectOpen(root: AccessibilityNodeInfo): Boolean =
        tabSel(root, ":id/direct_tab") ||
            visibleById(root, ":id/inbox_refreshable_thread_list_recyclerview") != null ||
            visibleById(root, ":id/direct_thread_view") != null ||
            visibleById(root, ":id/direct_thread_toolbar") != null

    /**
     * True when this scroll event comes from the home feed list (not DMs, not stories).
     * Used to allow the story tray while punishing feed doomscrolling.
     */
    fun igIsFeedScroll(event: AccessibilityEvent, root: AccessibilityNodeInfo): Boolean {
        val source = event.source ?: return false
        val id = source.viewIdResourceName ?: ""
        // Story tray is a horizontal list; feed scrolls are the vertical feed recycler.
        if (id.endsWith(":id/stories_tray_recyclerview") || id.endsWith(":id/tray_recyclerview")) return false
        // Horizontal-only scrolls are the story tray / post carousels — never
        // the feed. ID-independent, so it survives Instagram renames.
        if (event.scrollDeltaX != 0 && event.scrollDeltaY == 0) return false
        if (!igHomeFeedOpen(root)) return false
        // Heuristic: vertical scrollable container on the home surface.
        return source.className?.contains("RecyclerView") == true ||
            source.className?.contains("ListView") == true
    }

    /**
     * Top edge for the feed blackout on the home surface: just under the
     * story tray when it's findable, otherwise a 30%-of-screen fallback
     * that still leaves the tray region usable.
     */
    fun igFeedCoverTop(root: AccessibilityNodeInfo, screenHeight: Int): Int {
        // Story tray (dump-verified: a RecyclerView described "reels tray
        // container"; it scrolls away with the feed).
        val tray = findByDesc(root, setOf("reels tray container"))
            ?.takeIf { it.isVisibleToUser }
        if (tray != null) {
            val r = Rect()
            tray.getBoundsInScreen(r)
            if (r.bottom > 0 && r.bottom < screenHeight / 2) return r.bottom
        }
        // Tray scrolled away: cover from under the Instagram action bar.
        val bar = visibleById(root, ":id/action_bar_title_view")
        if (bar != null) {
            val r = Rect()
            bar.getBoundsInScreen(r)
            if (r.bottom > 0) return r.bottom
        }
        return (screenHeight * 0.12f).toInt()
    }

    /** Bottom edge for the blackout: top of the bottom tab bar (feed_tab). */
    fun igBottomNavTop(root: AccessibilityNodeInfo, screenHeight: Int): Int {
        val tab = findByIdSuffix(root, ":id/feed_tab")
        if (tab != null) {
            val r = Rect()
            tab.getBoundsInScreen(r)
            if (r.top > 0) return r.top
        }
        return (screenHeight * 0.92f).toInt()
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
