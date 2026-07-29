package dev.eli.feedgate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The replacement destination for the doomscroll urge: a finite daily brief.
 * Loads once per day/topic-selection, shows at most [BriefRepo.MAX_ITEMS]
 * items, ends with a full stop. Re-fronted instances reload on day change.
 */
class BriefActivity : AppCompatActivity() {

    /** "<date>|<topics>" of what is currently rendered — reload key. */
    private var renderedKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_brief)
    }

    override fun onStart() {
        super.onStart()
        val topics = Prefs(this).briefTopics
        val key = "${BriefRepo.today()}|${topics.sorted().joinToString(",")}"
        if (key != renderedKey) {
            renderedKey = key
            render(topics)
        }
    }

    private fun render(topics: Set<String>) {
        findViewById<TextView>(R.id.briefDate).text =
            SimpleDateFormat("EEEE · d MMM", Locale.getDefault()).format(Date())

        val status = findViewById<TextView>(R.id.briefStatus)
        val list = findViewById<LinearLayout>(R.id.briefList)
        val endCard = findViewById<View>(R.id.briefEnd)
        list.removeAllViews()
        endCard.visibility = View.GONE
        status.visibility = View.VISIBLE

        if (topics.isEmpty()) {
            status.setText(R.string.brief_empty)
            return
        }

        status.setText(R.string.brief_loading)
        val myKey = renderedKey
        Thread {
            val items = BriefRepo.todayBrief(this, topics)
            runOnUiThread {
                // Drop stale results if the activity died or a newer render started.
                if (isFinishing || isDestroyed || renderedKey != myKey) return@runOnUiThread
                if (items.isEmpty()) {
                    status.setText(R.string.brief_offline)
                    return@runOnUiThread
                }
                status.visibility = View.GONE
                val inflater = LayoutInflater.from(this)
                items.forEach { item ->
                    val row = inflater.inflate(R.layout.item_brief, list, false)
                    row.findViewById<TextView>(R.id.itemSource).text = item.source
                    row.findViewById<TextView>(R.id.itemTitle).text = item.title
                    row.setOnClickListener {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)))
                        }
                    }
                    list.addView(row)
                }
                endCard.visibility = View.VISIBLE
            }
        }.start()
    }
}
