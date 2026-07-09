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
        for (chan in channels) {
            val result = TelegramRepository.getChannelVideos(chan) ?: continue
            val title = result.first
            val videos = result.second

            // Fetch posters in parallel
            val searchResponses = coroutineScope {
                videos.map { msg ->
                    async {
                        val fileId = msg.fileId
                        val size = msg.fileSize
                        val name = msg.fileName
                        val url = "telegram://file?fileId=$fileId&size=$size&name=${URLEncoder.encode(name, "UTF-8")}&chatId=${msg.chatId}&messageId=${msg.messageId}"
                        
                        val poster = fetchPoster(name) ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                        
                        newMovieSearchResponse(name, url, TvType.Movie) {
                            this.posterUrl = poster
                        }
                    }
                }.awaitAll()
            }

            if (searchResponses.isNotEmpty()) {
                homePages.add(HomePageList(title, searchResponses))
            }
        }

        return newHomePageResponse(homePages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (!TelegramRepository.isAuthenticated()) {
            return emptyList()
        }
        val messages = TelegramRepository.searchVideoMessages(query)
        
        // Fetch posters in parallel
        return coroutineScope {
            messages.map { msg ->
                async {
                    val fileId = msg.fileId
                    val size = msg.fileSize
                    val name = msg.fileName
                    val url = "telegram://file?fileId=$fileId&size=$size&name=${URLEncoder.encode(name, "UTF-8")}&chatId=${msg.chatId}&messageId=${msg.messageId}"
                    
                    val poster = fetchPoster(name) ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"
                    
                    newMovieSearchResponse(name, url, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = android.net.Uri.parse(url)
        val fileId = uri.getQueryParameter("fileId")?.toIntOrNull() ?: throw Exception("Invalid fileId")
        val size = uri.getQueryParameter("size")?.toLongOrNull() ?: 0L
        val name = uri.getQueryParameter("name") ?: "Telegram File"

        val poster = fetchPoster(name) ?: "https://images.unsplash.com/photo-1543087903-1ac2ec7aa8c5?w=500"

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

    private suspend fun fetchPoster(title: String): String? {
        return try {
            val cleanTitle = cleanFileNameForSearch(title)
            if (cleanTitle.isBlank()) return null
            
            val url = "https://api.themoviedb.org/3/search/multi?api_key=b66d54848312e022f4625b5d1239ab7b&query=${URLEncoder.encode(cleanTitle, "UTF-8")}"
            val response = app.get(url).text
            
            val json = com.lagradost.cloudstream3.utils.AppUtils.tryParseJson<Map<String, Any>>(response)
            val results = json?.get("results") as? List<Map<String, Any>>
            val firstResult = results?.firstOrNull { 
                it.containsKey("poster_path") && it["poster_path"] != null 
            }
            val posterPath = firstResult?.get("poster_path") as? String
            if (posterPath != null) {
                "https://image.tmdb.org/t/p/w500$posterPath"
            } else null
        } catch (e: Exception) {
            Log.e("TelegramProvider", "Failed to fetch poster for $title: ${e.message}")
            null
        }
    }

    private fun cleanFileNameForSearch(fileName: String): String {
        var name = fileName.substringBeforeLast(".")
        name = name.replace(Regex("[._-]"), " ")
        
        val patterns = listOf(
            Regex("(?i)s\\d+e\\d+"),
            Regex("(?i)season\\s*\\d+"),
            Regex("(?i)episode\\s*\\d+"),
            Regex("\\b(19|20)\\d{2}\\b"),
            Regex("(?i)\\b(1080p|720p|480p|360p|4k|uhd|bluray|hdtv|webrip|web-dl|x264|x265|hevc|dd5\\.1|dual-audio|multi)\\b")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(name)
            if (match != null) {
                name = name.substring(0, match.range.first)
            }
        }
        
        return name.replace(Regex("\\s+"), " ").trim()
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

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%.0f KB".format(bytes / 1_000.0)
    }
}