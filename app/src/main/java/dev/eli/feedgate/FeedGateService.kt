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

    private val prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var lastBlockAt = 0L
    private var lastAutoInboxAt = 0L

    /** Last time a DM surface was on screen — powers the one-reel DM grace. */
    private var lastDirectSeenAt = 0L
    /** True while the current Reels viewer session was entered from a DM. */
    private var dmGraceActive = false

    private fun passActive() = System.currentTimeMillis() < prefs.passUntil

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(Detectors.TAG, "FeedGate service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return
        // The event's package and the active window can disagree (system UI
        // over the app, split screen) — never run detectors on the wrong tree.
        if (root.packageName?.toString() != pkg) return

        try {
            if (prefs.inspectorMode && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.d(Detectors.TAG, "===== window changed: $pkg / ${event.className} =====")
                Detectors.dumpTree(root)
            }
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
        // DM bookkeeping runs even during a pass so the grace timestamp never
        // freezes and grace works right after a pass expires.
        if (Detectors.igDirectOpen(root)) {
            lastDirectSeenAt = System.currentTimeMillis()
            dmGraceActive = false
            return
        }
        if (passActive()) return
        if (Detectors.igStoryViewerOpen(root)) return

        if (prefs.blockIgReels && Detectors.igReelsOpen(root)) {
            // One-reel DM grace: entering the Reels viewer within a few seconds
            // of being in a DM means "she sent me this". Let it play, but the
            // first swipe to the NEXT reel consumes the grace and blocks.
            if (prefs.dmGrace) {
                val now = System.currentTimeMillis()
                if (!dmGraceActive && now - lastDirectSeenAt < 8_000) {
                    dmGraceActive = true
                    // Consume the window: one DM visit buys exactly one grace,
                    // and the swipe-block below cannot re-arm it.
                    lastDirectSeenAt = 0L
                    Log.i(Detectors.TAG, "DM grace: allowing shared reel")
                }
                if (dmGraceActive) {
                    // Only a swipe on the Reels pager ends the grace — comment
                    // sheets and share trays emit scroll events too.
                    val src = event.source
                    val pagerScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                        (src?.viewIdResourceName?.contains("clips") == true ||
                            src?.className?.contains("ViewPager") == true)
                    if (pagerScroll) {
                        dmGraceActive = false
                        block("Instagram Reels (swiped past shared reel)")
                    }
                    return
                }
            }
            block("Instagram Reels")
            return
        }
        // Leaving Reels for a browsing surface ends any remaining grace.
        if (dmGraceActive &&
            (Detectors.igHomeFeedOpen(root) || Detectors.igExploreOpen(root))
        ) {
            dmGraceActive = false
        }
        if (prefs.blockIgExplore && Detectors.igExploreOpen(root)) {
            block("Instagram Explore")
            return
        }
        // Feed scroll: story tray stays reachable, doomscrolling does not.
        // No Back press here — the home surface hosts the story tray, so
        // bouncing out would break stories. Flash the wall, and (TikTok-style)
        // whisk over to the DM inbox if enabled.
        if (prefs.blockIgFeedScroll &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            Detectors.igIsFeedScroll(event, root)
        ) {
            val now = System.currentTimeMillis()
            if (now - lastBlockAt < 1200) return // same debounce as block()
            lastBlockAt = now
            Log.i(Detectors.TAG, "BLOCK: Instagram feed scroll")
            if (prefs.igAutoDms && openIgDms()) {
                Log.i(Detectors.TAG, "Instagram feed scroll -> redirecting to DMs")
            }
            flashOverlay(showBack = false)
        }
    }

    /** Deep-link into the Instagram DM inbox. Returns false if IG rejects it. */
    private fun openIgDms(): Boolean = try {
        startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("instagram://direct-inbox")
            )
                .setPackage(Detectors.PKG_INSTAGRAM)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (t: Throwable) {
        Log.w(Detectors.TAG, "DM deep link failed", t)
        false
    }

    // ---------- TikTok ----------

    private fun handleTikTok(root: AccessibilityNodeInfo) {
        if (passActive()) return
        if (!prefs.blockTikTokFeed) return
        if (Detectors.ttInboxOpen(root) || Detectors.ttProfileOpen(root)) return
        if (!Detectors.ttFeedOpen(root)) return

        if (prefs.tikTokAutoInbox) {
            val now = System.currentTimeMillis()
            // Cooldown: give the click time to land instead of re-clicking on
            // every content-changed event while the tab switch animates.
            if (now - lastAutoInboxAt < 2_000) return
            val inbox = Detectors.ttInboxTab(root)
            val clickable = generateSequence(inbox) { it.parent }
                .firstOrNull { it.isClickable }
            if (clickable != null) {
                lastAutoInboxAt = now
                Log.i(Detectors.TAG, "TikTok feed detected -> redirecting to Inbox")
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                flashOverlay(showBack = false)
                return
            }
        }
        block("TikTok feed")
    }

    // ---------- blocking machinery ----------

    private fun block(what: String, showBack: Boolean = true) {
        val now = System.currentTimeMillis()
        if (now - lastBlockAt < 1200) return // debounce
        lastBlockAt = now
        Log.i(Detectors.TAG, "BLOCK: $what")
        flashOverlay(showBack)
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
            val v = overlay ?: return@postDelayed
            overlay = null
            // Exit subtler and faster than the entrance — no hard pop.
            v.animate().alpha(0f).setDuration(120)
                .withEndAction { runCatching { wm.removeView(v) } }
                .start()
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
