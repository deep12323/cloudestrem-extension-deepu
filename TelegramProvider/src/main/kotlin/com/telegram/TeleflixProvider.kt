package com.telegram

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class TeleflixProvider : MainAPI() {
    override var mainUrl = "https://v3-cinemeta.strem.io"
    override var name = "Teleflix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/catalog/movie/top.json" to "Popular Movies",
        "$mainUrl/catalog/series/top.json" to "Popular TV Shows",
        "$mainUrl/catalog/movie/imdbRating.json" to "Featured Movies (Top Rated)",
        "$mainUrl/catalog/series/imdbRating.json" to "Featured TV Shows (Top Rated)",
        "$mainUrl/catalog/movie/year/genre=2026.json" to "2026 New Movies",
        "$mainUrl/catalog/series/year/genre=2026.json" to "2026 New TV Shows",
        "$mainUrl/catalog/movie/top/genre=Action.json" to "Action Movies",
        "$mainUrl/catalog/movie/top/genre=Sci-Fi.json" to "Sci-Fi Movies",
        "$mainUrl/catalog/movie/top/genre=Comedy.json" to "Comedy Movies",
        "$mainUrl/catalog/movie/top/genre=Horror.json" to "Horror Movies",
        "$mainUrl/catalog/movie/top/genre=Animation.json" to "Animation Movies",
        "$mainUrl/catalog/movie/top/genre=Thriller.json" to "Thriller Movies",
        "$mainUrl/catalog/movie/top/genre=Romance.json" to "Romance Movies",
        "$mainUrl/catalog/movie/top/genre=Documentary.json" to "Documentary Movies",
        "$mainUrl/catalog/series/top/genre=Action.json" to "Action TV Shows",
        "$mainUrl/catalog/series/top/genre=Sci-Fi.json" to "Sci-Fi TV Shows",
        "$mainUrl/catalog/series/top/genre=Drama.json" to "Drama TV Shows",
        "$mainUrl/catalog/series/top/genre=Comedy.json" to "Comedy TV Shows",
        "$mainUrl/catalog/series/top/genre=Animation.json" to "Animation TV Shows",
        "$mainUrl/catalog/series/top/genre=Documentary.json" to "Documentary TV Shows"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val skip = (page - 1) * 50
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("genre=")) {
                request.data.replace(".json", "&skip=$skip.json")
            } else {
                request.data.replace(".json", "/skip=$skip.json")
            }
        }
        
        val response = try { app.get(url).text } catch (e: Exception) { return null }
        val catalog = try { parseJson<CinemetaCatalog>(response) } catch (e: Exception) { return null }

        val items = catalog.metas.map { meta ->
            val isMovie = meta.type == "movie"

            newMovieSearchResponse(meta.name, "${meta.type}/${meta.id}", if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = meta.poster
            }
        }

        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.replace(" ", "%20")
        val moviesUrl = "$mainUrl/catalog/movie/top/search=$encoded.json"
        val seriesUrl = "$mainUrl/catalog/series/top/search=$encoded.json"

        val moviesResponse = app.get(moviesUrl).text
        val seriesResponse = app.get(seriesUrl).text

        val movies = parseJson<CinemetaCatalog>(moviesResponse).metas
        val series = parseJson<CinemetaCatalog>(seriesResponse).metas

        val all = (movies + series).map { meta ->
            val isMovie = meta.type == "movie"

            newMovieSearchResponse(meta.name, "${meta.type}/${meta.id}", if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = meta.poster
            }
        }

        return all
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("/").filter { it.isNotEmpty() }
        val id = parts.last()
        var type = if (parts.size > 1) parts[parts.size - 2] else id
        
        if (type == id || (type != "movie" && type != "series")) {
            // Backward compatibility for old bookmarks without type prefix
            val checkUrl = "$mainUrl/meta/series/$id.json"
            val checkMeta = try { parseJson<CinemetaMetaResponse>(app.get(checkUrl).text).meta } catch (e: Exception) { null }
            type = if (checkMeta != null && checkMeta.type == "series") "series" else "movie"
        }

        val metaUrl = "$mainUrl/meta/$type/$id.json"
        val metaResponse = app.get(metaUrl).text
        val meta = parseJson<CinemetaMetaResponse>(metaResponse).meta
        
        if (meta == null) throw ErrorLoadingException("Failed to load metadata")
        
        val isSeries = meta.type == "series"

        if (isSeries) {
            val episodes = meta.videos?.map { video ->
                val season = video.season ?: 1
                val ep = video.episode ?: 1
                val epTitle = video.title?.takeIf { it.isNotBlank() } ?: "Episode $ep"
                // We pass a custom data string to loadLinks containing the show name and episode
                val data = "${meta.name} S${season.toString().padStart(2, '0')}E${ep.toString().padStart(2, '0')}"
                newEpisode(epTitle) {
                    this.name = "S${season}E${ep}: $epTitle"
                    this.data = data
                    this.season = season
                    this.episode = ep
                    this.posterUrl = video.thumbnail ?: meta.poster
                    this.description = "Season $season Episode $ep"
                }
            } ?: emptyList()

            return newTvSeriesLoadResponse(meta.name, url, TvType.TvSeries, episodes) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = meta.description
                this.year = meta.year?.toIntOrNull()
                this.tags = meta.genres
            }
        } else {
            return newMovieLoadResponse(meta.name, url, TvType.Movie, meta.name) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = meta.description
                this.year = meta.year?.toIntOrNull()
                this.tags = meta.genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data contains the movie name or series query (e.g. "Spider-Man" or "Mr. Robot S01E01")
        if (!TelegramRepository.waitUntilAuthenticated()) {
            throw ErrorLoadingException("Please login to Telegram in settings first!")
        }

        val sxxEyyRegex = Regex("(?i)S(\\d{1,2})E(\\d{1,2})")
        val match = sxxEyyRegex.find(data)

        val targetSeason: Int?
        val targetEpisode: Int?
        val queries = mutableSetOf<String>()

        if (match != null) {
            targetSeason = match.groupValues[1].toInt()
            targetEpisode = match.groupValues[2].toInt()
            val baseName = data.substring(0, match.range.first).trim()
            val sStr = String.format("%02d", targetSeason)
            val eStr = String.format("%02d", targetEpisode)

            queries.add("$baseName S${sStr}E${eStr}")
            queries.add("$baseName ${targetSeason}x${eStr}")
            queries.add("$baseName ${targetSeason}x${targetEpisode}")
            queries.add("$baseName S${sStr} E${eStr}")
            queries.add("$baseName Season ${targetSeason} Episode ${targetEpisode}")
            queries.add("$baseName S${targetSeason}E${targetEpisode}")
            queries.add("$baseName S${sStr}")
        } else {
            targetSeason = null
            targetEpisode = null
            queries.add(data)
        }

        // Punctuation and spacing variations
        val queriesCopy = queries.toList()
        val punctRegex = Regex("[^a-zA-Z0-9 ]")
        for (q in queriesCopy) {
            if (punctRegex.containsMatchIn(q)) {
                queries.add(q.replace(punctRegex, " ").replace(Regex(" +"), " ").trim())
                queries.add(q.replace(punctRegex, ""))
            }
        }
        
        val rawResults = mutableSetOf<TelegramVideoMessage>()
        val searchLimit = if (targetSeason != null) 200 else 500
        coroutineScope {
            val jobs = queries.map { q ->
                async {
                    TelegramRepository.searchVideoMessages(q, limit = searchLimit, includeAudio = false)
                }
            }
            val resultsList = jobs.awaitAll()
            for (res in resultsList) {
                rawResults.addAll(res)
            }
        }

        // Filter out non-video files (.png, .srt, .nfo, .txt, etc.)
        val videoResults = rawResults.filter { msg -> isVideoFileOrContainer(msg.fileName) }
        
        // Strict filtering for TV Series episodes
        val filteredResults = if (targetSeason != null && targetEpisode != null) {
            videoResults.filter { msg ->
                isMatchingEpisode(msg.fileName, msg.caption, targetSeason, targetEpisode)
            }
        } else {
            videoResults
        }

        if (filteredResults.isEmpty()) {
            throw ErrorLoadingException("No matching streams found on Telegram for '$data'")
        }

        // Group split files and sort strictly by total file size descending (highest to lowest)
        val items = TelegramRepository.groupAndPreserveOrder(filteredResults).sortedByDescending { item ->
            when (item) {
                is DisplayItem.Group -> item.group.totalSize
                is DisplayItem.Single -> item.message.fileSize
            }
        }

        for (item in items) {
            when (item) {
                is DisplayItem.Group -> {
                    val group = item.group
                    val freshIds = group.parts.map { part ->
                        TelegramRepository.getFreshFileId(part.chatId, part.messageId) ?: part.fileId
                    }
                    val partSizes = group.parts.map { it.fileSize }
                    val totalSize = partSizes.sum()
                    val streamUrl = TelegramRepository.getMergedStreamUrl(freshIds, group.baseName, partSizes)
                    val sizeStr = TelegramProvider.formatBytes(totalSize)
                    val qualTag = getQualityTag(group.baseName, totalSize)

                    callback.invoke(
                        newExtractorLink(
                            source = "Telegram",
                            name = "\uD83D\uDD17 ${group.baseName} (${group.parts.size} parts, $sizeStr)$qualTag [SPLIT]",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                is DisplayItem.Single -> {
                    val msg = item.message
                    val freshFileId = TelegramRepository.getFreshFileId(msg.chatId, msg.messageId) ?: msg.fileId
                    val ext = msg.fileName.substringAfterLast('.', "").lowercase()
                    val qualTag = getQualityTag(msg.fileName, msg.fileSize)

                    if (ext == "zip" && msg.fileSize > 1_000_000) {
                        val streamUrl = TelegramRepository.getZipStreamUrl(freshFileId, msg.fileName, msg.fileSize)
                        val sizeStr = TelegramProvider.formatBytes(msg.fileSize)
                        callback.invoke(
                            newExtractorLink(
                                source = "Telegram",
                                name = "\uD83D\uDDC4\uFE0F ${msg.fileName} ($sizeStr)$qualTag [ZIP]",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = ""
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        val streamUrl = TelegramRepository.getStreamUrl(freshFileId, msg.fileName, msg.fileSize)
                        val sizeStr = TelegramProvider.formatBytes(msg.fileSize)
                        callback.invoke(
                            newExtractorLink(
                                source = "Telegram",
                                name = "${msg.fileName} ($sizeStr)$qualTag",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = ""
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        }

        return true
    }

    private fun isMatchingEpisode(
        fileName: String,
        caption: String,
        targetSeason: Int,
        targetEpisode: Int
    ): Boolean {
        val text = "$fileName $caption".lowercase()
        val sNum = targetSeason
        val eNum = targetEpisode
        val sStr = String.format("%02d", sNum)
        val eStr = String.format("%02d", eNum)

        // 1. Check multi-episode range pattern: S01E01-E04, S01E01-04, S1E1-E4, 1x01-1x04, 1x01-04
        val rangeRegex = Regex("(?i)(?:s(\\d{1,2})[._\\s-]*)?e?(\\d{1,2})[._\\s-]*(?:to|[-~])[._\\s-]*e?(\\d{1,2})|(?:(\\d{1,2})x(\\d{1,2})[._\\s-]*(?:to|[-~])[._\\s-]*(?:(\\d{1,2})x)?(\\d{1,2}))")
        val rangeMatches = rangeRegex.findAll(text)
        for (rm in rangeMatches) {
            val sVal = rm.groupValues[1].toIntOrNull() ?: rm.groupValues[4].toIntOrNull() ?: targetSeason
            val startEp = rm.groupValues[2].toIntOrNull() ?: rm.groupValues[5].toIntOrNull()
            val endEp = rm.groupValues[3].toIntOrNull() ?: rm.groupValues[7].toIntOrNull()
            if (startEp != null && endEp != null) {
                if (sVal == targetSeason && targetEpisode in minOf(startEp, endEp)..maxOf(startEp, endEp)) {
                    return true
                }
            }
        }

        // 2. Check full season batch: S01 Complete, Season 1 Complete, S01 Full
        val fullSeasonRegex = Regex("(?i)(?:s0*$sNum|season\\s*0*$sNum)[._\\s-]*(?:complete|full|pack|all)")
        if (fullSeasonRegex.containsMatchIn(text)) {
            return true
        }

        // 3. Check exact single episode matches: S01E04, S1E4, 1x04, 1x4, Season 1 Episode 4, eps1.4, ep04
        val exactPatterns = listOf(
            Regex("(?i)s0*$sNum[._\\s-]*e0*$eNum\\b"),
            Regex("(?i)\\b0*$sNum\\s*x\\s*0*$eNum\\b"),
            Regex("(?i)season\\s*0*$sNum\\s*ep(?:isode)?\\s*0*$eNum\\b"),
            Regex("(?i)eps?0*$sNum[._\\s-]*0*$eNum\\b")
        )
        if (exactPatterns.any { it.containsMatchIn(text) }) return true

        // 4. Check if filename contains explicit Season & Episode numbers for a DIFFERENT episode
        val anyEpRegex = Regex("(?i)(?:s(\\d{1,2})[._\\s-]*e(\\d{1,2})|(\\d{1,2})x(\\d{1,2}))")
        val matches = anyEpRegex.findAll(text)
        var foundAny = false
        for (m in matches) {
            foundAny = true
            val foundS = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull()
            val foundE = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull()
            if (foundS != null && foundE != null) {
                if (foundS == targetSeason && foundE == targetEpisode) {
                    return true
                }
            }
        }

        // If it explicitly specified another season/episode (e.g. S04E10 when we want S01E04), reject it
        if (foundAny) return false

        // 5. Fallback: Check if file name contains target episode string if Season matches
        if (text.contains("s$sStr") || text.contains("season $sNum") || text.contains("season$sStr")) {
            if (text.contains("e$eStr") || text.contains("ep$eNum") || text.contains("episode $eNum")) {
                return true
            }
        }

        return false
    }

    private fun parseSearchQuality(name: String, description: String = ""): SearchQuality {
        val text = "$name $description".lowercase()
        return when {
            text.contains("2160") || text.contains("4k") || text.contains("uhd") -> SearchQuality.FourK
            text.contains("1080") || text.contains("fhd") -> SearchQuality.HD
            text.contains("720") || text.contains("hd") -> SearchQuality.HD
            text.contains("480") || text.contains("sd") || text.contains("360p") -> SearchQuality.SD
            text.contains("cam") || text.contains("hdcam") -> SearchQuality.Cam
            text.contains("telecine") || text.contains("hdts") -> SearchQuality.Telecine
            else -> SearchQuality.HD
        }
    }

    private fun parseDubStatus(name: String, textContent: String = ""): Set<DubStatus> {
        val text = "$name $textContent".lowercase()
        val isDub = listOf("dub", "dubbed", "dual", "multi", "hindi", "tamil", "telugu", "malayalam", "kannada", "bengali", "marathi", "audio").any { text.contains(it) }
        val isSub = listOf("sub", "subbed", "esub", "msub", "subtitles", "english sub", "softsub", "hardsub", "srt").any { text.contains(it) }

        val set = mutableSetOf<DubStatus>()
        if (isDub) set.add(DubStatus.Dubbed)
        if (isSub) set.add(DubStatus.Subbed)
        return set
    }

    private fun getQualityTag(name: String, size: Long = 0L): String {
        val lower = name.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> " [4K]"
            lower.contains("1080") || lower.contains("fhd") -> " [1080p]"
            lower.contains("720") || lower.contains("hd") -> " [720p]"
            lower.contains("480") || lower.contains("sd") -> " [480p]"
            size >= 3_500_000_000L -> " [4K]"
            size >= 1_000_000_000L -> " [1080p]"
            size >= 400_000_000L -> " [720p]"
            size > 0L -> " [480p]"
            else -> ""
        }
    }

    private fun isVideoFileOrContainer(fileName: String): Boolean {
        val lower = fileName.lowercase()
        val ext = lower.substringAfterLast('.', "")
        val rejectedExts = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "tiff",
            "srt", "vtt", "ass", "sub", "idx", "smi",
            "nfo", "txt", "pdf", "doc", "docx", "html", "xml", "json",
            "apk", "exe", "zip64", "url"
        )
        if (ext in rejectedExts) return false

        val videoExts = setOf(
            "mkv", "mp4", "avi", "webm", "mov", "flv", "wmv", "m4v", "ts",
            "mpg", "mpeg", "m2ts", "vob", "ogv", "divx", "3gp", "rmvb"
        )
        if (ext in videoExts) return true

        val splitPattern = Regex("""^\d+$""")
        if (ext == "zip" || ext == "rar" || ext == "7z" || splitPattern.matches(ext) || lower.matches(Regex(""".*\.part\d+$"""))) {
            return true
        }

        return false
    }

    private fun getQualityFromName(name: String, size: Long = 0L): Int {
        val lower = name.lowercase()
        val qual = when {
            lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> Qualities.P2160.value
            lower.contains("1080") || lower.contains("fhd") -> Qualities.P1080.value
            lower.contains("720") || lower.contains("hd") -> Qualities.P720.value
            lower.contains("480") || lower.contains("sd") || lower.contains("360p") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
        if (qual != Qualities.Unknown.value) return qual
        return when {
            size >= 3_500_000_000L -> Qualities.P2160.value
            size >= 1_000_000_000L -> Qualities.P1080.value
            size >= 400_000_000L -> Qualities.P720.value
            size > 0L -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    // Data classes for Cinemeta API
    private data class CinemetaCatalog(val metas: List<CinemetaMeta> = emptyList())
    private data class CinemetaMetaResponse(val meta: CinemetaMeta?)
    
    private data class CinemetaMeta(
        val id: String,
        val type: String?,
        val name: String,
        val poster: String?,
        val background: String?,
        val description: String?,
        val year: String?,
        val imdbRating: String? = null,
        val rating: String? = null,
        val genres: List<String>? = null,
        val videos: List<CinemetaVideo>? = null
    )

    private data class CinemetaVideo(
        val id: String,
        val title: String?,
        val season: Int?,
        val episode: Int?,
        val thumbnail: String?
    )
}
