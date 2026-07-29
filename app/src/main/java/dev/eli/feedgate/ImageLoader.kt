package dev.eli.feedgate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Tiny thumbnail loader — enough for ≤12 images/day, no library needed.
 * Bounded LRU cache, size-capped downloads (feed-controlled URLs), and
 * worker threads that expire so the accessibility-service process doesn't
 * hold them forever.
 */
object ImageLoader {

    private const val MAX_BYTES = 1_000_000
    private const val MIN_PX = 48

    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val pool = ThreadPoolExecutor(
        0, 3, 20L, TimeUnit.SECONDS, LinkedBlockingQueue()
    )
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, view: ImageView) {
        view.tag = url
        cache.get(url)?.let {
            view.setImageBitmap(it)
            view.visibility = ImageView.VISIBLE
            return
        }
        pool.execute {
            val bmp = fetch(url)
            if (bmp != null) {
                cache.put(url, bmp)
                main.post {
                    if (view.tag == url) {
                        view.setImageBitmap(bmp)
                        view.visibility = ImageView.VISIBLE
                    }
                }
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        return try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 8_000
        conn.instanceFollowRedirects = true
        val bytes = conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_BYTES) break // feed-controlled URL — cap it
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        conn.disconnect()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            // Tracking pixels and spacers are not thumbnails.
            if (bounds.outWidth < MIN_PX || bounds.outHeight < MIN_PX) return null
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
}
