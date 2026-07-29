package dev.eli.feedgate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var stats: Stats
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var countdown: Runnable? = null
    private var countdownOwner = 0
    private val passTicker = Runnable { refreshPassStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        stats = Stats(this)

        bindSwitchRaw(R.id.swStats, stats.enabled) { stats.enabled = it }
        findViewById<Button>(R.id.btnStatsReset).setOnClickListener {
            stats.reset()
            refreshStats()
            Toast.makeText(this, R.string.stats_cleared, Toast.LENGTH_SHORT).show()
        }

        setupPassButton(R.id.btnPass10, 10)
        setupPassButton(R.id.btnPass30, 30)
        findViewById<TextView>(R.id.passStatus).setOnClickListener {
            if (System.currentTimeMillis() < prefs.passUntil) {
                prefs.passUntil = 0L
                Toast.makeText(this, getString(R.string.pass_ended), Toast.LENGTH_SHORT).show()
                refreshPassStatus()
            }
        }

        bindSwitch(R.id.swDmGrace, { prefs.dmGrace }, { prefs.dmGrace = it })
        bindSwitch(R.id.swIgReels, { prefs.blockIgReels }, { prefs.blockIgReels = it })
        bindSwitch(R.id.swIgExplore, { prefs.blockIgExplore }, { prefs.blockIgExplore = it })
        bindSwitch(R.id.swIgFeedScroll, { prefs.blockIgFeedScroll }, { prefs.blockIgFeedScroll = it })

        // Feed-scroll destination: Wall / DMs / Brief
        val destGroup = findViewById<MaterialButtonToggleGroup>(R.id.igDestGroup)
        destGroup.check(
            when (prefs.igFeedDest) {
                "dms" -> R.id.destDms
                "brief" -> R.id.destBrief
                else -> R.id.destWall
            }
        )
        destGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) prefs.igFeedDest = when (checkedId) {
                R.id.destDms -> "dms"
                R.id.destBrief -> "brief"
                else -> "wall"
            }
        }

        // Daybrief topics + entry
        val chips = findViewById<ChipGroup>(R.id.topicChips)
        BriefRepo.TOPICS.forEach { topic ->
            val chip = layoutInflater.inflate(R.layout.chip_topic, chips, false) as Chip
            chip.setText(topic.labelRes)
            chip.isChecked = topic.key in prefs.briefTopics
            chip.setOnCheckedChangeListener { _, checked ->
                val cur = prefs.briefTopics.toMutableSet()
                if (checked) cur.add(topic.key) else cur.remove(topic.key)
                prefs.briefTopics = cur
            }
            chips.addView(chip)
        }
        findViewById<Button>(R.id.btnOpenBrief).setOnClickListener {
            startActivity(Intent(this, BriefActivity::class.java))
        }
        bindSwitch(R.id.swTtFeed, { prefs.blockTikTokFeed }, { prefs.blockTikTokFeed = it })
        bindSwitch(R.id.swTtAutoInbox, { prefs.tikTokAutoInbox }, { prefs.tikTokAutoInbox = it })
        bindSwitch(R.id.swInspector, { prefs.inspectorMode }, { prefs.inspectorMode = it })

        findViewById<Button>(R.id.btnEnableService).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnIgDms).setOnClickListener {
            // Deep link straight into the Instagram DM inbox, skipping the feed.
            val direct = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct-inbox"))
                .setPackage("com.instagram.android")
            try {
                startActivity(direct)
            } catch (e: Exception) {
                // Fallback: https link routed to the IG app.
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/direct/inbox/"))
                            .setPackage("com.instagram.android")
                    )
                } catch (e2: Exception) {
                    Toast.makeText(this, getString(R.string.ig_not_installed), Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<Button>(R.id.btnShareDump).setOnClickListener {
            val src = java.io.File(filesDir, "inspector.txt")
            if (!src.exists()) {
                Toast.makeText(this, R.string.dump_missing, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val out = java.io.File(java.io.File(cacheDir, "updates").apply { mkdirs() }, "feedgate-tree.txt")
            src.copyTo(out, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", out
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    getString(R.string.btn_share_dump)
                )
            )
        }

        findViewById<TextView>(R.id.versionLine).text =
            getString(R.string.version_line, currentVersion())
        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            val rel = pendingRelease
            if (rel != null) startUpdateDownload(rel) else checkForUpdate(manual = true)
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        findViewById<TextView>(R.id.serviceStatus)
            .setText(if (enabled) R.string.status_running else R.string.status_off)
        findViewById<View>(R.id.statusDot)
            .setBackgroundResource(if (enabled) R.drawable.dot_on else R.drawable.dot_off)
        // The ember CTA only exists while there is something to act on.
        findViewById<Button>(R.id.btnEnableService).visibility =
            if (enabled) View.GONE else View.VISIBLE
        refreshPassStatus()
        refreshStats()
        findViewById<TextView>(R.id.verdictLine).text = prefs.lastVerdict
        // Quiet update check, at most once per 6h, only when opening the app.
        // Throttle recorded on SUCCESS so a failed check doesn't burn 6h.
        if (System.currentTimeMillis() - prefs.lastUpdateCheck > 6 * 60 * 60_000L) {
            checkForUpdate(manual = false)
        }
    }

    // ---------- in-app updates ----------

    private var pendingRelease: Updater.Release? = null

    private fun currentVersion(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"

    private fun checkForUpdate(manual: Boolean) {
        val btn = findViewById<Button>(R.id.btnUpdate)
        Thread {
            val rel = Updater.fetchLatest()
            runOnUiThread {
                if (rel != null) prefs.lastUpdateCheck = System.currentTimeMillis()
                when {
                    rel != null && Updater.isNewer(rel.tag, currentVersion()) -> {
                        pendingRelease = rel
                        btn.text = getString(R.string.btn_update_to, rel.tag)
                    }
                    manual && rel != null ->
                        Toast.makeText(this, R.string.update_none, Toast.LENGTH_SHORT).show()
                    manual ->
                        Toast.makeText(this, R.string.update_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun startUpdateDownload(rel: Updater.Release) {
        val btn = findViewById<Button>(R.id.btnUpdate)
        btn.isEnabled = false
        btn.setText(R.string.update_downloading)
        Thread {
            val apk = Updater.download(this, rel.apkUrl)
            runOnUiThread {
                btn.isEnabled = true
                if (apk != null) {
                    btn.text = getString(R.string.btn_update_to, rel.tag)
                    Updater.install(this, apk)
                } else {
                    btn.setText(R.string.btn_check_update)
                    pendingRelease = null
                    Toast.makeText(this, R.string.update_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Pass buttons use a 10-second visible countdown before activating —
     * deliberate friction so unlocking is a decision, not a reflex.
     * Tapping again during the countdown cancels it.
     */
    private fun setupPassButton(id: Int, minutes: Int) {
        val btn = findViewById<Button>(id)
        val label = btn.text
        btn.setOnClickListener {
            if (countdown != null) {
                // Tapping the counting button cancels; tapping the sibling
                // cancels it and starts this button's countdown instead.
                val wasThisButton = countdownOwner == id
                cancelCountdown()
                if (wasThisButton) return@setOnClickListener
            }
            var secondsLeft = 10
            val r = object : Runnable {
                override fun run() {
                    if (secondsLeft > 0) {
                        btn.text = getString(R.string.unlocking_in, secondsLeft)
                        secondsLeft--
                        handler.postDelayed(this, 1000)
                    } else {
                        prefs.passUntil = System.currentTimeMillis() + minutes * 60_000L
                        countdown = null
                        btn.text = label
                        refreshPassStatus()
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.pass_started, minutes),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            countdown = r
            countdownOwner = id
            handler.post(r)
        }
    }

    private fun cancelCountdown() {
        countdown?.let { handler.removeCallbacks(it) }
        countdown = null
        countdownOwner = 0
        findViewById<Button>(R.id.btnPass10).setText(R.string.btn_pass_10)
        findViewById<Button>(R.id.btnPass30).setText(R.string.btn_pass_30)
    }

    private fun refreshPassStatus() {
        // Single scheduled ticker — onResume and taps must not stack duplicates.
        handler.removeCallbacks(passTicker)
        val tv = findViewById<TextView>(R.id.passStatus)
        val left = prefs.passUntil - System.currentTimeMillis()
        if (left > 0) {
            tv.text = getString(R.string.pass_active, left / 60_000 + 1)
            tv.setTextColor(getColor(R.color.ember))
            tv.isClickable = true
            handler.postDelayed(passTicker, 30_000)
        } else {
            tv.setText(R.string.pass_none)
            tv.setTextColor(getColor(R.color.bone))
            // Nothing to end — don't announce a dead tap target.
            tv.isClickable = false
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen aborts an in-flight unlock — a pass must never
        // start while the user isn't looking at the countdown.
        if (countdown != null) cancelCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdown?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
    }

    private fun isServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flat.contains("$packageName/.FeedGateService") ||
            flat.contains("$packageName/${FeedGateService::class.java.name}")
    }

    private fun bindSwitch(id: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
    }

    private fun bindSwitchRaw(id: Int, checked: Boolean, set: (Boolean) -> Unit) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, c -> set(c) }
    }

    // ---------- the gate log ----------

    private val barIds = intArrayOf(
        R.id.barD0, R.id.barD1, R.id.barD2, R.id.barD3, R.id.barD4, R.id.barD5, R.id.barD6
    )
    private val labelIds = intArrayOf(
        R.id.barL0, R.id.barL1, R.id.barL2, R.id.barL3, R.id.barL4, R.id.barL5, R.id.barL6
    )

    /**
     * Static views only — values are written into them, nothing is inflated
     * here, so repeated resumes can't stack duplicate bars or rows.
     */
    private fun refreshStats() {
        val body = findViewById<View>(R.id.statBody)
        val empty = findViewById<View>(R.id.statEmpty)
        val since = stats.since()
        if (since == 0L || stats.total() == 0) {
            body.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        body.visibility = View.VISIBLE
        empty.visibility = View.GONE

        findViewById<TextView>(R.id.statToday).text = stats.today().toString()

        val week = stats.week()
        val labels = stats.weekLabels()
        val peak = maxOf(week.max(), 1)
        val full = resources.getDimensionPixelSize(R.dimen.bar_max)
        val floor = resources.getDimensionPixelSize(R.dimen.bar_floor)
        week.forEachIndexed { i, count ->
            val bar = findViewById<View>(barIds[i])
            bar.layoutParams = bar.layoutParams.apply {
                height = if (count == 0) floor else maxOf(floor, full * count / peak)
            }
            // Brightness carries the hierarchy — today is the brightest bar.
            bar.setBackgroundResource(
                if (i == week.lastIndex) R.color.bone_dim else R.color.bone_faint
            )
            findViewById<TextView>(labelIds[i]).text = labels[i]
        }

        row(R.id.rowReels, R.id.valReels, Stats.SURFACE_REELS)
        row(R.id.rowFeed, R.id.valFeed, Stats.SURFACE_FEED)
        row(R.id.rowExplore, R.id.valExplore, Stats.SURFACE_EXPLORE)
        row(R.id.rowTiktok, R.id.valTiktok, Stats.SURFACE_TIKTOK)

        val sinceText = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            .format(java.util.Date(since))
        findViewById<TextView>(R.id.statTotal).text =
            getString(R.string.stat_total, stats.total(), sinceText)
    }

    private fun row(rowId: Int, valueId: Int, surface: String) {
        val n = stats.countOf(surface)
        findViewById<View>(rowId).visibility = if (n == 0) View.GONE else View.VISIBLE
        if (n > 0) findViewById<TextView>(valueId).text = n.toString()
    }
}
