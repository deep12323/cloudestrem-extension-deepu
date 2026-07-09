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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        if (!TelegramRepository.isAuthenticated()) {
            return null
        }

        val context = TelegramRepository.getContext()
        val channels = TelegramRepository.getCustomChannels(context)
        if (channels.isEmpty()) {
            return null
        }

        val homePages = mutableListOf<HomePageList>()
        var hasMore = false
        
        for (chan in channels) {
            val result = TelegramRepository.getChannelVideos(chan, page) ?: continue
            val title = result.first
            val videos = result.second

            if (videos.isNotEmpty()) {
                hasMore = true
            }

            val searchResponses = videos.mapNotNull { msg ->
                val fileId = msg.fileId
                val size = msg.fileSize
                val name = msg.fileName
                val thumbId = msg.thumbnailFileId?.toString() ?: ""
                val url = "telegram://file?fileId=$fileId&size=$size&name=${URLEncoder.encode(name, "UTF-8")}&chatId=${msg.chatId}&messageId=${msg.messageId}&thumbnailFileId=$thumbId"
                
                val poster = msg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(it) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                
                newMovieSearchResponse(name, url, TvType.Movie) {
                    this.posterUrl = poster
                }
            }

            if (searchResponses.isNotEmpty()) {
                homePages.add(HomePageList(title, searchResponses))
            }
        }

        return newHomePageResponse(homePages, hasNext = hasMore)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (!TelegramRepository.isAuthenticated()) {
            return emptyList()
        }
        val messages = TelegramRepository.searchVideoMessages(query)
        
        return messages.mapNotNull { msg ->
                val fileId = msg.fileId
                val size = msg.fileSize
                val name = msg.fileName
                val thumbId = msg.thumbnailFileId?.toString() ?: ""
                val url = "telegram://file?fileId=$fileId&size=$size&name=${URLEncoder.encode(name, "UTF-8")}&chatId=${msg.chatId}&messageId=${msg.messageId}&thumbnailFileId=$thumbId"
                
                val poster = msg.thumbnailFileId?.takeIf { it != 0 }?.let { TelegramRepository.getThumbnailUrl(it) } ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                
                newMovieSearchResponse(name, url, TvType.Movie) {
                    this.posterUrl = poster
                }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = android.net.Uri.parse(url)
        val fileId = uri.getQueryParameter("fileId")?.toIntOrNull() ?: throw Exception("Invalid fileId")
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
        val fileId = uri.getQueryParameter("fileId")?.toIntOrNull() ?: return false
        val name = uri.getQueryParameter("name") ?: "Telegram File"

        val streamUrl = TelegramRepository.getStreamUrl(fileId)
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