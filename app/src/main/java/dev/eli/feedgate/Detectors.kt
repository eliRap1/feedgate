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

    /** Find a node whose TEXT or description matches — for visible labels. */
    private fun findByTextOrDesc(root: AccessibilityNodeInfo, strings: Set<String>): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            if (strings.any { s ->
                    t?.equals(s, true) == true || d?.equals(s, true) == true ||
                        d?.startsWith("$s,") == true
                }
            ) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    // The story-tray's own label — a home-surface signature that survives
    // Instagram's internal view-ID renames. Locale-dependent by design.
    private val IG_YOUR_STORY = setOf("Your story", "הסטורי שלך", "הסיפור שלך")

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

    private val REELS_TAB = setOf("Reels", "רילס")

    /** The full-screen clips viewer surface (by view IDs). */
    fun igClipsViewerOpen(root: AccessibilityNodeInfo): Boolean =
        hasIdSuffix(root, ":id/clips_viewer_view_pager", ":id/clips_swipe_refresh_container",
            ":id/clips_video_container")

    /** Reels tab selected in the bottom bar (Hebrew included). */
    fun igReelsTabSelected(root: AccessibilityNodeInfo): Boolean =
        selectedTab(root, REELS_TAB)

    /** True when the Reels (clips) viewer is on screen. */
    fun igReelsOpen(root: AccessibilityNodeInfo): Boolean =
        igClipsViewerOpen(root) || igReelsTabSelected(root)

    /** True when Explore / search-grid is on screen. */
    fun igExploreOpen(root: AccessibilityNodeInfo): Boolean {
        if (hasIdSuffix(root, ":id/explore_grid_recycler_view", ":id/explore_topic_cluster_grid")) return true
        return selectedTab(root, setOf("Search and explore", "Search and Explore", "Explore", "חיפוש"))
    }

    /** True when the home feed surface is on screen (story tray lives here too). */
    fun igHomeFeedOpen(root: AccessibilityNodeInfo): Boolean {
        // The DM inbox, story viewer and camera must NOT match:
        if (igStoryViewerOpen(root) || igDirectOpen(root)) return false
        if (selectedTab(root, setOf("Home", "בית"))) return true
        if (hasIdSuffix(root, ":id/feed_swipe_refresh_layout", ":id/main_feed_recycler")) return true
        // ID-independent signature: the story tray's "Your story" label is
        // only rendered on the home surface.
        return findByTextOrDesc(root, IG_YOUR_STORY) != null
    }

    /** Stories viewer — ALLOWED. (Instagram internally names stories "reel".) */
    fun igStoryViewerOpen(root: AccessibilityNodeInfo): Boolean =
        hasIdSuffix(root, ":id/reel_viewer_root", ":id/reel_viewer_texture_view",
            ":id/reel_viewer_media_container")

    /** DM inbox / thread — ALLOWED. */
    fun igDirectOpen(root: AccessibilityNodeInfo): Boolean =
        hasIdSuffix(root, ":id/direct_inbox_recycler_view", ":id/direct_thread_view",
            ":id/direct_thread_toolbar", ":id/direct_recycler_view")

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
        val tray = findByIdSuffix(root, ":id/stories_tray_recyclerview")
            ?: findByIdSuffix(root, ":id/tray_recyclerview")
        if (tray != null) {
            val r = Rect()
            tray.getBoundsInScreen(r)
            if (r.bottom > 0) return r.bottom
        }
        // Fallback: bottom edge of the "Your story" label = tray bottom.
        val label = findByTextOrDesc(root, IG_YOUR_STORY)
        if (label != null) {
            val r = Rect()
            label.getBoundsInScreen(r)
            if (r.bottom > 0) return r.bottom + 8
        }
        return (screenHeight * 0.30f).toInt()
    }

    /** Bottom edge for the blackout: top of the bottom tab bar. */
    fun igBottomNavTop(root: AccessibilityNodeInfo, screenHeight: Int): Int {
        val home = findByDesc(root, setOf("Home", "בית"))
        if (home != null) {
            val r = Rect()
            home.getBoundsInScreen(r)
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
