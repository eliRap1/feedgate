package dev.eli.feedgate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var countdown: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        setupPassButton(R.id.btnPass10, 10)
        setupPassButton(R.id.btnPass30, 30)
        findViewById<TextView>(R.id.passStatus).setOnClickListener {
            if (System.currentTimeMillis() < prefs.passUntil) {
                prefs.passUntil = 0L
                Toast.makeText(this, "Pass ended", Toast.LENGTH_SHORT).show()
                refreshPassStatus()
            }
        }

        bindSwitch(R.id.swDmGrace, { prefs.dmGrace }, { prefs.dmGrace = it })
        bindSwitch(R.id.swIgReels, { prefs.blockIgReels }, { prefs.blockIgReels = it })
        bindSwitch(R.id.swIgExplore, { prefs.blockIgExplore }, { prefs.blockIgExplore = it })
        bindSwitch(R.id.swIgFeedScroll, { prefs.blockIgFeedScroll }, { prefs.blockIgFeedScroll = it })
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
                    Toast.makeText(this, "Instagram not installed?", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        findViewById<TextView>(R.id.serviceStatus).text =
            if (enabled) "Service: running ✅" else "Service: OFF — enable it below"
        refreshPassStatus()
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
                // A countdown is running — treat any tap as cancel.
                cancelCountdown()
                return@setOnClickListener
            }
            var secondsLeft = 10
            val r = object : Runnable {
                override fun run() {
                    if (secondsLeft > 0) {
                        btn.text = "Unlocking in $secondsLeft… (tap to cancel)"
                        secondsLeft--
                        handler.postDelayed(this, 1000)
                    } else {
                        prefs.passUntil = System.currentTimeMillis() + minutes * 60_000L
                        countdown = null
                        btn.text = label
                        refreshPassStatus()
                        Toast.makeText(this@MainActivity, "$minutes-minute pass active", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            countdown = r
            handler.post(r)
        }
    }

    private fun cancelCountdown() {
        countdown?.let { handler.removeCallbacks(it) }
        countdown = null
        findViewById<Button>(R.id.btnPass10).text = "10-min pass"
        findViewById<Button>(R.id.btnPass30).text = "30-min pass"
    }

    private fun refreshPassStatus() {
        val tv = findViewById<TextView>(R.id.passStatus)
        val left = prefs.passUntil - System.currentTimeMillis()
        if (left > 0) {
            tv.text = "Pass active — ${left / 60_000 + 1} min left (tap to end early)"
            handler.postDelayed({ refreshPassStatus() }, 30_000)
        } else {
            tv.text = "No active pass"
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
