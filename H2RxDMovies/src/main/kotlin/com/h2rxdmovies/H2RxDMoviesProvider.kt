package com.h2rxdmovies

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class H2RxDMoviesProvider : MainAPI() {
    override var mainUrl = "https://h2r-xdmovies.pages.dev"
    override var name = "H2RxDMovies"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiUrl = "https://xd-api.hdmovielover.workers.dev"
    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    override val mainPage = mainPageOf(
        "$apiUrl/api/v1/catalog?sort=lastmod" to "Latest Releases",
        "$apiUrl/api/v1/movies?sort=lastmod" to "Latest Movies",
        "$apiUrl/api/v1/series?sort=lastmod" to "Latest TV Series",
        "$apiUrl/api/v1/catalog?sort=downloads" to "Most Popular / Trending",
        "$apiUrl/api/v1/movies?sort=rating" to "Top Rated Movies",
        "$apiUrl/api/v1/series?sort=rating" to "Top Rated TV Series",
        "$apiUrl/api/v1/movies?audio=Hindi" to "Hindi Movies",
        "$apiUrl/api/v1/movies?audio=Tamil" to "Tamil Movies",
        "$apiUrl/api/v1/movies?audio=Telugu" to "Telugu Movies",
        "$apiUrl/api/v1/movies?audio=Malayalam" to "Malayalam Movies",
        "$apiUrl/api/v1/catalog?genre=Action" to "Action",
        "$apiUrl/api/v1/catalog?genre=Thriller" to "Thriller & Crime",
        "$apiUrl/api/v1/catalog?genre=Comedy" to "Comedy",
        "$apiUrl/api/v1/catalog?source=Netflix" to "Netflix Releases",
        "$apiUrl/api/v1/catalog?source=Amazon" to "Prime Video Releases",
        "$apiUrl/api/v1/catalog?source=JioHotstar" to "JioHotstar Releases",
        "$apiUrl/api/v1/catalog?source=Zee5" to "Zee5 Releases"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json"
    )

    private fun MediaItem.toSearchResponse(): SearchResponse? {
        val title = this.title ?: return null
        val isSeries = this.type.equals("series", ignoreCase = true)
        val itemUrl = "$mainUrl/media/${this.id}"

        return if (isSeries) {
            newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                this.posterUrl = this@toSearchResponse.poster
                this.year = this@toSearchResponse.release_year
            }
        } else {
            newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                this.posterUrl = this@toSearchResponse.poster
                this.year = this@toSearchResponse.release_year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val requestUrl = if (request.data.contains("?")) {
            "${request.data}&page=$page&limit=20"
        } else {
            "${request.data}?page=$page&limit=20"
        }

        try {
            val response = app.get(requestUrl, headers = requestHeaders, timeout = 15L)
            if (!response.isSuccessful) return null

            val catalog: CatalogResponse = mapper.readValue(response.text)
            val items = catalog.results.mapNotNull { it.toSearchResponse() }
            val hasNext = catalog.page < catalog.totalPages

            return newHomePageResponse(HomePageList(request.name, items), hasNext)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$apiUrl/api/v1/search?q=$encodedQuery&limit=30"

        try {
            val response = app.get(searchUrl, headers = requestHeaders, timeout = 15L)
            if (!response.isSuccessful) return emptyList()

            val catalog: CatalogResponse = mapper.readValue(response.text)
            return catalog.results.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").trim().toIntOrNull() ?: return null
        val detailUrl = "$apiUrl/api/v1/media/$id"

        try {
            val detailResp = app.get(detailUrl, headers = requestHeaders, timeout = 15L)
            if (!detailResp.isSuccessful) return null

            val mediaDetail: MediaDetailResponse = mapper.readValue(detailResp.text)
            val media = mediaDetail.data ?: return null

            val title = media.title ?: "Unknown"
            val isSeries = media.type.equals("series", ignoreCase = true)
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            if (isSeries) {
                val dlResp = app.get("$apiUrl/api/v1/series/$id/downloads", headers = requestHeaders, timeout = 15L)
                val episodesList = mutableListOf<Episode>()

                if (dlResp.isSuccessful) {
                    val seriesData: SeriesDownloadsResponse = mapper.readValue(dlResp.text)
                    for (season in seriesData.seasons) {
                        val seasonNum = season.season

                        // Individual episodes
                        for (ep in season.episodes) {
                            val epNum = ep.episode
                            val epPayload = mapper.writeValueAsString(ep.downloads)
                            episodesList.add(
                                newEpisode(epPayload) {
                                    this.name = "Episode $epNum"
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = media.poster
                                }
                            )
                        }

                        // Season packs / batch zips (placed in Specials / Season 0)
                        for ((index, pack) in season.packs.withIndex()) {
                            val cleanName = pack.file_name ?: "Season Pack"
                            val sizeStr = if (!pack.size.isNullOrBlank()) " • ${pack.size}" else ""
                            val resStr = if (!pack.resolution.isNullOrBlank()) " • ${pack.resolution}" else ""
                            val packPayload = mapper.writeValueAsString(listOf(pack))
                            episodesList.add(
                                newEpisode(packPayload) {
                                    this.name = "📦 [ZIP PACK] $cleanName$resStr$sizeStr"
                                    this.season = 0
                                    this.episode = index + 1
                                    this.posterUrl = media.poster
                                }
                            )
                        }
                    }
                }

                return newTvSeriesLoadResponse(title, url, type, episodesList) {
                    this.posterUrl = media.poster
                    this.backgroundPosterUrl = media.backdrop
                    this.year = media.release_year
                    this.plot = media.overview
                    this.tags = media.genres
                    this.actors = media.cast?.map { ActorData(Actor(it, null)) }
                    this.score = Score.from10(media.rating_num)
                }
            } else {
                val dlResp = app.get("$apiUrl/api/v1/movies/$id/downloads", headers = requestHeaders, timeout = 15L)
                val downloads = if (dlResp.isSuccessful) {
                    val dlData: MovieDownloadsResponse = mapper.readValue(dlResp.text)
                    dlData.downloads
                } else {
                    emptyList()
                }

                val moviePayload = mapper.writeValueAsString(downloads)

                return newMovieLoadResponse(title, url, type, moviePayload) {
                    this.posterUrl = media.poster
                    this.backgroundPosterUrl = media.backdrop
                    this.year = media.release_year
                    this.plot = media.overview
                    this.tags = media.genres
                    this.actors = media.cast?.map { ActorData(Actor(it, null)) }
                    this.score = Score.from10(media.rating_num)
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
        val downloads: List<DownloadItem> = try {
            mapper.readValue(data)
        } catch (e: Exception) {
            return false
        }

        if (downloads.isEmpty()) return false

        coroutineScope {
            downloads.map { download ->
                async {
                    val hubUrl = download.hubcloud_url
                    if (!hubUrl.isNullOrBlank() && (hubUrl.contains("hubcloud") || hubUrl.contains("hubc"))) {
                        HubCloud.resolveHubCloud(hubUrl, download, callback)
                    } else {
                        val dlUrl = download.download_url
                        if (!dlUrl.isNullOrBlank()) {
                            if (dlUrl.contains("hubcloud") || dlUrl.contains("hubc")) {
                                HubCloud.resolveHubCloud(dlUrl, download, callback)
                            } else {
                                loadExtractor(dlUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        return true
    }
}
