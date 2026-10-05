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
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

object TorrServeManager {
    private const val PREFS_NAME = "TorrServePluginPrefs"
    private const val KEY_CACHE_MB = "cache_size_mb"
    private const val KEY_PRELOAD_PERCENT = "preload_percent"
    private const val KEY_READ_AHEAD_PERCENT = "read_ahead_percent"
    private const val KEY_USE_DISK = "use_disk"
    private const val KEY_DISABLE_UPLOAD = "disable_upload"
    private const val KEY_DISCONNECT_TIMEOUT = "torrent_disconnect_timeout"
    private const val KEY_LAST_KNOWN_URL = "last_known_server_url"

    const val DEFAULT_CACHE_MB = 128L
    const val DEFAULT_PRELOAD_PERCENT = 15
    const val DEFAULT_READ_AHEAD_PERCENT = 95
    const val DEFAULT_USE_DISK = false
    const val DEFAULT_DISABLE_UPLOAD = false
    const val DEFAULT_DISCONNECT_TIMEOUT = 60 // 60 seconds (TorrServer default is 30s)

    val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v")
    val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    data class TorrentFileInfo(
        val id: Int,
        val path: String,
        val length: Long,
        val extension: String
    )

    var appContext: Context? = null
        private set

    @Volatile
    private var activeServerUrl: String? = null

    @Volatile
    private var lastConfiguredUrl: String? = null

    @Volatile
    private var isWatcherRunning = false

    fun initContext(context: Context) {
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        syncSettingsFileToDisk(appCtx)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCacheSizeMb(context: Context): Long {
        return getPrefs(context).getLong(KEY_CACHE_MB, DEFAULT_CACHE_MB)
    }

    fun setCacheSizeMb(context: Context, mb: Long) {
        getPrefs(context).edit().putLong(KEY_CACHE_MB, mb).apply()
        syncSettingsFileToDisk(context)
    }

    fun getPreloadPercent(context: Context): Int {
        return getPrefs(context).getInt(KEY_PRELOAD_PERCENT, DEFAULT_PRELOAD_PERCENT)
    }

    fun setPreloadPercent(context: Context, percent: Int) {
        getPrefs(context).edit().putInt(KEY_PRELOAD_PERCENT, percent.coerceIn(0, 100)).apply()
        syncSettingsFileToDisk(context)
    }

    fun getReadAheadPercent(context: Context): Int {
        return getPrefs(context).getInt(KEY_READ_AHEAD_PERCENT, DEFAULT_READ_AHEAD_PERCENT)
    }

    fun setReadAheadPercent(context: Context, percent: Int) {
        getPrefs(context).edit().putInt(KEY_READ_AHEAD_PERCENT, percent.coerceIn(0, 100)).apply()
        syncSettingsFileToDisk(context)
    }

    fun getUseDisk(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USE_DISK, DEFAULT_USE_DISK)
    }

    fun setUseDisk(context: Context, useDisk: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USE_DISK, useDisk).apply()
        syncSettingsFileToDisk(context)
    }

    fun getDisableUpload(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DISABLE_UPLOAD, DEFAULT_DISABLE_UPLOAD)
    }

    fun setDisableUpload(context: Context, disable: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DISABLE_UPLOAD, disable).apply()
        syncSettingsFileToDisk(context)
    }

    fun getDisconnectTimeout(context: Context): Int {
        return getPrefs(context).getInt(KEY_DISCONNECT_TIMEOUT, DEFAULT_DISCONNECT_TIMEOUT)
    }

    fun setDisconnectTimeout(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt(KEY_DISCONNECT_TIMEOUT, seconds.coerceAtLeast(10)).apply()
        syncSettingsFileToDisk(context)
    }

    fun getLastKnownUrl(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_KNOWN_URL, null)
    }

    fun setLastKnownUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_LAST_KNOWN_URL, url).apply()
    }

    /**
     * Persists user settings directly into TorrServer's settings.json on disk.
     * TorrServer reads this file automatically on startup in InitSets(), ensuring
     * settings are applied from the first millisecond of a cold launch!
     */
    fun syncSettingsFileToDisk(context: Context) {
        runCatching {
            val cacheMb = getCacheSizeMb(context)
            val preload = getPreloadPercent(context)
            val readAhead = getReadAheadPercent(context).coerceAtLeast(5)
            val useDisk = getUseDisk(context)
            val disableUpload = getDisableUpload(context)
            val timeoutSec = getDisconnectTimeout(context)

            val bitTorrObj = JSONObject().apply {
                put("CacheSize", cacheMb * 1024L * 1024L)
                put("PreloadCache", preload)
                put("ReaderReadAHead", readAhead)
                put("UseDisk", useDisk)
                put("DisableUpload", disableUpload)
                put("UploadRateLimit", if (disableUpload) 1 else 0)
                put("TorrentDisconnectTimeout", timeoutSec)
                put("StoreSettingsInJson", true)
            }

            val rootObj = JSONObject().apply {
                put("BitTorr", bitTorrObj)
                put("CacheSize", cacheMb * 1024L * 1024L)
                put("PreloadCache", preload)
                put("ReaderReadAHead", readAhead)
                put("UseDisk", useDisk)
                put("DisableUpload", disableUpload)
                put("UploadRateLimit", if (disableUpload) 1 else 0)
                put("TorrentDisconnectTimeout", timeoutSec)
                put("StoreSettingsInJson", true)
            }

            val jsonContent = rootObj.toString(2)
            try { File(context.cacheDir, "settings.json").writeText(jsonContent) } catch (_: Throwable) {}
            try { File(context.filesDir, "settings.json").writeText(jsonContent) } catch (_: Throwable) {}
        }
    }

    fun startAutoConfigDaemon(context: Context) {
        initContext(context)
        if (isWatcherRunning) return
        isWatcherRunning = true

        CoroutineScope(Dispatchers.IO).launch {
            // 1. Initial boot / handshake with retry
            runCatching {
                val url = getOrStartServer(context)
                applyBufferSettings(url, context)
            }

            // 2. Background daemon: keeps active TorrServer synced with user configuration
            while (true) {
                try {
                    val targetCtx = appContext ?: context
                    val url = activeServerUrl
                        ?: getLastKnownUrl(targetCtx)?.takeIf { isServerAlive(it) }
                        ?: getInbuiltUrlFromReflection()
                        ?: if (isServerAlive("http://127.0.0.1:8090")) "http://127.0.0.1:8090" else null

                    if (url != null) {
                        if (isServerAlive(url)) {
                            activeServerUrl = url
                            setLastKnownUrl(targetCtx, url)
                            if (url != lastConfiguredUrl) {
                                if (applyBufferSettings(url, targetCtx)) {
                                    lastConfiguredUrl = url
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // silently handle
                }
                delay(1500)
            }
        }
    }

    suspend fun getOrStartServer(context: Context?): String {
        val targetCtx = context ?: appContext

        // 1. Check existing active URL
        activeServerUrl?.let { url ->
            if (isServerAlive(url)) {
                if (targetCtx != null && url != lastConfiguredUrl) {
                    applyBufferSettings(url, targetCtx)
                }
                return url
            }
        }

        // 2. Check last known URL saved across app launches
        targetCtx?.let { ctx ->
            getLastKnownUrl(ctx)?.let { savedUrl ->
                if (isServerAlive(savedUrl)) {
                    activeServerUrl = savedUrl
                    applyBufferSettings(savedUrl, ctx)
                    return savedUrl
                }
            }
        }

        // 3. Check reflection URL if CloudStream player set it
        getInbuiltUrlFromReflection()?.let { url ->
            if (isServerAlive(url)) {
                activeServerUrl = url
                targetCtx?.let {
                    setLastKnownUrl(it, url)
                    applyBufferSettings(url, it)
                }
                return url
            }
        }

        // 4. Start in-built Go TorrServer with retry polling
        if (targetCtx != null) {
            syncSettingsFileToDisk(targetCtx)
            val bootedUrl = startInbuiltTorrServer(targetCtx)
            if (bootedUrl != null) {
                val alive = waitForServerAlive(bootedUrl, maxRetries = 20, delayMs = 150)
                if (alive) {
                    activeServerUrl = bootedUrl
                    setLastKnownUrl(targetCtx, bootedUrl)
                    applyBufferSettings(bootedUrl, targetCtx)
                    return bootedUrl
                }
            }
        }

        // 5. Check standard external port 8090
        val externalUrl = "http://127.0.0.1:8090"
        if (isServerAlive(externalUrl)) {
            activeServerUrl = externalUrl
            targetCtx?.let {
                setLastKnownUrl(it, externalUrl)
                applyBufferSettings(externalUrl, it)
            }
            return externalUrl
        }

        throw IllegalStateException("TorrServer is not running or failed to initialize on localhost.")
    }

    private suspend fun isServerAlive(url: String): Boolean {
        return try {
            app.get("$url/echo", timeout = 1L).code == 200
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun waitForServerAlive(url: String, maxRetries: Int = 20, delayMs: Long = 150): Boolean {
        for (i in 1..maxRetries) {
            if (isServerAlive(url)) return true
            delay(delayMs)
        }
        return false
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
        val readAhead = getReadAheadPercent(context).coerceAtLeast(5)
        val useDisk = getUseDisk(context)
        val disableUpload = getDisableUpload(context)
        val timeoutSec = getDisconnectTimeout(context)

        // Make sure settings on disk are also up to date
        syncSettingsFileToDisk(context)

        return try {
            val cacheBytes = cacheMb * 1024L * 1024L

            val sets = JSONObject().apply {
                put("CacheSize", cacheBytes)
                put("PreloadCache", preload)
                put("ReaderReadAHead", readAhead)
                put("UseDisk", useDisk)
                put("DisableUpload", disableUpload)
                put("UploadRateLimit", if (disableUpload) 1 else 0)
                put("TorrentDisconnectTimeout", timeoutSec)
                put("StoreSettingsInJson", true)
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
            val success = res.code == 200
            if (success) {
                lastConfiguredUrl = serverUrl
            }
            success
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

    suspend fun waitForFiles(serverUrl: String, hash: String, maxWaitSeconds: Int = 20): List<TorrentFileInfo> {
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
