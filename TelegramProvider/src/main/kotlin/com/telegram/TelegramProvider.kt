package com.telegram

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class TelegramProvider : MainAPI() {
    override var mainUrl = "https://t.me"
    override var name = "Telegram"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var lang = "en"
    override val hasMainPage = true

    override val mainPage: List<MainPageData>
        get() {
            val context = try { TelegramRepository.getContext() } catch (e: Exception) { return emptyList() }
            val channels = TelegramRepository.getCustomChannels(context)
            val prefs = context.getSharedPreferences("telegram_plugin_prefs", android.content.Context.MODE_PRIVATE)
            return mainPageOf(
                *channels.map { 
                    val cachedTitle = prefs.getString("title_$it", "Channel $it") ?: "Channel $it"
                    it.toString() to cachedTitle 
                }.toTypedArray()
            )
        }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        if (!TelegramRepository.waitUntilAuthenticated()) {
            return null
        }

        val chanId = request.data
        val chatId = TelegramRepository.getChatId(chanId) ?: return null

        // Check if this is a forum channel with topics
        if (page == 1 && TelegramRepository.isForumChannel(chatId)) {
            // Forum channel: show topics as series cards
            val topics = TelegramRepository.getForumTopics(chatId)
            if (topics.isEmpty()) return null

            val searchResponses = topics.map { topicData ->
                val url = "${mainUrl}?tgType=topic&chatId=${chatId}&topicId=${topicData.topicId}&v=2&name=${java.net.URLEncoder.encode(topicData.displayName, "UTF-8")}&channelTitle=${java.net.URLEncoder.encode(topicData.channelTitle, "UTF-8")}"
                val poster = if (topicData.thumbnailChatId != 0L && topicData.thumbnailMessageId != 0L) {
                    TelegramRepository.getThumbnailUrl(topicData.thumbnailChatId, topicData.thumbnailMessageId)
                } else {
                    "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                }
                val qual = parseSearchQuality(topicData.displayName)
                newTvSeriesSearchResponse(topicData.displayName, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.quality = qual
                }
            }

            // Cache the channel title
            try {
                val context = TelegramRepository.getContext()
                val title = topics.firstOrNull()?.channelTitle ?: chanId
                context.getSharedPreferences("telegram_plugin_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("title_$chanId", title)
                    .apply()
            } catch (e: Exception) {}

            return try {
                newHomePageResponse(
                    HomePageList(request.name, searchResponses, true),
                    false
                )
            } catch (e: Throwable) {
                newHomePageResponse(request.name, searchResponses, false)
            }
        }

        // Non-forum channel: original behavior - show videos directly
        val result = TelegramRepository.getChannelVideos(chanId, page = page, limit = 50) ?: return null
        
        val title = result.first
        val videos = result.second

        // Cache the title for future mainPage calls
        try {
            val context = TelegramRepository.getContext()
            context.getSharedPreferences("telegram_plugin_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("title_$chanId", title)
                .apply()
        } catch (e: Exception) {}

        if (videos.isEmpty() && page > 1) return null

        val (splitGroups, individualFiles) = TelegramRepository.groupSplitFiles(videos)
        val searchResponses = mutableListOf<SearchResponse>()

        for (group in splitGroups) {
            val firstMsg = group.parts.first()
            val size = group.totalSize
            val name = group.baseName
            val thumbId = firstMsg.thumbnailFileId?.toString() ?: ""
            val url = "${mainUrl}?tgType=message&chatId=${firstMsg.chatId}&messageId=${firstMsg.messageId}&fileId=${firstMsg.fileId}&size=$size&name=${java.net.URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
            
            val poster = firstMsg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
            val qual = parseSearchQuality(name, firstMsg.caption, size)
            val sizeStr = formatBytes(size)
            val typeTag = getFileTypeTag(name).ifEmpty { " [SPLIT]" }
            val displayTitle = "${cleanTitle(name)} [$sizeStr] (${group.parts.size} parts)$typeTag"
            
            searchResponses.add(newMovieSearchResponse(displayTitle, url, TvType.Movie) {
                this.posterUrl = poster
                this.quality = qual
            })
        }

        for (msg in individualFiles) {
            val size = msg.fileSize
            val name = msg.fileName
            val thumbId = msg.thumbnailFileId?.toString() ?: ""
            val url = "${mainUrl}?tgType=message&chatId=${msg.chatId}&messageId=${msg.messageId}&fileId=${msg.fileId}&size=$size&name=${java.net.URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
            
            val poster = msg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
            val qual = parseSearchQuality(name, msg.caption, size)
            val sizeStr = formatBytes(size)
            val typeTag = getFileTypeTag(name)
            val displayTitle = if (size > 0) "${cleanTitle(name)} [$sizeStr]$typeTag" else "${cleanTitle(name)}$typeTag"
            
            searchResponses.add(newMovieSearchResponse(displayTitle, url, TvType.Movie) {
                this.posterUrl = poster
                this.quality = qual
            })
        }

        val hasNext = videos.isNotEmpty()
        return try {
            newHomePageResponse(
                HomePageList(request.name, searchResponses, true),
                hasNext
            )
        } catch (e: Throwable) {
            newHomePageResponse(request.name, searchResponses, hasNext)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (!TelegramRepository.waitUntilAuthenticated()) return emptyList()
        val messages = TelegramRepository.searchVideoMessages(query)
        val (splitGroups, individualFiles) = TelegramRepository.groupSplitFiles(messages)
        val searchResponses = mutableListOf<SearchResponse>()
        
        for (group in splitGroups) {
            val firstMsg = group.parts.first()
            val size = group.totalSize
            val name = group.baseName
            val thumbId = firstMsg.thumbnailFileId?.toString() ?: ""
            val url = "${mainUrl}?tgType=message&chatId=${firstMsg.chatId}&messageId=${firstMsg.messageId}&fileId=${firstMsg.fileId}&size=$size&name=${URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
            
            val poster = firstMsg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
            val qual = parseSearchQuality(name, firstMsg.caption, size)
            val sizeStr = formatBytes(size)
            val typeTag = getFileTypeTag(name).ifEmpty { " [SPLIT]" }
            val displayTitle = "${cleanTitle(name)} [$sizeStr] (${group.parts.size} parts)$typeTag"
            
            searchResponses.add(newMovieSearchResponse(displayTitle, url, TvType.Movie) {
                this.posterUrl = poster
                this.quality = qual
            })
        }

        for (msg in individualFiles) {
            val size = msg.fileSize
            val name = msg.fileName
            val thumbId = msg.thumbnailFileId?.toString() ?: ""
            val url = "${mainUrl}?tgType=message&chatId=${msg.chatId}&messageId=${msg.messageId}&fileId=${msg.fileId}&size=$size&name=${URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
            
            val poster = msg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
            val qual = parseSearchQuality(name, msg.caption, size)
            val sizeStr = formatBytes(size)
            val typeTag = getFileTypeTag(name)
            val displayTitle = if (size > 0) "${cleanTitle(name)} [$sizeStr]$typeTag" else "${cleanTitle(name)}$typeTag"
            
            searchResponses.add(newMovieSearchResponse(displayTitle, url, TvType.Movie) {
                this.posterUrl = poster
                this.quality = qual
            })
        }

        return searchResponses
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = android.net.Uri.parse(url)
        
        // Handle forum topic URLs - show as TV Series with episodes
        val tgType = uri.getQueryParameter("tgType")
        if (tgType == "topic") {
            val chatId = uri.getQueryParameter("chatId")?.toLongOrNull() ?: throw ErrorLoadingException("Missing chatId")
            val topicId = uri.getQueryParameter("topicId")?.toIntOrNull() ?: throw ErrorLoadingException("Missing topicId")
            val topicName = uri.getQueryParameter("name") ?: "Topic"
            val channelTitle = uri.getQueryParameter("channelTitle") ?: ""

            // Fetch all videos in this topic (paginate until exhausted)
            val allVideos = mutableListOf<TelegramVideoMessage>()
            var currentPage = 1
            while (true) {
                val pageVideos = TelegramRepository.getTopicVideos(chatId, topicId, page = currentPage, limit = 50)
                if (pageVideos.isEmpty()) break
                allVideos.addAll(pageVideos)
                currentPage++
            }
            val videos = allVideos
            val (splitGroups, individualFiles) = TelegramRepository.groupSplitFiles(videos)
            var epNum = 1
            val episodes = mutableListOf<Episode>()

            for (group in splitGroups) {
                val firstMsg = group.parts.first()
                val size = group.totalSize
                val name = group.baseName
                val thumbId = firstMsg.thumbnailFileId?.toString() ?: ""
                val episodeUrl = "${mainUrl}?tgType=message&chatId=${firstMsg.chatId}&messageId=${firstMsg.messageId}&fileId=${firstMsg.fileId}&size=$size&name=${java.net.URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
                val poster = firstMsg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                val cleanEpName = cleanTitle(name)
                val sizeStr = formatBytes(size)
                val typeTag = getFileTypeTag(name).ifEmpty { " [SPLIT]" }

                episodes.add(newEpisode(episodeUrl) {
                    this.name = "$cleanEpName ($sizeStr) (${group.parts.size} parts)$typeTag"
                    this.data = episodeUrl
                    this.season = 1
                    this.episode = epNum++
                    this.posterUrl = poster
                    this.description = "Episode ${epNum - 1} • Size: $sizeStr • ${group.parts.size} split parts"
                })
            }

            for (msg in individualFiles) {
                val size = msg.fileSize
                val name = msg.fileName
                val thumbId = msg.thumbnailFileId?.toString() ?: ""
                val episodeUrl = "${mainUrl}?tgType=message&chatId=${msg.chatId}&messageId=${msg.messageId}&fileId=${msg.fileId}&size=$size&name=${java.net.URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
                val poster = msg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                val cleanEpName = cleanTitle(name)
                val sizeStr = formatBytes(size)
                val typeTag = getFileTypeTag(name)

                episodes.add(newEpisode(episodeUrl) {
                    this.name = "$cleanEpName ($sizeStr)$typeTag"
                    this.data = episodeUrl
                    this.season = 1
                    this.episode = epNum++
                    this.posterUrl = poster
                    this.description = "Episode ${epNum - 1} • Size: $sizeStr"
                })
            }

            return newTvSeriesLoadResponse(topicName, url, TvType.TvSeries, episodes) {
                this.plot = "$channelTitle • ${videos.size} video${if (videos.size != 1) "s" else ""}"
                this.posterUrl = if (videos.isNotEmpty()) {
                    val firstMsg = videos.first()
                    firstMsg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(firstMsg.chatId, firstMsg.messageId) }
                        ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                } else {
                    "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                }
            }
        }

        // Handle regular video URLs - show as Movie (original behavior)
        val size = uri.getQueryParameter("size")?.toLongOrNull() ?: 0L
        val name = uri.getQueryParameter("name") ?: "Telegram File"
        val chatId = uri.getQueryParameter("chatId")?.toLongOrNull() ?: 0L
        val messageId = uri.getQueryParameter("messageId")?.toLongOrNull() ?: 0L

        val poster = if (chatId != 0L && messageId != 0L) TelegramRepository.getThumbnailUrl(chatId, messageId) else "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"

        return newMovieLoadResponse(name, url, TvType.Movie, url) {
            this.plot = "Telegram Video File\nSize: ${formatBytes(size)}"
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = android.net.Uri.parse(data)
        val chatId = uri.getQueryParameter("chatId")?.toLongOrNull() ?: 0L
        val messageId = uri.getQueryParameter("messageId")?.toLongOrNull() ?: 0L
        val fileId = uri.getQueryParameter("fileId")?.toIntOrNull()
        val name = uri.getQueryParameter("name") ?: "Telegram File"
        val size = uri.getQueryParameter("size")?.toLongOrNull() ?: 0L

        val freshFileId = if (chatId != 0L && messageId != 0L) {
            TelegramRepository.getFreshFileId(chatId, messageId) ?: fileId
        } else {
            fileId
        } ?: return false

        val ext = name.substringAfterLast('.', "").lowercase()

        // Check if this is a ZIP file
        if (ext == "zip" && size > 1_000_000) {
            val streamUrl = TelegramRepository.getZipStreamUrl(freshFileId, name, size)
            val quality = parseQuality(name)
            val link = newExtractorLink(
                name = "\uD83D\uDDC4\uFE0F $name [ZIP]",
                source = "Telegram",
                url = streamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = ""
                this.quality = quality
            }
            callback(link)
            return true
        }

        // Check if this is part of a split file group
        val splitPattern = Regex("""^(.+?)\.(\d{2,4})$""")
        val partPattern = Regex("""^(.+?)\.part\d+$""", RegexOption.IGNORE_CASE)
        val isSplit = splitPattern.matches(name) || partPattern.matches(name)

        if (isSplit && chatId != 0L) {
            // Find all sibling parts in the same chat
            val baseName = splitPattern.find(name)?.groupValues?.get(1)
                ?: partPattern.find(name)?.groupValues?.get(1)
                ?: name.substringBeforeLast('.')

            val siblingMessages = TelegramRepository.searchVideoMessages(baseName, limit = 100)
                .filter { msg ->
                    msg.chatId == chatId &&
                    (msg.fileName.startsWith(baseName) &&
                        (splitPattern.matches(msg.fileName) || partPattern.matches(msg.fileName)))
                }

            if (siblingMessages.size >= 2) {
                val (groups, _) = TelegramRepository.groupSplitFiles(siblingMessages)
                val group = groups.firstOrNull { it.baseName == baseName }

                if (group != null) {
                    // Get fresh file IDs for all parts
                    val freshIds = group.parts.map { part ->
                        TelegramRepository.getFreshFileId(part.chatId, part.messageId) ?: part.fileId
                    }
                    val partSizes = group.parts.map { it.fileSize }
                    val totalSize = partSizes.sum()

                    val streamUrl = TelegramRepository.getMergedStreamUrl(freshIds, baseName, partSizes)
                    val quality = parseQuality(baseName)
                    val sizeStr = formatBytes(totalSize)

                    val link = newExtractorLink(
                        name = "\uD83D\uDD17 $baseName (${group.parts.size} parts, $sizeStr) [SPLIT]",
                        source = "Telegram",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = ""
                        this.quality = quality
                    }
                    callback(link)
                    return true
                }
            }
        }

        // Regular single file streaming (original behavior)
        val streamUrl = TelegramRepository.getStreamUrl(freshFileId, name, size)
        val quality = parseQuality(name)

        val link = newExtractorLink(
            name = name,
            source = "Telegram",
            url = streamUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = ""
            this.quality = quality
        }
        callback(link)
        return true
    }

    private fun parseSearchQuality(name: String, caption: String = "", fileSize: Long = 0L): SearchQuality {
        val text = "$name $caption".lowercase()
        return when {
            text.contains("2160") || text.contains("4k") || text.contains("uhd") -> SearchQuality.FourK
            text.contains("1080") || text.contains("fhd") -> SearchQuality.HD
            text.contains("720") || text.contains("hd") -> SearchQuality.HD
            text.contains("480") || text.contains("sd") || text.contains("360p") -> SearchQuality.SD
            text.contains("cam") || text.contains("hdcam") -> SearchQuality.Cam
            text.contains("telecine") || text.contains("hdts") -> SearchQuality.Telecine
            fileSize >= 3_500_000_000L -> SearchQuality.FourK
            fileSize >= 600_000_000L -> SearchQuality.HD
            fileSize > 0L -> SearchQuality.SD
            else -> SearchQuality.HD
        }
    }

    private fun parseDubStatus(name: String, caption: String = ""): Set<DubStatus> {
        val text = "$name $caption".lowercase()
        val isDub = listOf("dub", "dubbed", "dual", "multi", "hindi", "tamil", "telugu", "malayalam", "kannada", "bengali", "marathi", "audio", "org audio").any { text.contains(it) }
        val isSub = listOf("sub", "subbed", "esub", "msub", "subtitles", "english sub", "softsub", "hardsub", "srt").any { text.contains(it) }

        val set = mutableSetOf<DubStatus>()
        if (isDub) set.add(DubStatus.Dubbed)
        if (isSub) set.add(DubStatus.Subbed)
        return set
    }

    private fun cleanTitle(rawName: String): String {
        var name = rawName.substringBeforeLast('.')
        name = name.replace(Regex("(?i)[\\._\\-\\s]*[Ss]\\d{1,2}[Ee]\\d{1,4}.*"), "")
        name = name.replace(Regex("(?i)[\\._\\-\\s]+[Ee][Pp]?\\d{1,4}([\\._\\-\\s]|$).*"), "")
        val tags = listOf(
            "1080p", "720p", "480p", "2160p", "4k", "uhd", "fhd", "hd", "sd",
            "hevc", "x265", "x264", "h264", "h265", "web-dl", "webrip", "bluray", "hdrip", "brrip",
            "aac", "dts", "ac3", "dd5", "dual", "audio", "hindi", "english", "esub", "msub", "sub"
        )
        for (tag in tags) {
            name = name.replace(Regex("(?i)[\\._\\-\\s]+$tag([\\._\\-\\s]|$).*"), "")
        }
        val cleaned = name.replace('.', ' ').replace('_', ' ').trim()
        return if (cleaned.isNotBlank()) cleaned else rawName
    }

    private fun parseQuality(raw: String): Int {
        val t = raw.lowercase().replace(' ', '.')
        fun has(vararg xs: String) = xs.any { it in t }
        return when {
            has("360", "36o") -> 360
            has("480", "48o") -> 480
            has("720", "72o") -> 720
            has("1080", "1o8o", "108o", "1o80", ".fhd.") -> 1080
            has("2160", "216o", ".4k.", ".uhd.", "ultrahd") -> 2160
            else -> 0 // Unknown
        }
    }

    private fun getFileTypeTag(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val splitPattern = Regex("""^\d+$""")
        return when {
            ext == "zip" -> " [ZIP]"
            splitPattern.matches(ext) -> " [SPLIT]"
            fileName.lowercase().matches(Regex(""".*\.part\d+$""")) -> " [SPLIT]"
            else -> ""
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}