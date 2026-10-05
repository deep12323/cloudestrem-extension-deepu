package com.torrserve

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method

object TorrServeManager {
    private const val PREFS_NAME = "TorrServePluginPrefs"
    private const val KEY_CACHE_MB = "cache_size_mb"
    private const val KEY_PRELOAD_PERCENT = "preload_percent"
    private const val KEY_READ_AHEAD_PERCENT = "read_ahead_percent"
    private const val KEY_USE_DISK = "use_disk"
    private const val KEY_DISABLE_UPLOAD = "disable_upload"

    const val DEFAULT_CACHE_MB = 128L
    const val DEFAULT_PRELOAD_PERCENT = 15
    const val DEFAULT_READ_AHEAD_PERCENT = 95
    const val DEFAULT_USE_DISK = false
    const val DEFAULT_DISABLE_UPLOAD = false

    val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v")
    val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    data class TorrentFileInfo(
        val id: Int,
        val path: String,
        val length: Long,
        val extension: String
    )

    private var activeServerUrl: String? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCacheSizeMb(context: Context): Long {
        return getPrefs(context).getLong(KEY_CACHE_MB, DEFAULT_CACHE_MB)
    }

    fun setCacheSizeMb(context: Context, mb: Long) {
        getPrefs(context).edit().putLong(KEY_CACHE_MB, mb).apply()
    }

    fun getPreloadPercent(context: Context): Int {
        return getPrefs(context).getInt(KEY_PRELOAD_PERCENT, DEFAULT_PRELOAD_PERCENT)
    }

    fun setPreloadPercent(context: Context, percent: Int) {
        getPrefs(context).edit().putInt(KEY_PRELOAD_PERCENT, percent.coerceIn(0, 100)).apply()
    }

    fun getReadAheadPercent(context: Context): Int {
        return getPrefs(context).getInt(KEY_READ_AHEAD_PERCENT, DEFAULT_READ_AHEAD_PERCENT)
    }

    fun setReadAheadPercent(context: Context, percent: Int) {
        getPrefs(context).edit().putInt(KEY_READ_AHEAD_PERCENT, percent.coerceIn(0, 100)).apply()
    }

    fun getUseDisk(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USE_DISK, DEFAULT_USE_DISK)
    }

    fun setUseDisk(context: Context, useDisk: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USE_DISK, useDisk).apply()
    }

    fun getDisableUpload(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DISABLE_UPLOAD, DEFAULT_DISABLE_UPLOAD)
    }

    fun setDisableUpload(context: Context, disable: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DISABLE_UPLOAD, disable).apply()
    }

    private var lastConfiguredUrl: String? = null
    private var isWatcherRunning = false

    fun startAutoConfigDaemon(context: Context) {
        if (isWatcherRunning) return
        isWatcherRunning = true

        CoroutineScope(Dispatchers.IO).launch {
            // 1. Pre-warm / pre-configure immediately on app start
            runCatching {
                val url = getOrStartServer(context)
                val ok = applyBufferSettings(url, context)
                if (ok) lastConfiguredUrl = url
            }

            // 2. Continuously watch for any new TorrServer instance started by CloudStream or other extensions
            while (true) {
                try {
                    val currentUrl = getInbuiltUrlFromReflection()
                        ?: if (isServerAlive("http://127.0.0.1:8090")) "http://127.0.0.1:8090" else null

                    if (currentUrl != null && currentUrl != lastConfiguredUrl) {
                        if (isServerAlive(currentUrl)) {
                            activeServerUrl = currentUrl
                            val applied = applyBufferSettings(currentUrl, context)
                            if (applied) {
                                lastConfiguredUrl = currentUrl
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // silently handle
                }
                delay(800)
            }
        }
    }

    suspend fun getOrStartServer(context: Context?): String {
        activeServerUrl?.let { url ->
            if (isServerAlive(url)) return url
        }

        getInbuiltUrlFromReflection()?.let { url ->
            if (isServerAlive(url)) {
                activeServerUrl = url
                context?.let { applyBufferSettings(url, it) }
                return url
            }
        }

        if (context != null) {
            val bootedUrl = startInbuiltTorrServer(context)
            if (bootedUrl != null && isServerAlive(bootedUrl)) {
                activeServerUrl = bootedUrl
                applyBufferSettings(bootedUrl, context)
                return bootedUrl
            }
        }

        val externalUrl = "http://127.0.0.1:8090"
        if (isServerAlive(externalUrl)) {
            activeServerUrl = externalUrl
            context?.let { applyBufferSettings(externalUrl, it) }
            return externalUrl
        }

        throw IllegalStateException("Neither CloudStream's inbuilt torrent engine nor external TorrServer (port 8090) is running.")
    }

    private suspend fun isServerAlive(url: String): Boolean {
        return try {
            app.get("$url/echo", timeout = 2L).code == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun getInbuiltUrlFromReflection(): String? {
        return try {
            val torrentClass = Class.forName("com.lagradost.cloudstream3.ui.player.Torrent")
            val field: Field = torrentClass.getDeclaredField("TORRENT_SERVER_URL")
            field.isAccessible = true
            val url = field.get(null) as? String
            if (!url.isNullOrBlank()) url else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun startInbuiltTorrServer(context: Context): String? {
        return try {
            val torrServerClass = Class.forName("torrServer.TorrServer")
            val startMethod: Method = torrServerClass.getDeclaredMethod(
                "startTorrentServer",
                String::class.java,
                Long::class.javaPrimitiveType
            )

            val cachePath = context.cacheDir.absolutePath
            val port = startMethod.invoke(null, cachePath, 0L) as? Long ?: return null

            if (port > 0) {
                val url = "http://127.0.0.1:$port"

                runCatching {
                    val torrentClass = Class.forName("com.lagradost.cloudstream3.ui.player.Torrent")
                    val field = torrentClass.getDeclaredField("TORRENT_SERVER_URL")
                    field.isAccessible = true
                    field.set(null, url)
                }

                runCatching {
                    val addTrackersMethod = torrServerClass.getDeclaredMethod("addTrackers", String::class.java)
                    addTrackersMethod.invoke(null, "udp://tracker.opentrackr.org:1337/announce\nhttps://tracker2.ctix.cn/announce")
                }

                url
            } else {
                null
            }
        } catch (e: Throwable) {
            logError(e)
            null
        }
    }

    suspend fun applyBufferSettings(serverUrl: String, context: Context): Boolean {
        val cacheMb = getCacheSizeMb(context)
        val preload = getPreloadPercent(context)
        val readAhead = getReadAheadPercent(context)
        val useDisk = getUseDisk(context)
        val disableUpload = getDisableUpload(context)

        return try {
            val cacheBytes = cacheMb * 1024L * 1024L

            val sets = JSONObject().apply {
                put("CacheSize", cacheBytes)
                put("PreloadCache", preload)
                put("ReaderReadAHead", readAhead)
                put("UseDisk", useDisk)
                put("DisableUpload", disableUpload)
                if (disableUpload) {
                    put("UploadRateLimit", 1)
                } else {
                    put("UploadRateLimit", 0)
                }
            }

            val payload = JSONObject().apply {
                put("action", "set")
                put("sets", sets)
            }

            val res = app.post(
                "$serverUrl/settings",
                json = payload,
                timeout = 5L
            )
            res.code == 200
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    fun applySettingsAsync(context: Context, onComplete: ((Boolean, String?) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            var appliedUrl: String? = null
            val success = runCatching {
                val url = getOrStartServer(context)
                appliedUrl = url
                val ok = applyBufferSettings(url, context)
                if (ok) lastConfiguredUrl = url
                ok
            }.getOrDefault(false)

            if (onComplete != null) {
                withContext(Dispatchers.Main) {
                    onComplete(success, appliedUrl)
                }
            }
        }
    }

    suspend fun addTorrent(serverUrl: String, torrentUrl: String, title: String = "CloudStream Stream"): String {
        val payload = JSONObject().apply {
            put("action", "add")
            put("link", torrentUrl)
            put("title", title)
            put("save_to_db", false)
        }

        val response = app.post(
            "$serverUrl/torrents",
            json = payload,
            timeout = 10L
        ).text

        return JSONObject(response).getString("hash")
    }

    suspend fun waitForFiles(serverUrl: String, hash: String, maxWaitSeconds: Int = 15): List<TorrentFileInfo> {
        val maxAttempts = maxWaitSeconds * 2
        var attempts = 0

        while (attempts < maxAttempts) {
            delay(500)
            attempts++

            val getPayload = JSONObject().apply {
                put("action", "get")
                put("hash", hash)
            }

            try {
                val getResponse = app.post(
                    "$serverUrl/torrents",
                    json = getPayload,
                    timeout = 5L
                ).text

                val getJson = JSONObject(getResponse)
                if (getJson.has("file_stats")) {
                    val stats = getJson.getJSONArray("file_stats")
                    if (stats.length() > 0) {
                        val list = mutableListOf<TorrentFileInfo>()
                        for (i in 0 until stats.length()) {
                            val item = stats.getJSONObject(i)
                            val path = item.getString("path")
                            val ext = path.substringAfterLast('.', "").lowercase()
                            list.add(
                                TorrentFileInfo(
                                    id = item.getInt("id"),
                                    path = path,
                                    length = item.getLong("length"),
                                    extension = ext
                                )
                            )
                        }
                        return list
                    }
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        return emptyList()
    }
}
