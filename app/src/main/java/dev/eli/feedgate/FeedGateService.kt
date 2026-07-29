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
    private var lastFileDumpAt = 0L
    private var lastVerdictAt = 0L

    /** Last time a DM surface was on screen — powers the one-reel DM grace. */
    private var lastDirectSeenAt = 0L
    /** True while the current Reels viewer session was entered from a DM. */
    private var dmGraceActive = false
    /** When the current grace was granted — the pager fires a "settle" scroll
     *  right as the reel opens, which must not count as the ending swipe. */
    private var graceStartedAt = 0L
    /** No new grace shortly after a swipe-block — blocks exit re-arming. */
    private var graceCooldownUntil = 0L

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
            if (prefs.inspectorMode) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    Log.d(Detectors.TAG, "===== window changed: $pkg / ${event.className} =====")
                    Detectors.dumpTree(root)
                }
                val now = System.currentTimeMillis()
                if (now - lastFileDumpAt > 3_000) {
                    lastFileDumpAt = now
                    saveInspectorDump(pkg, root)
                }
            }
            when {
                pkg == Detectors.PKG_INSTAGRAM -> {
                    // Live verdict for the Debug card — screenshot-friendly
                    // diagnosis without digging through dump files.
                    val now = System.currentTimeMillis()
                    if (now - lastVerdictAt > 1_000) {
                        lastVerdictAt = now
                        runCatching {
                            prefs.lastVerdict =
                                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                    .format(java.util.Date()) + "  " + Detectors.debugState(root)
                        }
                    }
                    handleInstagram(event, root)
                }
                pkg in Detectors.PKG_TIKTOK -> handleTikTok(root)
            }
        } catch (t: Throwable) {
            // Never crash the service over a detection hiccup.
            Log.w(Detectors.TAG, "detector error", t)
        }
    }

    // ---------- Instagram ----------

    private fun handleInstagram(event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        // The feed blackout panel tracks the home surface on every event.
        updateFeedCover(root)
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
                // Browsing Reels deliberately (bottom tab) never rides grace.
                if (dmGraceActive && Detectors.igReelsTabSelected(root)) {
                    dmGraceActive = false
                }
                // Shared-reel signature (dump-verified): the clips viewer is
                // on screen and the ENTIRE bottom tab bar is gone from the
                // tree. Browsing keeps clips_tab selected; the post-Back
                // transition keeps feed_tab selected — both have the bar.
                val fromShare = !Detectors.igTabBarPresent(root) &&
                    now > graceCooldownUntil
                if (!dmGraceActive && (now - lastDirectSeenAt < 8_000 || fromShare)) {
                    dmGraceActive = true
                    graceStartedAt = now
                    // Consume the window: one DM visit buys exactly one grace,
                    // and the swipe-block below cannot re-arm it.
                    lastDirectSeenAt = 0L
                    Log.i(Detectors.TAG, "DM grace: allowing shared reel")
                }
                if (dmGraceActive) {
                    // Only a swipe on the Reels pager ends the grace — comment
                    // sheets and share trays emit scroll events too, and the
                    // pager itself emits a settle-scroll while the reel OPENS,
                    // which must not kill the grace instantly (2s shield).
                    val src = event.source
                    val pagerScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                        (src?.viewIdResourceName?.contains("clips") == true ||
                            src?.className?.contains("ViewPager") == true)
                    if (pagerScroll && now - graceStartedAt > 2_000) {
                        dmGraceActive = false
                        // Don't instantly re-arm during the exit transition.
                        graceCooldownUntil = now + 3_000
                        block("Instagram Reels (swiped past shared reel)")
                    }
                    return
                }
            }
            block("Instagram Reels")
            return
        }
        // Grace lives only while the reel viewer is actually on screen.
        dmGraceActive = false
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
            Log.i(Detectors.TAG, "BLOCK: Instagram feed scroll -> ${prefs.igFeedDest}")
            // Overlay FIRST: a visible window of ours exempts the redirect
            // from Android's background-activity-launch restrictions.
            flashOverlay(showBack = false)
            when (prefs.igFeedDest) {
                "dms" -> openIgDms()
                "brief" -> openBrief()
            }
        }
    }

    /** Land the doomscroll urge on the finite Daybrief instead. */
    private fun openBrief() = runCatching {
        startActivity(
            android.content.Intent(this, BriefActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------- persistent feed blackout ----------

    private var feedCover: View? = null
    private var feedCoverTop = -1
    private var feedCoverBottom = -1

    /**
     * Instagram-only events reach this service, so leaving Instagram emits
     * nothing — this poll retires the panel when IG is no longer in front.
     */
    private val coverPoll = object : Runnable {
        override fun run() {
            val root = rootInActiveWindow
            val onHome = root != null &&
                root.packageName?.toString() == Detectors.PKG_INSTAGRAM &&
                runCatching { Detectors.igHomeFeedOpen(root) }.getOrDefault(false)
            if (!onHome || passActive() || !prefs.blockIgFeedScroll) {
                removeFeedCover()
            } else {
                handler.postDelayed(this, 700)
            }
        }
    }

    private fun updateFeedCover(root: AccessibilityNodeInfo) {
        val want = prefs.blockIgFeedScroll && !passActive() &&
            runCatching { Detectors.igHomeFeedOpen(root) }.getOrDefault(false)
        if (!want) {
            removeFeedCover()
            return
        }
        val screenH = resources.displayMetrics.heightPixels
        val top = Detectors.igFeedCoverTop(root, screenH)
        val bottom = Detectors.igBottomNavTop(root, screenH)
        if (bottom - top < 200) return // implausible geometry — don't cover
        showFeedCover(top, bottom)
    }

    private fun showFeedCover(top: Int, bottom: Int) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        feedCover?.let { existing ->
            if (kotlin.math.abs(top - feedCoverTop) > 12 ||
                kotlin.math.abs(bottom - feedCoverBottom) > 12
            ) {
                val lp = existing.layoutParams as WindowManager.LayoutParams
                lp.y = top
                lp.height = bottom - top
                runCatching { wm.updateViewLayout(existing, lp) }
                feedCoverTop = top
                feedCoverBottom = bottom
            }
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_feed_cover, null)
        view.findViewById<View>(R.id.coverBrief)?.setOnClickListener { openBrief() }
        view.findViewById<View>(R.id.coverDms)?.setOnClickListener { openIgDms() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            bottom - top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        )
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.y = top
        runCatching {
            wm.addView(view, lp)
            feedCover = view
            feedCoverTop = top
            feedCoverBottom = bottom
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(160).start()
            handler.removeCallbacks(coverPoll)
            handler.postDelayed(coverPoll, 700)
        }
    }

    private fun removeFeedCover() {
        handler.removeCallbacks(coverPoll)
        val v = feedCover ?: return
        feedCover = null
        feedCoverTop = -1
        feedCoverBottom = -1
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
    }

    /** Rolling on-device inspector dump — shareable from the app, no adb. */
    private fun saveInspectorDump(pkg: String, root: AccessibilityNodeInfo) = runCatching {
        val f = java.io.File(filesDir, "inspector.txt")
        val sb = StringBuilder()
        sb.append("\n===== ")
            .append(
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
            )
            .append(' ').append(pkg).append(" =====\n")
        if (pkg == Detectors.PKG_INSTAGRAM) {
            sb.append("VERDICTS: ").append(Detectors.debugState(root)).append('\n')
        }
        Detectors.buildTree(root, sb)
        f.appendText(sb.toString())
        if (f.length() > 900_000) f.writeText(f.readText().takeLast(400_000))
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
        // Don't leak stuck overlays if the service dies mid-flight.
        handler.removeCallbacksAndMessages(null)
        overlay?.let { v ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        }
        overlay = null
        removeFeedCover()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // no-op
    }
}
