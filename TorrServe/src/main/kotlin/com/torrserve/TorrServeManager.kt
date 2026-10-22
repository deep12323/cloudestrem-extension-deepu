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
import java.net.URLDecoder
import java.net.URLEncoder

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

    const val REMOTE_TRACKERS_URL = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"

    val FAST_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://tracker.qu.ax:6969/announce",
        "http://tracker.qu.ax:6969/announce",
        "udp://tracker.skynetcloud.site:6969/announce",
        "udp://tracker.ducks.party:1984/announce",
        "udp://tracker.corpscorp.online:80/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "udp://tracker.nyaa.vc:6969/announce",
        "udp://tracker.gmi.gd:6969/announce",
        "udp://tracker.tryhackx.org:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "http://tracker2.dler.org:80/announce",
        "udp://tracker2.dler.org:80/announce",
        "http://tracker.dler.com:6969/announce",
        "http://tracker.renfei.net:8080/announce",
        "udp://tracker.peerfect.org:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "https://tracker.tamersunion.org:443/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "https://tracker2.ctix.cn/announce",
        "udp://retracker.lanta-net.ru:2710/announce",
        "udp://tracker.cyberia.is:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "https://tracker1.520.jp:443/announce",
        "http://ipv4announce.sktorrent.eu:6969/announce",
        "http://nyaa.tracker.wf:7777/announce",
        "http://1337.abcvg.info:80/announce",
        "http://bt1.archive.org:6969/announce",
        "http://bt2.archive.org:6969/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "udp://movies.zsw.ca:6969/announce",
        "udp://p4p.arenabg.com:1337/announce"
    )

    val FAST_TRACKERS_STRING: String by lazy {
        FAST_TRACKERS.joinToString("\n")
    }

    /**
     * Appends all high-speed trackers to a magnet URI or raw info-hash if not already present.
     * Ensures any torrent stream announces to 40+ responsive peers immediately.
     */
    fun enrichMagnetWithTrackers(uriOrHash: String): String {
        val trimmed = uriOrHash.trim()
        val isHash = (trimmed.length == 40 || trimmed.length == 32) && trimmed.all { it.isLetterOrDigit() }
        val baseMagnet = if (isHash) {
            "magnet:?xt=urn:btih:$trimmed"
        } else if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            trimmed
        } else {
            return trimmed
        }

        val existingTrackers = mutableSetOf<String>()
        val trRegex = Regex("[?&]tr=([^&]+)", RegexOption.IGNORE_CASE)
        trRegex.findAll(baseMagnet).forEach { match ->
            val decoded = runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrNull()
            if (decoded != null) {
                existingTrackers.add(decoded.trim().lowercase())
            }
        }

        val hasQuery = baseMagnet.contains("?")
        var firstParam = !hasQuery
        val sb = StringBuilder(baseMagnet)
        for (tracker in FAST_TRACKERS) {
            if (!existingTrackers.contains(tracker.lowercase())) {
                val encoded = runCatching { URLEncoder.encode(tracker, "UTF-8") }.getOrNull() ?: tracker
                if (firstParam) {
                    sb.append("?tr=").append(encoded)
                    firstParam = false
                } else {
                    sb.append("&tr=").append(encoded)
                }
            }
        }

        return sb.toString()
    }

    /**
     * Injects fast trackers directly into the running TorrServer instance via reflection.
     */
    fun injectTrackersToEngine(context: Context? = null) {
        val targetCtx = context ?: appContext
        val classLoaders = listOfNotNull(
            targetCtx?.classLoader,
            Thread.currentThread().contextClassLoader,
            TorrServeManager::class.java.classLoader
        ).distinct()

        val trackersBlock = FAST_TRACKERS_STRING

        for (cl in classLoaders) {
            runCatching {
                val torrServerClass = Class.forName("torrServer.TorrServer", true, cl)
                val addTrackersMethod = torrServerClass.getDeclaredMethod("addTrackers", String::class.java)
                addTrackersMethod.isAccessible = true
                addTrackersMethod.invoke(null, trackersBlock)
            }
        }
    }

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
        injectTrackersToEngine(appCtx)
        enforceProactiveTorrentWarningBypass(appCtx)
    }

    fun enforceProactiveTorrentWarningBypass(context: Context? = null) {
        val targetCtx = context ?: appContext
        val classLoaders = listOfNotNull(
            targetCtx?.classLoader,
            Thread.currentThread().contextClassLoader,
            TorrServeManager::class.java.classLoader
        ).distinct()

        for (cl in classLoaders) {
            runCatching {
                val torrentClass = Class.forName("com.lagradost.cloudstream3.ui.player.Torrent", true, cl)
                val instance = runCatching {
                    torrentClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                }.getOrNull()

                val setter = torrentClass.methods.firstOrNull {
                    it.name == "setHasAcceptedTorrentForThisSession" && it.parameterTypes.size == 1
                } ?: torrentClass.declaredMethods.firstOrNull {
                    it.name == "setHasAcceptedTorrentForThisSession" && it.parameterTypes.size == 1
                }

                if (setter != null) {
                    setter.isAccessible = true
                    if (instance != null) runCatching { setter.invoke(instance, true) }
                    runCatching { setter.invoke(null, true) }
                }

                var currClass: Class<*>? = torrentClass
                while (currClass != null && currClass != Any::class.java) {
                    val field = runCatching {
                        currClass.getDeclaredField("hasAcceptedTorrentForThisSession").apply { isAccessible = true }
                    }.getOrNull()
                    if (field != null) {
                        if (instance != null) runCatching { field.set(instance, true) }
                        runCatching { field.set(null, true) }
                        break
                    }
                    currClass = currClass.superclass
                }
            }
        }
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

            val trackersBlock = FAST_TRACKERS_STRING

            // Write trackers.txt fallback file directly to TorrServer directories
            try { File(context.cacheDir, "trackers.txt").writeText(trackersBlock) } catch (_: Throwable) {}
            try { File(context.filesDir, "trackers.txt").writeText(trackersBlock) } catch (_: Throwable) {}

            val bitTorrObj = JSONObject().apply {
                put("CacheSize", cacheMb * 1024L * 1024L)
                put("PreloadCache", preload)
                put("ReaderReadAHead", readAhead)
                put("UseDisk", useDisk)
                put("DisableUpload", disableUpload)
                put("UploadRateLimit", if (disableUpload) 1 else 0)
                put("TorrentDisconnectTimeout", timeoutSec)
                put("RetrackersMode", 1) // 1 = Add retrackers automatically
                put("TrackersListURL", REMOTE_TRACKERS_URL)
                put("DefaultTrackers", trackersBlock)
                put("DefaultTracker", trackersBlock)
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
                put("RetrackersMode", 1)
                put("TrackersListURL", REMOTE_TRACKERS_URL)
                put("DefaultTrackers", trackersBlock)
                put("DefaultTracker", trackersBlock)
                put("StoreSettingsInJson", true)
            }

            val jsonContent = rootObj.toString(2)
            try { File(context.cacheDir, "settings.json").writeText(jsonContent) } catch (_: Throwable) {}
            try { File(context.filesDir, "settings.json").writeText(jsonContent) } catch (_: Throwable) {}
        }
        injectTrackersToEngine(context)
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
                    enforceProactiveTorrentWarningBypass(targetCtx)
                    injectTrackersToEngine(targetCtx)

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
                    addTrackersMethod.invoke(null, FAST_TRACKERS_STRING)
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

        // Make sure settings and trackers on disk are also up to date
        syncSettingsFileToDisk(context)
        injectTrackersToEngine(context)

        return try {
            val cacheBytes = cacheMb * 1024L * 1024L
            val trackersBlock = FAST_TRACKERS_STRING

            val sets = JSONObject().apply {
                put("CacheSize", cacheBytes)
                put("PreloadCache", preload)
                put("ReaderReadAHead", readAhead)
                put("UseDisk", useDisk)
                put("DisableUpload", disableUpload)
                put("UploadRateLimit", if (disableUpload) 1 else 0)
                put("TorrentDisconnectTimeout", timeoutSec)
                put("RetrackersMode", 1) // 1 = Add retrackers automatically
                put("TrackersListURL", REMOTE_TRACKERS_URL)
                put("DefaultTrackers", trackersBlock)
                put("DefaultTracker", trackersBlock)
                put("StoreSettingsInJson", true)
            }

            val payload = JSONObject().apply {
                put("action", "set")
                put("sets", sets)
                put("config", sets)
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
        val finalUrl = enrichMagnetWithTrackers(torrentUrl)
        val payload = JSONObject().apply {
            put("action", "add")
            put("link", finalUrl)
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
