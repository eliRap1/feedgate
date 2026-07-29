package dev.eli.feedgate

import android.util.Log
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

    private fun findByDesc(root: AccessibilityNodeInfo, descs: Set<String>): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val d = n.contentDescription?.toString()
            if (d != null && descs.any { d.equals(it, ignoreCase = true) || d.startsWith("$it,") || d.startsWith("$it ") }) {
                return n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun hasIdSuffix(root: AccessibilityNodeInfo, vararg suffixes: String): Boolean =
        suffixes.any { findByIdSuffix(root, it) != null }

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

    /** True when the Reels (clips) viewer is on screen. */
    fun igReelsOpen(root: AccessibilityNodeInfo): Boolean {
        // Full-screen Reels surface.
        if (hasIdSuffix(root, ":id/clips_viewer_view_pager", ":id/clips_swipe_refresh_container",
                ":id/clips_video_container")) return true
        // Reels tab selected in the bottom bar.
        val tab = findByDesc(root, setOf("Reels"))
        return tab != null && tab.isSelected
    }

    /** True when Explore / search-grid is on screen. */
    fun igExploreOpen(root: AccessibilityNodeInfo): Boolean {
        if (hasIdSuffix(root, ":id/explore_grid_recycler_view", ":id/explore_topic_cluster_grid")) return true
        val tab = findByDesc(root, setOf("Search and explore", "Search and Explore", "Explore"))
        return tab != null && tab.isSelected
    }

    /** True when the home feed surface is on screen (story tray lives here too). */
    fun igHomeFeedOpen(root: AccessibilityNodeInfo): Boolean {
        // The DM inbox, story viewer and camera must NOT match:
        if (igStoryViewerOpen(root) || igDirectOpen(root)) return false
        val tab = findByDesc(root, setOf("Home"))
        if (tab != null && tab.isSelected) return true
        return hasIdSuffix(root, ":id/feed_swipe_refresh_layout", ":id/main_feed_recycler")
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
    fun igIsFeedScroll(source: AccessibilityNodeInfo?, root: AccessibilityNodeInfo): Boolean {
        if (source == null) return false
        val id = source.viewIdResourceName ?: ""
        // Story tray is a horizontal list; feed scrolls are the vertical feed recycler.
        if (id.endsWith(":id/stories_tray_recyclerview") || id.endsWith(":id/tray_recyclerview")) return false
        if (!igHomeFeedOpen(root)) return false
        // Heuristic: vertical scrollable container on the home surface.
        return source.className?.contains("RecyclerView") == true ||
            source.className?.contains("ListView") == true
    }

    // ---------- TikTok ----------

    /** True when the For You / Friends video feed is on screen. */
    fun ttFeedOpen(root: AccessibilityNodeInfo): Boolean {
        if (ttInboxOpen(root) || ttProfileOpen(root)) return false
        val home = findByDesc(root, setOf("Home"))
        if (home != null && home.isSelected) return true
        val friends = findByDesc(root, setOf("Friends"))
        return friends != null && friends.isSelected
    }

    /** Inbox tab (DMs live here) — ALLOWED. */
    fun ttInboxOpen(root: AccessibilityNodeInfo): Boolean {
        val tab = findByDesc(root, setOf("Inbox", "Messages"))
        return tab != null && tab.isSelected
    }

    fun ttProfileOpen(root: AccessibilityNodeInfo): Boolean {
        val tab = findByDesc(root, setOf("Profile", "Me"))
        return tab != null && tab.isSelected
    }

    /** The Inbox bottom-tab node, for auto-redirect clicking. */
    fun ttInboxTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByDesc(root, setOf("Inbox", "Messages"))
}
