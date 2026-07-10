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
    override val supportedTypes = setOf(TvType.Movie)

    override var lang = "en"
    override val hasMainPage = true

    override val mainPage: List<MainPageData>
        get() {
            val context = try { TelegramRepository.getContext() } catch (e: Exception) { return emptyList() }
            val channels = TelegramRepository.getCustomChannels(context)
            return mainPageOf(
                *channels.map { it.toString() to "Channel" }.toTypedArray()
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
        val result = TelegramRepository.getChannelVideos(chanId, page = page, limit = 50) ?: return null
        
        val title = result.first
        val videos = result.second

        if (videos.isEmpty() && page > 1) return null

        val searchResponses = videos.mapNotNull { msg ->
            val size = msg.fileSize
            val name = msg.fileName
            val thumbId = msg.thumbnailFileId?.toString() ?: ""
            // Remove fileId from URL to ensure watch history stability, as fileId changes across TDLib sessions.
            val url = "telegram://message?chatId=${msg.chatId}&messageId=${msg.messageId}&size=$size&name=${URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
            
            val poster = msg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(it) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
            
            newMovieSearchResponse(name, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        // Enable endless pagination horizontally (assuming result > 0 and next page could have more)
        val hasNext = videos.isNotEmpty()
        return newHomePageResponse(title, searchResponses, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (!TelegramRepository.waitUntilAuthenticated()) return emptyList()
        val messages = TelegramRepository.searchVideoMessages(query)
        
        return messages.mapNotNull { msg ->
                val size = msg.fileSize
                val name = msg.fileName
                val thumbId = msg.thumbnailFileId?.toString() ?: ""
                val url = "telegram://message?chatId=${msg.chatId}&messageId=${msg.messageId}&size=$size&name=${URLEncoder.encode(name, "UTF-8")}&thumbnailFileId=$thumbId"
                
                val poster = msg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(it) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                
                newMovieSearchResponse(name, url, TvType.Movie) {
                    this.posterUrl = poster
                }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = android.net.Uri.parse(url)
        val size = uri.getQueryParameter("size")?.toLongOrNull() ?: 0L
        val name = uri.getQueryParameter("name") ?: "Telegram File"
        val thumbnailFileId = uri.getQueryParameter("thumbnailFileId")?.toIntOrNull()

        val poster = thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(it) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"

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

        val freshFileId = TelegramRepository.getFreshFileId(chatId, messageId) ?: return false
        val streamUrl = TelegramRepository.getStreamUrl(freshFileId, name)
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