package com.moviesdrive

import android.content.Context
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Collections

class MoviesDriveProvider(private val pluginContext: Context? = null) : MainAPI() {
    override var mainUrl = "https://new3.moviesdrive.cyou"
    override var name = "MoviesDrive"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    override val mainPage: List<MainPageData>
        get() = MoviesDrivePreferences.getActiveCategories(pluginContext, mainUrl)

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("""(?i)\s*[-–|]?\s*MoviesDrives?.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*Full Movie.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*Complete Web Series.*$"""), "")
            .replace("&amp;", "&")
            .replace("&#8211;", "-")
            .replace("&#8217;", "'")
            .replace("&#038;", "&")
            .replace("&hellip;", "...")
            .trim()
    }

    private fun isTvSeries(title: String, url: String): Boolean {
        val combined = "$title $url".lowercase()
        return combined.contains("season") ||
                combined.contains("series") ||
                combined.contains("episode") ||
                Regex("""\bs\d+\b""").containsMatchIn(combined)
    }

    private fun extractYear(title: String): Int? {
        val match = Regex("""\((\d{4})\)""").find(title)
            ?: Regex("""\b(19\d\d|20\d\d)\b""").find(title)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parsePosterCard(element: Element): SearchResponse? {
        val linkElem = when {
            element.tagName().equals("a", ignoreCase = true) -> element
            element.parent()?.tagName()?.equals("a", ignoreCase = true) == true -> element.parent()
            else -> element.selectFirst("a") ?: element.closest("a") ?: element.parents().firstOrNull { it.tagName().equals("a", ignoreCase = true) }
        }
        val rawHref = linkElem?.attr("href")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val itemUrl = if (rawHref.startsWith("http")) rawHref else "$mainUrl${if (rawHref.startsWith("/")) "" else "/"}$rawHref"

        val imgElem = element.selectFirst("img") ?: linkElem.selectFirst("img")
        val rawTitle = element.selectFirst("p.poster-title, .poster-title")?.text()
            ?: linkElem.selectFirst("p.poster-title, .poster-title")?.text()
            ?: imgElem?.attr("alt")
            ?: linkElem.text()
        if (rawTitle.isBlank()) return null

        val title = cleanTitle(rawTitle)
        val posterUrl = imgElem?.attr("src")?.takeIf { it.isNotBlank() }
            ?: imgElem?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElem?.attr("data-original")
        val year = extractYear(rawTitle)
        val isSeries = isTvSeries(rawTitle, itemUrl)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val baseTarget = request.data.removeSuffix("/")
        val url = if (baseTarget.equals(mainUrl, ignoreCase = true) || baseTarget.isEmpty()) {
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            if (page <= 1) "$baseTarget/" else "$baseTarget/page/$page/"
        }

        try {
            val response = app.get(url, headers = requestHeaders, timeout = 15L)
            if (!response.isSuccessful) return null

            val doc = response.document
            val cards = doc.select(".movies-grid > a, div.poster-card")
            val items = cards.mapNotNull { parsePosterCard(it) }.distinctBy { it.url }

            val hasNext = doc.selectFirst("a.next-btn, a.next, .pagination a.next, .pagination a.next-btn, a:contains(Next)") != null || items.size >= 24

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                ),
                hasNext = hasNext
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // 1. Primary: Fast Typesense JSON search
        val typesenseUrl = "$mainUrl/search.php?q=$encodedQuery&page=$page"
        try {
            val response = app.get(typesenseUrl, headers = requestHeaders, timeout = 15L)
            if (response.isSuccessful && response.text.trim().startsWith("{")) {
                val tsData: TypesenseResponse = mapper.readValue(response.text)
                val hits = tsData.hits ?: emptyList()
                val items = hits.mapNotNull { hit ->
                    val doc = hit.document ?: return@mapNotNull null
                    val rawTitle = doc.post_title ?: return@mapNotNull null
                    val permalink = doc.permalink ?: return@mapNotNull null
                    val fullUrl = if (permalink.startsWith("http")) permalink else "$mainUrl${if (permalink.startsWith("/")) "" else "/"}$permalink"
                    val title = cleanTitle(rawTitle)
                    val isSeries = isTvSeries(rawTitle, fullUrl) ||
                            (doc.category?.any { it.contains("series", ignoreCase = true) || it.contains("web", ignoreCase = true) } == true)
                    val year = extractYear(rawTitle)
                    val posterUrl = doc.post_thumbnail

                    if (isSeries) {
                        newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    } else {
                        newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    }
                }

                if (items.isNotEmpty()) {
                    val found = tsData.found ?: 0
                    val hasNext = (page * 15) < found
                    return newSearchResponseList(items, hasNext)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Secondary: WordPress REST API Search
        val wpApiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$encodedQuery&page=$page&per_page=20"
        try {
            val response = app.get(wpApiUrl, headers = mapOf("User-Agent" to requestHeaders["User-Agent"]!!, "Accept" to "application/json"), timeout = 15L)
            if (response.isSuccessful && response.text.trim().startsWith("[")) {
                val posts: List<WpPost> = mapper.readValue(response.text)
                val items = posts.mapNotNull { post ->
                    val rawTitle = post.title?.rendered ?: return@mapNotNull null
                    val link = post.link ?: return@mapNotNull null
                    val title = cleanTitle(rawTitle)
                    val isSeries = isTvSeries(rawTitle, link)
                    val year = extractYear(rawTitle)

                    val contentHtml = post.content?.rendered ?: ""
                    val posterMatch = Regex("""<img[^>]+src=["'](https?://[^"'\s]*tmdb\.org[^"'\s]*)["']""").find(contentHtml)
                        ?: Regex("""<img[^>]+src=["'](https?://[^"'\s]+\.(?:jpg|jpeg|png|webp))["']""").find(contentHtml)
                    val posterUrl = posterMatch?.groupValues?.get(1)

                    if (isSeries) {
                        newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    } else {
                        newMovieSearchResponse(title, link, TvType.Movie) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    }
                }

                val totalPages = response.headers["x-wp-totalpages"]?.toIntOrNull() ?: 1
                val hasNext = page < totalPages

                return newSearchResponseList(items, hasNext)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Fallback to HTML search
        val htmlUrl = if (page <= 1) "$mainUrl/?s=$encodedQuery" else "$mainUrl/page/$page/?s=$encodedQuery"
        try {
            val response = app.get(htmlUrl, headers = requestHeaders, timeout = 15L)
            if (!response.isSuccessful) return newSearchResponseList(emptyList(), false)

            val doc = response.document
            val cards = doc.select(".movies-grid > a, div.poster-card")
            val items = cards.mapNotNull { parsePosterCard(it) }.distinctBy { it.url }
            val hasNext = doc.selectFirst("a.next-btn, a.next, .pagination a.next") != null || items.size >= 24

            return newSearchResponseList(items, hasNext)
        } catch (e: Exception) {
            e.printStackTrace()
            return newSearchResponseList(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        try {
            val response = app.get(url, headers = requestHeaders, timeout = 15L)
            if (!response.isSuccessful) return null
            val doc = response.document

            val rawTitle = doc.selectFirst("h1.entry-title, h1, title")?.text() ?: "Unknown"
            val title = cleanTitle(rawTitle)
            val year = extractYear(rawTitle)
            val isSeries = isTvSeries(rawTitle, url)
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            // Poster extraction
            val posterUrl = doc.selectFirst("img[src*='tmdb.org']")?.attr("src")
                ?: doc.selectFirst(".entry-content img")?.attr("src")

            // Plot extraction
            val plot = doc.selectFirst("h3:contains(Storyline) + p, p:contains(Storyline)")?.text()
                ?.replace(Regex("""(?i)^.*Storyline\s*:?-?\s*"""), "")
                ?.trim()

            // Rating
            val ratingText = doc.selectFirst("div:contains(iMDB Rating)")?.text()
            val ratingMatch = Regex("""(\d+(?:\.\d+)?)\s*/\s*10""").find(ratingText ?: "")
            val scoreVal = ratingMatch?.groupValues?.get(1)?.toDoubleOrNull()

            // Tags / Genres
            val genreText = doc.selectFirst("div:contains(Genre:)")?.text()
            val genres = genreText?.substringAfter("Genre:")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

            // Actors
            val starsText = doc.selectFirst("div:contains(Stars:)")?.text()
            val actors = starsText?.substringAfter("Stars:")?.split(",")?.map { ActorData(Actor(it.trim(), null)) }

            // Parse download anchors and season context
            val contentElem = doc.selectFirst(".entry-content") ?: doc
            val allElements = contentElem.select("h1, h2, h3, h4, h5, h6, p, div")

            data class ScrapedDownload(
                val season: Int,
                val text: String,
                val url: String,
                val isSingleEpisode: Boolean,
                val isZip: Boolean,
                val quality: String?,
                val size: String?
            )

            val scrapedDownloads = mutableListOf<ScrapedDownload>()
            val seenHrefs = mutableSetOf<String>()
            var currentSeason = 1

            for (elem in allElements) {
                val text = elem.text()
                val seasonMatch = Regex("""(?i)\bSeason\s*0*(\d+)\b""").find(text)
                if (seasonMatch != null && !text.contains("Single Episode", ignoreCase = true) && !text.contains("Zip", ignoreCase = true)) {
                    currentSeason = seasonMatch.groupValues[1].toIntOrNull() ?: currentSeason
                }

                val anchors = elem.select("a[href*='mdrive.'], a[href*='hubcloud.'], a[href*='gdflix.'], a[href*='only-apis.com']")
                for (anchor in anchors) {
                    val href = anchor.attr("href").trim()
                    val linkText = anchor.text().trim()
                    if (href.isBlank() || !href.startsWith("http") || !seenHrefs.add(href)) continue

                    val combinedContext = "$text $linkText"
                    val isSingle = linkText.contains("Single Episode", ignoreCase = true) || href.contains("single", ignoreCase = true)
                    val isZip = linkText.contains("Zip", ignoreCase = true) || href.contains("zip", ignoreCase = true)
                    val quality = Regex("""\b(2160p|4k|1080p|720p|480p)\b""", RegexOption.IGNORE_CASE).find(combinedContext)?.value
                    val size = Regex("""\[([0-9.]+\s*(?:GB|MB))\]""", RegexOption.IGNORE_CASE).find(combinedContext)?.groupValues?.get(1)

                    scrapedDownloads.add(
                        ScrapedDownload(
                            season = currentSeason,
                            text = linkText.ifBlank { "Download" },
                            url = href,
                            isSingleEpisode = isSingle,
                            isZip = isZip,
                            quality = quality,
                            size = size
                        )
                    )
                }
            }

            if (isSeries) {
                val episodesList = mutableListOf<Episode>()

                // Group all single episode archive links by season
                val singleEpBySeason = scrapedDownloads.filter { it.isSingleEpisode }.groupBy { it.season }

                if (singleEpBySeason.isNotEmpty()) {
                    coroutineScope {
                        singleEpBySeason.map { (seasonNum, seasonLinks) ->
                            async {
                                val seasonEpDownloadsMap = mutableMapOf<Int, MutableList<MovieDownloadLink>>()
                                val seasonEpQualitiesMap = mutableMapOf<Int, MutableSet<String>>()

                                // Concurrently fetch ALL quality archives for this season to show ALL links
                                coroutineScope {
                                    seasonLinks.map { archive ->
                                        async {
                                            try {
                                                val epResp = app.get(archive.url, headers = requestHeaders, timeout = 12L)
                                                if (epResp.isSuccessful) {
                                                    val epDoc = epResp.document
                                                    val epContent = epDoc.selectFirst(".entry-content") ?: epDoc
                                                    val epElems = epContent.select("h1, h2, h3, h4, h5, h6, p, div")

                                                    var currentEpNum = 1
                                                    var currentQuality = archive.quality
                                                    var currentSize = archive.size

                                                    for (el in epElems) {
                                                        val elText = el.text()
                                                        val epMatch = Regex("""(?i)\b(?:EP|Episode)\s*0*(\d+)\b""").find(elText)
                                                        if (epMatch != null) {
                                                            currentEpNum = epMatch.groupValues[1].toIntOrNull() ?: currentEpNum
                                                            val qMatch = Regex("""\b(2160p|4k|1080p|720p|480p)\b""", RegexOption.IGNORE_CASE).find(elText)?.value
                                                            if (qMatch != null) currentQuality = qMatch
                                                            val sMatch = Regex("""\[([0-9.]+\s*(?:GB|MB))\]""", RegexOption.IGNORE_CASE).find(elText)?.groupValues?.get(1)
                                                            if (sMatch != null) currentSize = sMatch
                                                        }

                                                        val links = el.select("a[href*='hubcloud.'], a[href*='gdflix.'], a[href*='only-apis.com']")
                                                        for (link in links) {
                                                            val h = link.attr("href").trim()
                                                            val t = link.text().trim()
                                                            if (h.isNotBlank() && h.startsWith("http")) {
                                                                val epLabel = "${archive.text} ${currentQuality ?: ""} ${if (currentSize != null) "[$currentSize]" else ""}".trim()
                                                                val itemDl = MovieDownloadLink(
                                                                    name = epLabel.ifBlank { "Episode $currentEpNum" },
                                                                    url = h,
                                                                    quality = currentQuality,
                                                                    size = currentSize
                                                                )
                                                                synchronized(seasonEpDownloadsMap) {
                                                                    seasonEpDownloadsMap.getOrPut(currentEpNum) { mutableListOf() }.add(itemDl)
                                                                    if (currentQuality != null) {
                                                                        seasonEpQualitiesMap.getOrPut(currentEpNum) { mutableSetOf() }.add(currentQuality)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }

                                                    // Fallback if no EP headings in doc: map sequentially
                                                    if (!seasonEpDownloadsMap.containsKey(1)) {
                                                        val allEpAnchors = epDoc.select("a[href*='hubcloud.'], a[href*='gdflix.'], a[href*='only-apis.com']")
                                                        for ((idx, anchor) in allEpAnchors.withIndex()) {
                                                            val h = anchor.attr("href").trim()
                                                            val t = anchor.text().trim()
                                                            if (h.isNotBlank() && h.startsWith("http")) {
                                                                val epN = idx + 1
                                                                val epLabel = "${archive.text} ${archive.quality ?: ""}".trim()
                                                                val itemDl = MovieDownloadLink(
                                                                    name = epLabel.ifBlank { "Episode $epN" },
                                                                    url = h,
                                                                    quality = archive.quality,
                                                                    size = archive.size
                                                                )
                                                                synchronized(seasonEpDownloadsMap) {
                                                                    seasonEpDownloadsMap.getOrPut(epN) { mutableListOf() }.add(itemDl)
                                                                    if (archive.quality != null) {
                                                                        seasonEpQualitiesMap.getOrPut(epN) { mutableSetOf() }.add(archive.quality)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                    }.awaitAll()
                                }

                                seasonEpDownloadsMap.toSortedMap().forEach { (epNum, linkList) ->
                                    val sortedLinkList = linkList.sortedWith(
                                        compareByDescending<MovieDownloadLink> {
                                            MoviesDriveHubCloud.parseSizeToBytes(it.size ?: it.name, MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name))
                                        }.thenByDescending {
                                            MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name)
                                        }
                                    )
                                    val distinctQualities = sortedLinkList.mapNotNull { it.quality }.distinct()
                                    val qSummary = if (distinctQualities.isNotEmpty()) " (${distinctQualities.joinToString(", ")})" else ""
                                    val epPayload = mapper.writeValueAsString(sortedLinkList)

                                    synchronized(episodesList) {
                                        episodesList.add(
                                            newEpisode(epPayload) {
                                                this.name = "Episode $epNum$qSummary"
                                                this.season = seasonNum
                                                this.episode = epNum
                                                this.posterUrl = posterUrl
                                            }
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }

                // If no single episode archives existed (e.g. mini-series with direct links), add direct downloads as episodes
                if (episodesList.isEmpty()) {
                    val directDownloads = scrapedDownloads.filter { !it.isZip }.sortedWith(
                        compareByDescending<ScrapedDownload> {
                            MoviesDriveHubCloud.parseSizeToBytes(it.size ?: it.text, MoviesDriveHubCloud.getQualityInt(it.quality ?: it.text))
                        }.thenByDescending {
                            MoviesDriveHubCloud.getQualityInt(it.quality ?: it.text)
                        }
                    )
                    for ((idx, dl) in directDownloads.withIndex()) {
                        val payload = mapper.writeValueAsString(
                            listOf(MovieDownloadLink(name = dl.text, url = dl.url, quality = dl.quality, size = dl.size))
                        )
                        episodesList.add(
                            newEpisode(payload) {
                                this.name = "${dl.text} ${dl.quality ?: ""} ${if (dl.size != null) "[${dl.size}]" else ""}".trim()
                                this.season = dl.season
                                this.episode = idx + 1
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }

                // Add Zip packs in Season 0 / Specials
                val zipPacks = scrapedDownloads.filter { it.isZip }.sortedWith(
                    compareByDescending<ScrapedDownload> {
                        MoviesDriveHubCloud.parseSizeToBytes(it.size ?: it.text, MoviesDriveHubCloud.getQualityInt(it.quality ?: it.text))
                    }
                )
                for ((idx, pack) in zipPacks.withIndex()) {
                    val packPayload = mapper.writeValueAsString(
                        listOf(MovieDownloadLink(name = pack.text, url = pack.url, quality = pack.quality, size = pack.size))
                    )
                    episodesList.add(
                        newEpisode(packPayload) {
                            val resStr = if (pack.quality != null) " • ${pack.quality}" else ""
                            val sizeStr = if (pack.size != null) " • [${pack.size}]" else ""
                            this.name = "📦 [ZIP PACK] ${pack.text}$resStr$sizeStr"
                            this.season = 0
                            this.episode = idx + 1
                            this.posterUrl = posterUrl
                        }
                    )
                }

                val sortedEpisodes = episodesList.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))

                return newTvSeriesLoadResponse(title, url, type, sortedEpisodes) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.tags = genres
                    this.actors = actors
                    if (scoreVal != null) this.score = Score.from10(scoreVal)
                }
            } else {
                // Movie: Gather all download items sorted by decreasing file size
                val downloadItems = scrapedDownloads.filter { !it.isZip }.map {
                    MovieDownloadLink(name = it.text, url = it.url, quality = it.quality, size = it.size)
                }.sortedWith(
                    compareByDescending<MovieDownloadLink> {
                        MoviesDriveHubCloud.parseSizeToBytes(it.size ?: it.name, MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name))
                    }.thenByDescending {
                        MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name)
                    }
                )
                val moviePayload = mapper.writeValueAsString(downloadItems)

                return newMovieLoadResponse(title, url, type, moviePayload) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.tags = genres
                    this.actors = actors
                    if (scoreVal != null) this.score = Score.from10(scoreVal)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val downloadItems: List<MovieDownloadLink> = try {
            mapper.readValue(data)
        } catch (e: Exception) {
            return false
        }

        if (downloadItems.isEmpty()) return false

        // Sort download items by decreasing file size
        val sortedItems = downloadItems.sortedWith(
            compareByDescending<MovieDownloadLink> {
                MoviesDriveHubCloud.parseSizeToBytes(it.size ?: it.name, MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name))
            }.thenByDescending {
                MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name)
            }
        )

        data class ExtLinkWithSize(
            val link: ExtractorLink,
            val sizeBytes: Long,
            val serverPriority: Int
        )

        val collectedLinks = Collections.synchronizedList(mutableListOf<ExtLinkWithSize>())

        fun getServerPriority(source: String): Int {
            val s = source.lowercase()
            return when {
                s.contains("fsl") -> 4
                s.contains("10gbps") -> 3
                s.contains("fast") || s.contains("stream") -> 2
                s.contains("pixeldrain") -> 1
                else -> 0
            }
        }

        coroutineScope {
            sortedItems.map { item ->
                async {
                    val itemBytes = MoviesDriveHubCloud.parseSizeToBytes(
                        item.size ?: item.name,
                        MoviesDriveHubCloud.getQualityInt(item.quality ?: item.name)
                    )

                    val itemCollector: (ExtractorLink) -> Unit = { extLink ->
                        val linkBytes = MoviesDriveHubCloud.parseSizeToBytes(extLink.name, extLink.quality).takeIf { it > 0L } ?: itemBytes
                        val priority = getServerPriority(extLink.source)
                        collectedLinks.add(ExtLinkWithSize(extLink, linkBytes, priority))
                    }

                    val itemUrl = item.url
                    if (itemUrl.contains("mdrive.")) {
                        try {
                            val archiveResp = app.get(itemUrl, headers = requestHeaders, timeout = 10L)
                            if (archiveResp.isSuccessful) {
                                val archiveDoc = archiveResp.document
                                val hubLinks = archiveDoc.select("a[href*='hubcloud'], a[href*='hubc']")
                                for (hubLink in hubLinks) {
                                    val hubUrl = hubLink.attr("href")
                                    if (hubUrl.isNotBlank()) {
                                        MoviesDriveHubCloud.resolveHubCloud(hubUrl, item.name, item.quality, item.size, itemCollector)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else if (itemUrl.contains("hubcloud") || itemUrl.contains("hubc")) {
                        MoviesDriveHubCloud.resolveHubCloud(itemUrl, item.name, item.quality, item.size, itemCollector)
                    } else {
                        loadExtractor(itemUrl, subtitleCallback, itemCollector)
                    }
                }
            }.awaitAll()
        }

        // Sort all extracted links strictly based on DECREASING file size, then quality, then server priority
        val sortedLinks = collectedLinks
            .distinctBy { it.link.url }
            .sortedWith(
                compareByDescending<ExtLinkWithSize> { it.sizeBytes }
                    .thenByDescending { it.link.quality }
                    .thenByDescending { it.serverPriority }
            )

        for (item in sortedLinks) {
            callback(item.link)
        }

        return sortedLinks.isNotEmpty()
    }
}
