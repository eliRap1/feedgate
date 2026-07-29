package dev.eli.feedgate

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The blocker. Watches Instagram + TikTok windows and:
 *  - Instagram: blocks Reels viewer, Explore, and *scrolling* the home feed.
 *    Story tray, story viewer, DMs, camera all stay usable.
 *  - TikTok: blocks the For You / Friends feed; auto-clicks the Inbox tab
 *    so opening the app lands you on messages (streaks live there).
 */
class FeedGateService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var lastBlockAt = 0L

    /** Last time a DM surface was on screen — powers the one-reel DM grace. */
    private var lastDirectSeenAt = 0L
    /** True while the current Reels viewer session was entered from a DM. */
    private var dmGraceActive = false

    private fun passActive() = System.currentTimeMillis() < prefs.passUntil

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        Log.i(Detectors.TAG, "FeedGate service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return

        if (prefs.inspectorMode && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(Detectors.TAG, "===== window changed: $pkg / ${event.className} =====")
            Detectors.dumpTree(root)
        }

        try {
            when {
                pkg == Detectors.PKG_INSTAGRAM -> handleInstagram(event, root)
                pkg in Detectors.PKG_TIKTOK -> handleTikTok(root)
            }
        } catch (t: Throwable) {
            // Never crash the service over a detection hiccup.
            Log.w(Detectors.TAG, "detector error", t)
        }
    }

    // ---------- Instagram ----------

    private fun handleInstagram(event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        if (passActive()) return

        // Always-allowed surfaces short-circuit everything.
        if (Detectors.igDirectOpen(root)) {
            lastDirectSeenAt = System.currentTimeMillis()
            dmGraceActive = false
            return
        }
        if (Detectors.igStoryViewerOpen(root)) return

        if (prefs.blockIgReels && Detectors.igReelsOpen(root)) {
            // One-reel DM grace: entering the Reels viewer within a few seconds
            // of being in a DM means "she sent me this". Let it play, but the
            // first swipe to the NEXT reel ends the grace and blocks.
            if (prefs.dmGrace) {
                val now = System.currentTimeMillis()
                if (!dmGraceActive && now - lastDirectSeenAt < 8_000) {
                    dmGraceActive = true
                    Log.i(Detectors.TAG, "DM grace: allowing shared reel")
                }
                if (dmGraceActive) {
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                        dmGraceActive = false
                        block("Instagram Reels (swiped past shared reel)")
                    }
                    return
                }
            }
            block("Instagram Reels")
            return
        }
        dmGraceActive = false
        if (prefs.blockIgExplore && Detectors.igExploreOpen(root)) {
            block("Instagram Explore")
            return
        }
        // Feed scroll: story tray stays reachable, doomscrolling does not.
        if (prefs.blockIgFeedScroll &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            Detectors.igIsFeedScroll(event.source, root)
        ) {
            block("Instagram feed scroll")
        }
    }

    // ---------- TikTok ----------

    private fun handleTikTok(root: AccessibilityNodeInfo) {
        if (passActive()) return
        if (!prefs.blockTikTokFeed) return
        if (Detectors.ttInboxOpen(root) || Detectors.ttProfileOpen(root)) return
        if (!Detectors.ttFeedOpen(root)) return

        if (prefs.tikTokAutoInbox) {
            val inbox = Detectors.ttInboxTab(root)
            val clickable = generateSequence(inbox) { it.parent }
                .firstOrNull { it.isClickable }
            if (clickable != null) {
                Log.i(Detectors.TAG, "TikTok feed detected -> redirecting to Inbox")
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                flashOverlay(showBack = false)
                return
            }
        }
        block("TikTok feed")
    }

    // ---------- blocking machinery ----------

    private fun block(what: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockAt < 1200) return // debounce
        lastBlockAt = now
        Log.i(Detectors.TAG, "BLOCK: $what")
        flashOverlay(showBack = true)
    }

    /** Show the full-screen overlay briefly; optionally press Back underneath it. */
    private fun flashOverlay(showBack: Boolean) {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_block, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(view, lp)
            overlay = view
            // One quiet entrance: fade + settle. System animator scale is honored.
            view.findViewById<View>(R.id.overlayContent)?.let { content ->
                content.alpha = 0f
                content.scaleX = 0.97f
                content.scaleY = 0.97f
                content.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        } catch (t: Throwable) {
            Log.w(Detectors.TAG, "overlay failed", t)
        }
        if (showBack) performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            overlay?.let { runCatching { wm.removeView(it) } }
            overlay = null
        }, 900)
    }

    override fun onDestroy() {
        // Don't leak a stuck overlay if the service dies inside the 900ms window.
        handler.removeCallbacksAndMessages(null)
        overlay?.let { v ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        }
        overlay = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        // no-op
    }
}
