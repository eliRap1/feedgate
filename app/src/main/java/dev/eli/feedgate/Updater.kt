package dev.eli.feedgate

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal self-updater against the public GitHub releases feed.
 * Network is used ONLY here, only over HTTPS to github.com, and only when
 * triggered from the app (open-time check is throttled to once per 6h).
 */
object Updater {

    private const val API =
        "https://api.github.com/repos/eliRap1/feedgate/releases/latest"

    data class Release(val tag: String, val apkUrl: String)

    /** Blocking — call off the main thread. Null on any failure. */
    fun fetchLatest(): Release? = try {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = JSONObject(body)
        val tag = json.getString("tag_name")
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                apkUrl = a.getString("browser_download_url")
                break
            }
        }
        apkUrl?.let { Release(tag, it) }
    } catch (t: Throwable) {
        Log.w(Detectors.TAG, "update check failed: $t")
        null
    }

    /** "v1.10" vs "1.9" → true. Numeric per-part compare, not string compare. */
    fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Blocking download to cache — call off the main thread. Null on failure. */
    fun download(context: Context, url: String): File? = try {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "feedgate-update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        out
    } catch (t: Throwable) {
        Log.w(Detectors.TAG, "update download failed: $t")
        null
    }

    /** Hand the APK to the system installer. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                )
        )
    }
}
