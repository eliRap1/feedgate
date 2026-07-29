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
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var countdown: Runnable? = null
    private var countdownOwner = 0
    private val passTicker = Runnable { refreshPassStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

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
        bindSwitch(R.id.swIgAutoDms, { prefs.igAutoDms }, { prefs.igAutoDms = it })
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
        // Quiet update check, at most once per 6h, only when opening the app.
        if (System.currentTimeMillis() - prefs.lastUpdateCheck > 6 * 60 * 60_000L) {
            prefs.lastUpdateCheck = System.currentTimeMillis()
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
}
