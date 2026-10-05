package com.torrserve

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder

class TorrServeProvider(var pluginContext: Context? = null) : MainAPI() {
    override var mainUrl = "http://127.0.0.1:8090"
    override var name = "TorrServe Player"
    override val hasMainPage = false
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Torrent, TvType.Movie, TvType.TvSeries)
    override val vpnStatus = VPNStatus.None

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        val isMagnet = trimmed.startsWith("magnet:", ignoreCase = true)
        val isTorrentFile = trimmed.contains(".torrent", ignoreCase = true)
        val isHash = (trimmed.length == 40 || trimmed.length == 32) && trimmed.all { it.isLetterOrDigit() }

        if (isMagnet || isTorrentFile || isHash) {
            val magnetUrl = if (isHash) "magnet:?xt=urn:btih:$trimmed" else trimmed
            val title = parseTorrentTitle(magnetUrl)

            return listOf(
                newMovieSearchResponse(title, magnetUrl, TvType.Torrent) {
                    this.posterUrl = "https://raw.githubusercontent.com/YouROK/TorrServer/master/server/web/public/favicon.ico"
                }
            )
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val magnetUrl = if (!url.startsWith("magnet:", ignoreCase = true) &&
            !url.contains("://") &&
            (url.length == 40 || url.length == 32) &&
            url.all { it.isLetterOrDigit() }
        ) {
            "magnet:?xt=urn:btih:$url"
        } else {
            url
        }

        val title = parseTorrentTitle(magnetUrl)
        val ctx = pluginContext ?: TorrServeManager.appContext

        try {
            val serverUrl = TorrServeManager.getOrStartServer(ctx)
            if (ctx != null) {
                TorrServeManager.applyBufferSettings(serverUrl, ctx)
            }
            val hash = TorrServeManager.addTorrent(serverUrl, magnetUrl, title)
            val files = TorrServeManager.waitForFiles(serverUrl, hash, maxWaitSeconds = 8)
            val videoFiles = files.filter { it.extension in TorrServeManager.VIDEO_EXTENSIONS }

            if (videoFiles.size > 1) {
                val episodes = videoFiles.mapIndexed { index, file ->
                    val epName = file.path.substringAfterLast('/')
                    newEpisode("$magnetUrl#fileIndex=${file.id}") {
                        this.name = epName
                        this.episode = index + 1
                    }
                }

                val bufferMb = if (ctx != null) TorrServeManager.getCacheSizeMb(ctx) else 128L
                return newTvSeriesLoadResponse(title, magnetUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = "https://raw.githubusercontent.com/YouROK/TorrServer/master/server/web/public/favicon.ico"
                    this.plot = "Multi-file BitTorrent stream via TorrServer (${bufferMb}MB buffer)"
                }
            }
        } catch (e: Exception) {
            // Fall through to movie response if metadata is slow
        }

        return newMovieLoadResponse(title, magnetUrl, TvType.Torrent, magnetUrl) {
            this.posterUrl = "https://raw.githubusercontent.com/YouROK/TorrServer/master/server/web/public/favicon.ico"
            this.plot = "BitTorrent stream via TorrServer (Configurable buffer)"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ctx = pluginContext ?: TorrServeManager.appContext
        val serverUrl = TorrServeManager.getOrStartServer(ctx)
        if (ctx != null) {
            TorrServeManager.applyBufferSettings(serverUrl, ctx)
        }

        val cleanTorrentUrl = data.substringBefore("#fileIndex=")
        val targetFileId = if (data.contains("#fileIndex=")) {
            data.substringAfter("#fileIndex=").toIntOrNull()
        } else {
            null
        }

        val hash = TorrServeManager.addTorrent(serverUrl, cleanTorrentUrl)
        val timeout = if (ctx != null) TorrServeManager.getDisconnectTimeout(ctx) else 60
        val metaWait = (timeout / 2).coerceIn(15, 60)
        val files = TorrServeManager.waitForFiles(serverUrl, hash, maxWaitSeconds = metaWait)

        if (files.isEmpty()) {
            throw Exception("Timed out fetching torrent metadata. Ensure the torrent has active seeders.")
        }

        // Subtitles extraction
        files.filter { it.extension in TorrServeManager.SUBTITLE_EXTENSIONS }.forEach { sub ->
            val subName = sub.path.substringAfterLast('/')
            val encodedName = URLEncoder.encode(subName, "UTF-8")
            val subStreamUrl = "$serverUrl/stream/$encodedName?link=$hash&index=${sub.id}&play=true"
            subtitleCallback(
                newSubtitleFile(
                    subName.substringBeforeLast('.'),
                    subStreamUrl
                )
            )
        }

        // Video extraction
        val candidateVideos = files.filter { it.extension in TorrServeManager.VIDEO_EXTENSIONS }
            .ifEmpty { listOfNotNull(files.maxByOrNull { it.length }) }

        val targetVideos = if (targetFileId != null) {
            candidateVideos.filter { it.id == targetFileId }.ifEmpty { candidateVideos }
        } else {
            candidateVideos.sortedByDescending { it.length }
        }

        targetVideos.forEach { video ->
            val fileName = video.path.substringAfterLast('/')
            val encodedName = URLEncoder.encode(fileName, "UTF-8")
            val playUrl = "$serverUrl/stream/$encodedName?link=$hash&index=${video.id}&play=true&preload=true"
            val quality = getQualityFromName(fileName)

            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name: $fileName",
                    url = playUrl
                ) {
                    this.referer = ""
                }
            )
        }

        return true
    }

    private fun parseTorrentTitle(url: String): String {
        return if (url.startsWith("magnet:", ignoreCase = true)) {
            val match = Regex("dn=([^&]+)").find(url)
            match?.groupValues?.get(1)?.let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
            } ?: "Magnet Stream"
        } else if (url.contains("/")) {
            url.substringAfterLast("/").substringBefore("?")
        } else {
            "Torrent Stream"
        }
    }
}
