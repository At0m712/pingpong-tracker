package com.pingpong.tracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Zero-dependency async circular portrait loader for table tennis players.
 * Checks memory cache -> disk cache -> explicit photoUrl -> Wikipedia FR/EN REST summary
 * -> Wikipedia Search fallback (accent-tolerant).
 */
object PlayerPhotoLoader {

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memCache = LruCache<String, Bitmap>(24)

    fun loadInto(
        context: Context,
        playerName: String,
        explicitUrl: String,
        imageView: ImageView,
        fallbackView: View,
    ) {
        val key = cacheKey(playerName)
        imageView.tag = key

        val cached = memCache.get(key)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            imageView.visibility = View.VISIBLE
            fallbackView.visibility = View.GONE
            return
        }

        imageView.visibility = View.GONE
        fallbackView.visibility = View.VISIBLE

        val appContext = context.applicationContext
        executor.execute {
            val bitmap = loadFromDisk(appContext, key)
                ?: fetchAndCache(appContext, key, playerName, explicitUrl)

            if (bitmap != null) {
                memCache.put(key, bitmap)
                mainHandler.post {
                    if (imageView.tag == key) {
                        imageView.setImageBitmap(bitmap)
                        imageView.visibility = View.VISIBLE
                        fallbackView.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun cacheKey(name: String): String {
        return name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "player" }
    }

    private fun diskFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "player_photos")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$key.png")
    }

    private fun loadFromDisk(context: Context, key: String): Bitmap? {
        val f = diskFile(context, key)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchAndCache(
        context: Context,
        key: String,
        playerName: String,
        explicitUrl: String,
    ): Bitmap? {
        val imageUrl = explicitUrl.trim().ifEmpty {
            resolveWikipediaThumbnail(playerName, "fr")
                ?: resolveWikipediaThumbnail(playerName, "en")
                ?: searchWikipediaThumbnail(playerName, "fr", "tennis de table")
                ?: searchWikipediaThumbnail(playerName, "en", "table tennis")
                ?: ""
        }
        if (imageUrl.isEmpty()) return null

        val raw = downloadBitmap(imageUrl) ?: return null
        val circular = toCircularPortrait(raw, 192)
        if (raw != circular) raw.recycle()

        try {
            FileOutputStream(diskFile(context, key)).use { out ->
                circular.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
        } catch (_: Exception) {
            // Disk cache optional
        }
        return circular
    }

    private fun resolveWikipediaThumbnail(pageTitle: String, lang: String): String? {
        return try {
            val encoded = URLEncoder.encode(pageTitle.trim().replace(' ', '_'), "UTF-8")
            val url = URL("https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("User-Agent", "PingPongTracker-Android/1.0")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                val json = JSONObject(body)
                json.optJSONObject("thumbnail")?.optString("source", "")?.takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun searchWikipediaThumbnail(playerName: String, lang: String, sportHint: String): String? {
        return try {
            val q = URLEncoder.encode("$playerName $sportHint", "UTF-8")
            val url = URL("https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&srlimit=1&format=json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("User-Agent", "PingPongTracker-Android/1.0")
            }
            val title = try {
                if (conn.responseCode !in 200..299) return null
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                val arr = JSONObject(body).optJSONObject("query")?.optJSONArray("search")
                if (arr != null && arr.length() > 0) arr.optJSONObject(0)?.optString("title", "") else null
            } finally {
                conn.disconnect()
            }
            if (title.isNullOrBlank()) null else resolveWikipediaThumbnail(title, lang)
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7_000
                readTimeout = 7_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PingPongTracker-Android/1.0")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun toCircularPortrait(src: Bitmap, targetSize: Int): Bitmap {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = ((src.height - side) * 0.2f).toInt().coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        if (cropped != scaled) cropped.recycle()

        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radius = targetSize / 2f
        canvas.drawCircle(radius, radius, radius, paint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = 0x99F472B6.toInt()
        }
        canvas.drawCircle(radius, radius, radius - 2f, borderPaint)

        scaled.recycle()
        return output
    }
}
