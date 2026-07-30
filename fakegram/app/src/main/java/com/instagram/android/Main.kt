package com.instagram.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.TextView

/**
 * FakeGram — behavioral test double for FeedGate.
 * States and their accessibility signatures mirror the real Instagram
 * dumps (2026-07-29): tabbed surfaces keep the bottom bar; story viewer
 * and shared reels remove it entirely.
 */
class Main : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var tabs: LinearLayout
    private var state = "home"

    // Real Instagram emits WINDOW_CONTENT_CHANGED constantly (video UI).
    // A static fake emits nothing after a state swap, so detectors never
    // re-evaluate — this ticker keeps the event stream alive.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var ticker: TextView
    private var tick = 0
    /**
     * Real Instagram emits events constantly (video UI). This ticker fakes
     * that — but it STOPS after a few ticks so the screen goes completely
     * silent, reproducing the "quiet feed" case where FeedGate gets no
     * events at all and the blackout must still attach on its own.
     */
    private val tickRun = object : Runnable {
        override fun run() {
            tick++
            ticker.text = "t$tick"
            if (tick < 5) handler.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        content = FrameLayout(this)
        root.addView(
            content,
            FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = dp(56) }
        )
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            tabs,
            FrameLayout.LayoutParams(MATCH, dp(56), Gravity.BOTTOM)
        )
        tab(R.id.feed_tab, "Home") { showHome() }
        tab(R.id.clips_tab, "Reels") { showReels() }
        tab(R.id.direct_tab, "Message") { showDm() }
        tab(R.id.search_tab, "Search and explore") { showExplore() }
        tab(R.id.profile_tab, "Profile") { showProfile() }
        ticker = TextView(this).apply {
            setTextColor(Color.GRAY)
            textSize = 10f
        }
        root.addView(
            ticker,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
        )
        setContentView(root)
        showHome()
        handler.post(tickRun)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun tab(id: Int, desc: String, onClick: () -> Unit) {
        val f = FrameLayout(this).apply {
            this.id = id
            contentDescription = desc
            isClickable = true
            setOnClickListener { onClick() }
            addView(TextView(this@Main).apply {
                text = desc.take(6)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        tabs.addView(f, LinearLayout.LayoutParams(0, MATCH, 1f))
    }

    private fun select(id: Int) {
        for (i in 0 until tabs.childCount) {
            val t = tabs.getChildAt(i)
            t.isSelected = t.id == id
        }
    }

    private fun setState(name: String, tabsVisible: Boolean, sel: Int, view: View) {
        state = name
        tabs.visibility = if (tabsVisible) View.VISIBLE else View.GONE
        if (tabsVisible) select(sel)
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun showHome() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        col.addView(FrameLayout(this).apply {
            id = R.id.action_bar_title_view
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(TextView(this@Main).apply {
                id = R.id.title_logo
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Instagram Home feed"
                text = "Instagram"
                textSize = 24f
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
        })
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            contentDescription = "reels tray container"
            setBackgroundColor(Color.rgb(255, 200, 120))
            isClickable = true
            setOnClickListener { showStory() }
            addView(TextView(this@Main).apply {
                text = "STORY TRAY (tap = story)"
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, MATCH))
        }, LinearLayout.LayoutParams(MATCH, dp(96)))
        col.addView(ListView(this).apply {
            adapter = ArrayAdapter(
                this@Main, android.R.layout.simple_list_item_1,
                (1..40).map { "FEED POST $it — should be behind the blackout" }
            )
        }, LinearLayout.LayoutParams(MATCH, MATCH))
        setState("home", true, R.id.feed_tab, col)
        // Real Instagram clears EVERY tab's selected flag while the feed
        // loads (dump 2026-07-30), then restores it. Replicate that window:
        // the blackout must cover during it, not only after.
        select(0)
        handler.postDelayed({ if (state == "home") select(R.id.feed_tab) }, 3_000)
    }

    private fun showReels() {
        val f = FrameLayout(this).apply {
            id = R.id.clips_viewer_view_pager
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(Color.rgb(120, 20, 20))
            addView(TextView(this@Main).apply {
                text = "REELS BROWSING (clips_tab selected)"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setState("reels", true, R.id.clips_tab, f)
    }

    private fun showDm() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        col.addView(Button(this).apply {
            text = "OPEN SHARED REEL"
            setOnClickListener { showSharedReel() }
        })
        col.addView(ListView(this).apply {
            id = R.id.inbox_refreshable_thread_list_recyclerview
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            adapter = ArrayAdapter(
                this@Main, android.R.layout.simple_list_item_1,
                (1..15).map { "DM THREAD $it" }
            )
        }, LinearLayout.LayoutParams(MATCH, MATCH))
        setState("dm", true, R.id.direct_tab, col)
    }

    /** Shared reel: NO tab bar (dump signature), scrollable clips pager. */
    private fun showSharedReel() {
        val scroll = ScrollView(this).apply {
            id = R.id.clips_viewer_view_pager
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(Color.rgb(60, 20, 90))
            addView(LinearLayout(this@Main).apply {
                orientation = LinearLayout.VERTICAL
                (1..8).forEach { n ->
                    addView(TextView(this@Main).apply {
                        text = "SHARED REEL $n (swipe down = next)"
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(MATCH, dp(700)))
                }
            })
        }
        val wrap = FrameLayout(this).apply {
            id = R.id.clips_video_container
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setState("shared", false, 0, wrap)
    }

    private fun showStory() {
        val v = FrameLayout(this).apply {
            id = R.id.reel_viewer_root
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(Color.rgb(20, 90, 40))
            addView(TextView(this@Main).apply {
                text = "STORY VIEWER (allowed)"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setState("story", false, 0, v)
    }

    private fun showExplore() {
        setState("explore", true, R.id.search_tab, TextView(this).apply {
            text = "EXPLORE GRID"
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(200, 200, 240))
        })
    }

    private fun showProfile() {
        setState("profile", true, R.id.profile_tab, TextView(this).apply {
            text = "PROFILE"
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
        })
    }

    @Deprecated("test double")
    override fun onBackPressed() {
        when (state) {
            "shared" -> showDm()
            "story" -> showHome()
            "home" -> super.onBackPressed()
            else -> showHome()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
