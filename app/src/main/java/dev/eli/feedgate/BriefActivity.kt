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
                findViewById<TextView>(R.id.briefMeta).apply {
                    text = getString(R.string.brief_meta, items.size)
                    visibility = View.VISIBLE
                }
                val inflater = LayoutInflater.from(this)
                var index = 0
                // One standalone card per topic, numbered across the whole brief.
                BriefRepo.TOPICS.filter { t -> items.any { it.topic == t.key } }.forEach { topic ->
                    val card = inflater.inflate(R.layout.brief_section_card, list, false)
                    card.findViewById<TextView>(R.id.sectionLabel).setText(topic.labelRes)
                    val holder = card.findViewById<LinearLayout>(R.id.sectionItems)
                    val topicItems = items.filter { it.topic == topic.key }
                    topicItems.forEachIndexed { i, item ->
                        index++
                        val row = inflater.inflate(R.layout.item_brief, holder, false)
                        row.findViewById<TextView>(R.id.itemIndex).text =
                            String.format(Locale.US, "%02d", index)
                        row.findViewById<TextView>(R.id.itemTitle).text = item.title
                        row.findViewById<TextView>(R.id.itemSource).text = item.source
                        item.image?.let {
                            ImageLoader.load(it, row.findViewById(R.id.itemThumb))
                        }
                        row.setOnClickListener {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)))
                            }
                        }
                        holder.addView(row)
                        if (i < topicItems.size - 1) {
                            holder.addView(inflater.inflate(R.layout.brief_divider, holder, false))
                        }
                    }
                    list.addView(card)
                }
                // Once-only staggered entrance (system animation scale honored).
                list.scheduleLayoutAnimation()
                endCard.visibility = View.VISIBLE
            }
        }.start()
    }
}
