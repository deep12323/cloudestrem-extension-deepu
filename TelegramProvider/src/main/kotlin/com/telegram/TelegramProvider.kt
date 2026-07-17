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
                val url = "telegram://topic?chatId=${chatId}&topicId=${topicData.topicId}&v=2&name=${java.net.URLEncoder.encode(topicData.displayName, "UTF-8")}&channelTitle=${java.net.URLEncoder.encode(topicData.channelTitle, "UTF-8")}"
                val poster = if (topicData.thumbnailChatId != 0L && topicData.thumbnailMessageId != 0L) {
                    TelegramRepository.getThumbnailUrl(topicData.thumbnailChatId, topicData.thumbnailMessageId)
                } else {
                    "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                }
                newTvSeriesSearchResponse(topicData.displayName, url, TvType.TvSeries) {
                    this.posterUrl = poster
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

        val searchResponses = videos.mapNotNull { msg ->
            val size = msg.fileSize
            val name = msg.fileName
            val thumbId = msg.thumbnailFileId?.toString() ?: ""
            val url = "telegram://message?chatId=${msg.chatId}&messageId=${msg.messageId}&size=$size&name=${java.net.URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
            
            val poster = msg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
            
            newMovieSearchResponse(name, url, TvType.Movie) {
                this.posterUrl = poster
            }
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
        
        return messages.mapNotNull { msg ->
                val size = msg.fileSize
                val name = msg.fileName
                val thumbId = msg.thumbnailFileId?.toString() ?: ""
                val url = "telegram://message?chatId=${msg.chatId}&messageId=${msg.messageId}&size=$size&name=${URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
                
                val poster = msg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                
                newMovieSearchResponse(name, url, TvType.Movie) {
                    this.posterUrl = poster
                }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = android.net.Uri.parse(url)
        
        // Handle forum topic URLs - show as TV Series with episodes
        if (url.startsWith("telegram://topic")) {
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

            val episodes = videos.mapIndexed { index, msg ->
                val size = msg.fileSize
                val name = msg.fileName
                val thumbId = msg.thumbnailFileId?.toString() ?: ""
                val episodeUrl = "telegram://message?chatId=${msg.chatId}&messageId=${msg.messageId}&size=$size&name=${java.net.URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
                val poster = msg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(msg.chatId, msg.messageId) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"

                newEpisode(episodeUrl) {
                    this.name = name
                    this.data = episodeUrl
                    this.season = 1
                    this.episode = index + 1
                    this.posterUrl = poster
                }
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
        val chatId = uri.getQueryParameter("chatId")?.toLongOrNull() ?: return false
        val messageId = uri.getQueryParameter("messageId")?.toLongOrNull() ?: return false
        val name = uri.getQueryParameter("name") ?: "Telegram File"
        val size = uri.getQueryParameter("size")?.toLongOrNull() ?: 0L

        val freshFileId = TelegramRepository.getFreshFileId(chatId, messageId) ?: return false
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

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}