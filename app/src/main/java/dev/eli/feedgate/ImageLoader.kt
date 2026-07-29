package dev.eli.feedgate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Tiny thumbnail loader — enough for ≤12 images/day, no library needed.
 * Memory cache only; images re-download on process death, which is fine
 * for a once-a-day brief.
 */
object ImageLoader {

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, view: ImageView) {
        view.tag = url
        cache[url]?.let {
            view.setImageBitmap(it)
            view.visibility = ImageView.VISIBLE
            return
        }
        pool.execute {
            val bmp = fetch(url)
            if (bmp != null) {
                cache[url] = bmp
                main.post {
                    if (view.tag == url) {
                        view.setImageBitmap(bmp)
                        view.visibility = ImageView.VISIBLE
                    }
                }
            }
        }
    }

    private fun fetch(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 8_000
        conn.instanceFollowRedirects = true
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        // Subsample to thumbnail scale — don't hold full-size press photos.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 168)
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (t: Throwable) {
        Log.w(Detectors.TAG, "thumb load failed: $t")
        null
    }
}
