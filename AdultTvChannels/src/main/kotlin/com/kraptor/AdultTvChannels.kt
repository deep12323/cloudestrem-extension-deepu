// ! Bu araç @Kraptor123 tarafından | @Cs-GizliKeyif için yazılmıştır.

package com.kraptor

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AdultTvChannels : MainAPI() {
    override var mainUrl              = "https://adult-tv-channels.com"
    override var name                 = "AdultTvChannels"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)
    override var sequentialMainPage   = true

    override val mainPage = mainPageOf(
        "${mainUrl}/page/1/" to "Page 1",
        "${mainUrl}/page/2/" to "Page 2",
        "${mainUrl}/page/3/" to "Page 3",
        "${mainUrl}/page/4/" to "Page 4",
        "${mainUrl}/page/5/" to "Page 5",
        "${mainUrl}/page/6/" to "Page 6",
        "${mainUrl}/page/7/" to "Page 7",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = try {
            app.get(
                request.data,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                )
            ).document
        } catch (e: Exception) {
            Log.d(name, "getMainPage error: url=${request.data} msg=${e.message}")
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = emptyList(),
                    isHorizontalImages = true
                ),
                hasNext = false
            )
        }
        val home = document.select("article.entry-item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = false
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a") ?: return null
        val title        = titleElement.text().trim().ifBlank { return null }
        val href         = fixUrlNull(titleElement.attr("href")) ?: return null
        val posterUrl    = fixUrlNull(this.selectFirst("figure.entry-item-thumb img")?.attr("src"))

        return newLiveSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url      = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )
        ).document
        val results  = document.select("article.entry-item").mapNotNull { it.toMainPageResult() }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )
        ).document

        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(
            document.selectFirst("figure.entry-thumb img")?.attr("src")
                ?: document.selectFirst("figure.entry-item-thumb img")?.attr("src")
        )
        val description = "Watch Live Adult TV - $title"
        val iframeSrc   = document.selectFirst("iframe")?.attr("src")?.ifBlank { null }
            ?: document.selectFirst("iframe")?.attr("data-src")?.ifBlank { null }
        val iframe      = fixUrlNull(iframeSrc) ?: return null

        return newLiveStreamLoadResponse(title, url, iframe) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks data=$data")

        val iframeUrl = if (data.contains("/tv/") || data.endsWith(".php")) {
            data
        } else {
            val pageDoc = app.get(
                data,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                )
            ).document
            val src = pageDoc.selectFirst("iframe")?.attr("src")?.ifBlank { null }
                ?: pageDoc.selectFirst("iframe")?.attr("data-src")?.ifBlank { null }
            fixUrlNull(src) ?: data
        }

        val html = app.get(
            iframeUrl,
            referer = "$mainUrl/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )
        ).text

        var videoM3u8: String? = null

        // Pattern 1: Obfuscated XOR cipher array:
        // const signedUrl = (function(){var k=13,a=[101,121,...],r='';for(...)...})();
        val xorRegex = Regex("""(?:var|let|const)?\s*k\s*=\s*(\d+)\s*,\s*a\s*=\s*\[([0-9,\s]+)\]""", RegexOption.IGNORE_CASE)
        val xorMatch = xorRegex.find(html)
        if (xorMatch != null) {
            try {
                val k = xorMatch.groupValues[1].toInt()
                val numbers = xorMatch.groupValues[2].split(",").mapNotNull { it.trim().toIntOrNull() }
                val decoded = numbers.map { (it xor k).toChar() }.joinToString("")
                if (decoded.isNotBlank() && decoded.startsWith("http")) {
                    videoM3u8 = decoded.trim()
                }
            } catch (e: Exception) {
                Log.d(name, "XOR decode failed: ${e.message}")
            }
        }

        // Pattern 2: Plain signedUrl or file with quotes (original pattern fallback)
        if (videoM3u8.isNullOrBlank()) {
            val plainRegex = Regex("""(?:signedUrl\s*=|file\s*:)\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val match = plainRegex.find(html)?.groupValues?.get(1)?.ifBlank { null }
            if (match != null) {
                videoM3u8 = match.trim()
            }
        }

        // Pattern 3: Any direct .m3u8 link in script / html
        if (videoM3u8.isNullOrBlank()) {
            val m3u8Regex = Regex("""["'](https?:[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            val match = m3u8Regex.find(html)?.groupValues?.get(1)?.ifBlank { null }
            if (match != null) {
                videoM3u8 = match.trim()
            }
        }

        if (videoM3u8.isNullOrBlank()) {
            Log.d(name, "No video stream link could be found for $data")
            return false
        }

        val finalUrl = when {
            videoM3u8.startsWith("//") -> "https:$videoM3u8"
            videoM3u8.startsWith("http:") -> videoM3u8.replace("http:", "https:")
            !videoM3u8.startsWith("http") -> fixUrl(videoM3u8)
            else -> videoM3u8
        }

        Log.d(name, "Resolved stream URL: $finalUrl")

        callback.invoke(
            newExtractorLink(
                name = name,
                source = name,
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            }
        )

        return true
    }
}
