package dev.eli.feedgate

import android.content.Context
import android.util.Log
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The anti-feed. Pulls a FINITE daily brief from a fixed set of curated RSS
 * feeds (verified live 2026-07-29) and never shows more than [MAX_ITEMS].
 * No ranking, no engagement loop — read it, it ends.
 *
 * Cache model: one JSON object per day mapping topic -> items. Each topic is
 * fetched at most once per day ONCE IT SUCCEEDS; topics that failed (empty)
 * are retried on the next open. Items are immutable for the day, so toggling
 * topics cannot mine fresh novelty.
 */
object BriefRepo {

    const val MAX_ITEMS = 12
    private const val PER_TOPIC = 4

    /** [source] is the display name — feed hosts lie (feedburner, hnrss). */
    data class Topic(val key: String, val labelRes: Int, val url: String, val source: String)

    val TOPICS = listOf(
        Topic("tech", R.string.topic_tech, "https://hnrss.org/frontpage", "news.ycombinator.com"),
        Topic("israel", R.string.topic_israel, "https://www.ynet.co.il/Integration/StoryRss2.xml", "ynet.co.il"),
        Topic("world", R.string.topic_world, "https://feeds.bbci.co.uk/news/world/rss.xml", "bbc.co.uk"),
        Topic("business", R.string.topic_business, "https://feeds.bbci.co.uk/news/business/rss.xml", "bbc.co.uk"),
        Topic("science", R.string.topic_science, "https://www.sciencedaily.com/rss/top/science.xml", "sciencedaily.com"),
        Topic("ai", R.string.topic_ai, "https://venturebeat.com/category/ai/feed/", "venturebeat.com"),
        Topic("security", R.string.topic_security, "https://feeds.feedburner.com/TheHackersNews", "thehackernews.com"),
        Topic("design", R.string.topic_design, "https://www.smashingmagazine.com/feed/", "smashingmagazine.com"),
        Topic("health", R.string.topic_health, "https://www.sciencedaily.com/rss/health_medicine.xml", "sciencedaily.com"),
        Topic("gym", R.string.topic_gym, "https://www.strongerbyscience.com/feed/", "strongerbyscience.com"),
    )

    data class Item(
        val topic: String,
        val title: String,
        val link: String,
        val source: String,
        val image: String? = null,
    )

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Blocking — call off the main thread. */
    fun todayBrief(context: Context, topicKeys: Set<String>): List<Item> {
        val cache = File(context.filesDir, "brief-${today()}.json")
        val store = readCache(cache)

        // Fetch only topics we don't have yet today (includes earlier failures).
        val missing = TOPICS.filter { it.key in topicKeys && store[it.key].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            val fetched = fetchParallel(missing)
            var changed = false
            fetched.forEach { (key, items) ->
                if (items.isNotEmpty()) {
                    store[key] = items
                    changed = true
                }
            }
            if (changed) {
                writeCache(cache, store)
                // Old days are removed only once today's cache exists — a
                // failed morning fetch must not destroy anything.
                cleanOldCaches(context, cache.name)
            }
        }

        // Round-robin interleave so one topic can't crowd out the rest.
        // Curated TOPICS order, not HashSet order — the MAX_ITEMS cut must
        // be deterministic.
        val lists = TOPICS.filter { it.key in topicKeys }
            .mapNotNull { store[it.key] }.filter { it.isNotEmpty() }
        val brief = mutableListOf<Item>()
        var i = 0
        while (brief.size < MAX_ITEMS && lists.any { i < it.size }) {
            for (list in lists) {
                if (i < list.size && brief.size < MAX_ITEMS) brief.add(list[i])
            }
            i++
        }
        return brief
    }

    /** All topics fetched concurrently under ONE overall 8s deadline. */
    private fun fetchParallel(topics: List<Topic>): Map<String, List<Item>> {
        val results = ConcurrentHashMap<String, List<Item>>()
        val threads = topics.map { t ->
            Thread { results[t.key] = fetchTopic(t) }.apply { start() }
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
        threads.forEach { it.join(maxOf(1L, deadline - android.os.SystemClock.uptimeMillis())) }
        return results
    }

    private fun fetchTopic(topic: Topic): List<Item> = try {
        val conn = URL(topic.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 6_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty(
            "User-Agent", "FeedGate/${BuildConfig.VERSION_NAME} (personal)"
        )
        val bytes = conn.inputStream.use { it.readBytes() }
        val charset = charsetOf(conn.contentType)
        conn.disconnect()
        parseRss(bytes, charset).take(PER_TOPIC).map { raw ->
            Item(topic.key, raw.title, raw.link, topic.source, raw.image)
        }
    } catch (t: Throwable) {
        Log.w(Detectors.TAG, "brief fetch failed for ${topic.key}: $t")
        emptyList()
    }

    /** charset from the Content-Type header, or null to let the prolog win. */
    private fun charsetOf(contentType: String?): Charset? = try {
        contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { Charset.forName(it) }
    } catch (t: Throwable) {
        null
    }

    private data class RawItem(val title: String, val link: String, val image: String?)

    private val IMG_IN_HTML = Regex("""<img[^>]+src=['"]([^'"]+)""")

    /**
     * Minimal RSS parser: item -> (title, link, image?). CDATA handled by
     * nextText(). Images come from media:thumbnail / media:content /
     * image enclosures, falling back to an <img> inside the description
     * (ynet's style). A malformed token mid-feed keeps whatever parsed
     * cleanly before it.
     */
    private fun parseRss(bytes: ByteArray, charset: Charset?): List<RawItem> {
        val out = mutableListOf<RawItem>()
        try {
            val parser = Xml.newPullParser()
            // null encoding => the parser honors the XML prolog / BOM.
            parser.setInput(java.io.ByteArrayInputStream(bytes), charset?.name())
            var inItem = false
            var title: String? = null
            var link: String? = null
            var image: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name ?: ""
                        when {
                            name.equals("item", true) -> {
                                inItem = true; title = null; link = null; image = null
                            }
                            inItem && name.equals("title", true) ->
                                title = parser.nextText().trim()
                            inItem && name.equals("link", true) ->
                                link = parser.nextText().trim()
                            inItem && (name.endsWith("thumbnail", true) ||
                                name.endsWith("enclosure", true) ||
                                name.equals("media:content", true)) -> {
                                val url = parser.getAttributeValue(null, "url")
                                val type = parser.getAttributeValue(null, "type") ?: "image"
                                if (image == null && url != null && type.startsWith("image")) {
                                    image = url
                                }
                            }
                            inItem && name.equals("description", true) && image == null ->
                                image = IMG_IN_HTML.find(parser.nextText())?.groupValues?.get(1)
                        }
                    }
                    XmlPullParser.END_TAG ->
                        if (parser.name.equals("item", true)) {
                            inItem = false
                            val t = title
                            val l = link
                            if (!t.isNullOrBlank() && !l.isNullOrBlank()) {
                                out.add(RawItem(t, l, image?.takeIf { it.startsWith("http") }))
                            }
                        }
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            Log.w(Detectors.TAG, "rss parse stopped early: $t")
        }
        return out
    }

    // Cache format: { "<topic>": [ {title, link, source}, ... ], ... }

    private fun readCache(file: File): MutableMap<String, List<Item>> {
        val map = mutableMapOf<String, List<Item>>()
        if (!file.exists()) return map
        try {
            val obj = JSONObject(file.readText())
            obj.keys().forEach { topic ->
                val arr = obj.getJSONArray(topic)
                map[topic] = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Item(
                        topic, o.getString("title"), o.getString("link"),
                        o.getString("source"), o.optString("image").takeIf { it.isNotEmpty() }
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(Detectors.TAG, "brief cache unreadable: $t")
        }
        return map
    }

    private fun writeCache(file: File, store: Map<String, List<Item>>) = runCatching {
        val obj = JSONObject()
        store.forEach { (topic, items) ->
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject().put("title", it.title).put("link", it.link)
                        .put("source", it.source).put("image", it.image ?: "")
                )
            }
            obj.put(topic, arr)
        }
        file.writeText(obj.toString())
    }

    private fun cleanOldCaches(context: Context, keep: String) {
        context.filesDir.listFiles()?.forEach {
            if (it.name.startsWith("brief-") && it.name != keep) it.delete()
        }
    }
}
