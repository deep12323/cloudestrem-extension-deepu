package com.telegram

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.INFER_TYPE
import org.drinkless.tdlib.TdApi

class TeleflixProvider : MainAPI() {
    override var mainUrl = "https://v3-cinemeta.strem.io"
    override var name = "Teleflix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/catalog/movie/top.json" to "Top Movies",
        "$mainUrl/catalog/series/top.json" to "Top TV Shows"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Cinemeta pagination is usually skip=0, skip=100 etc. or skip is not fully supported for top.
        // For simplicity, we just fetch the top page.
        if (page > 1) return HomePageResponse(emptyList(), false)

        val response = app.get(request.data).text
        val catalog = parseJson<CinemetaCatalog>(response)

        val items = catalog.metas.map { meta ->
            val isMovie = meta.type == "movie"
            newMovieSearchResponse(meta.name, meta.id, if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = meta.poster
            }
        }

        return newHomePageResponse(request.name, items, false)
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
            newMovieSearchResponse(meta.name, meta.id, if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = meta.poster
            }
        }

        return all
    }

    override suspend fun load(url: String): LoadResponse {
        val type = if (url.startsWith("tt")) {
            // It's an IMDb ID. We need to check if it's a movie or series.
            // Let's just try movie first, if fail try series.
            "movie"
        } else url

        // url is the id (e.g. tt1234567)
        val metaUrl = "$mainUrl/meta/movie/$url.json" // Try movie
        var metaResponse = app.get(metaUrl).text
        var meta = parseJson<CinemetaMetaResponse>(metaResponse).meta
        
        var isSeries = false
        if (meta == null) {
            val seriesUrl = "$mainUrl/meta/series/$url.json"
            metaResponse = app.get(seriesUrl).text
            meta = parseJson<CinemetaMetaResponse>(metaResponse).meta
            isSeries = true
        }
        
        if (meta == null) throw ErrorLoadingException("Failed to load metadata")

        if (isSeries) {
            val episodes = meta.videos?.map { video ->
                val season = video.season ?: 1
                val ep = video.episode ?: 1
                // We pass a custom data string to loadLinks containing the show name and episode
                val data = "${meta.name} S${season.toString().padStart(2, '0')}E${ep.toString().padStart(2, '0')}"
                newEpisode(data) {
                    this.name = video.name ?: "Episode $ep"
                    this.season = season
                    this.episode = ep
                    this.posterUrl = video.thumbnail ?: meta.poster
                }
            } ?: emptyList()

            return newTvSeriesLoadResponse(meta.name, url, TvType.TvSeries, episodes) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = meta.description
                this.year = meta.year?.toIntOrNull()
            }
        } else {
            return newMovieLoadResponse(meta.name, url, TvType.Movie, meta.name) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = meta.description
                this.year = meta.year?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data contains the movie name or series query (e.g. "Spider-Man" or "Breaking Bad S01E01")
        if (!TelegramRepository.waitUntilAuthenticated()) {
            throw ErrorLoadingException("Please login to Telegram in settings first!")
        }

        val queries = mutableSetOf<String>()
        queries.add(data)
        
        // Multi-search logic: generate variations (e.g. S01E01 -> 1x01)
        val sxxEyyRegex = Regex("(?i)S(\\d{1,2})E(\\d{1,2})")
        val match = sxxEyyRegex.find(data)
        if (match != null) {
            val s = match.groupValues[1].toInt()
            val e = match.groupValues[2].toInt()
            val baseName = data.substring(0, match.range.first).trim()
            queries.add("$baseName ${s}x${String.format("%02d", e)}")
            queries.add("$baseName ${s}x$e")
            queries.add("$baseName S${String.format("%02d", s)} E${String.format("%02d", e)}")
            queries.add(baseName) // Fallback to just the series name
        }

        // Punctuation and spacing variations for movies and shows (e.g. Spider-Man -> Spider Man)
        val queriesCopy = queries.toList()
        val punctRegex = Regex("[^a-zA-Z0-9 ]")
        for (q in queriesCopy) {
            if (punctRegex.containsMatchIn(q)) {
                queries.add(q.replace(punctRegex, " ").replace(Regex(" +"), " ").trim())
                queries.add(q.replace(punctRegex, ""))
            }
        }
        
        val results = mutableSetOf<TelegramVideoMessage>()
        for (q in queries) {
            val res = TelegramRepository.searchVideoMessages(q, limit = 20)
            results.addAll(res)
        }
        
        if (results.isEmpty()) {
            throw ErrorLoadingException("No streams found on Telegram for '$data'")
        }

        results.forEach { msg ->
            val streamUrl = TelegramRepository.getStreamUrl(msg.fileId)
            val sizeStr = TelegramProvider.formatBytes(msg.fileSize)
            
            callback.invoke(
                ExtractorLink(
                    source = "Telegram",
                    name = "${msg.fileName} ($sizeStr)",
                    url = streamUrl,
                    referer = "",
                    quality = getQualityFromName(msg.fileName),
                    isM3u8 = false,
                    headers = emptyMap()
                )
            )
        }

        return true
    }

    private fun getQualityFromName(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
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
        val videos: List<CinemetaVideo>? = null
    )

    private data class CinemetaVideo(
        val id: String,
        val name: String?,
        val season: Int?,
        val episode: Int?,
        val thumbnail: String?
    )
}
