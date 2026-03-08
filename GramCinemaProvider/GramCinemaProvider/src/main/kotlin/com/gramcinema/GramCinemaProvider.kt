package com.gramcinema

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

// ─────────────────────────────────────────────────────────────────────────────
//  GramCinemaProvider  —  CloudStream 3 plugin for bollywood.eu.org
//  Uses the site's TMDB proxy for metadata + VidSrc for streaming
// ─────────────────────────────────────────────────────────────────────────────

class GramCinemaProvider : MainAPI() {

    // ── Identity ──────────────────────────────────────────────────────────────
    override var mainUrl     = "https://bollywood.eu.org"
    override var name        = "Gram Cinema"
    override val lang        = "hi"
    override val hasMainPage = true
    override val hasSearch   = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ── API endpoints (discovered via DevTools) ───────────────────────────────
    private val API      = "https://bollywood.eu.org/tmdb/3"
    private val IMG_BASE = "https://image.tmdb.org/t/p/w500"

    // ── Home page rows ────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$API/trending/movie/week"                      to "🔥 Trending Movies",
        "$API/movie/popular"                            to "🎬 Popular Movies",
        "$API/movie/top_rated"                          to "⭐ Top Rated Movies",
        "$API/trending/tv/week"                         to "📺 Trending TV Shows",
        "$API/tv/popular"                               to "🌟 Popular TV Shows",
        "$API/discover/movie?with_original_language=hi" to "🇮🇳 Bollywood",
        "$API/discover/movie?with_original_language=ta" to "🎭 Tamil Movies",
        "$API/discover/movie?with_original_language=te" to "🎭 Telugu Movies"
    )

    // ── Fetch one home page row ───────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = "${request.data}&page=$page"
        val json = app.get(url).parsedSafe<TmdbPageResponse>()
            ?: return newHomePageResponse(request.name, emptyList())

        val isTV    = request.data.contains("/tv")
        val items   = json.results?.mapNotNull { it.toSearchResult(isTV) } ?: emptyList()
        val hasNext = (json.page ?: 1) < (json.total_pages ?: 1)

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ── Search ────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val movies = app.get("$API/search/movie?query=${query.encodeUrl()}")
            .parsedSafe<TmdbPageResponse>()
            ?.results?.mapNotNull { it.toSearchResult(false) } ?: emptyList()

        val shows = app.get("$API/search/tv?query=${query.encodeUrl()}")
            .parsedSafe<TmdbPageResponse>()
            ?.results?.mapNotNull { it.toSearchResult(true) } ?: emptyList()

        return movies + shows
    }

    // ── Detail page ───────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val isTV   = url.contains("/tv/")
        val tmdbId = url.trimEnd('/').substringAfterLast('/')

        val endpoint = if (isTV) "$API/tv/$tmdbId" else "$API/movie/$tmdbId"
        val json     = app.get(endpoint).parsedSafe<TmdbDetail>()
            ?: throw ErrorLoadingException("Could not load details")

        val title    = json.title ?: json.name ?: "Unknown"
        val poster   = json.poster_path?.let { "$IMG_BASE$it" }
        val backdrop = json.backdrop_path?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val year     = (json.release_date ?: json.first_air_date)?.take(4)?.toIntOrNull()
        val rating   = json.vote_average?.times(10)?.toInt()
        val tags     = json.genres?.mapNotNull { it.name }

        return if (isTV) {
            val episodes = mutableListOf<Episode>()

            json.seasons?.forEach { season ->
                val seasonNum = season.season_number ?: return@forEach
                if (seasonNum == 0) return@forEach

                val sJson = app.get("$API/tv/$tmdbId/season/$seasonNum")
                    .parsedSafe<TmdbSeason>()

                sJson?.episodes?.forEach { ep ->
                    val epNum = ep.episode_number ?: return@forEach
                    val streamUrl = "https://vidsrc.to/embed/tv/$tmdbId/$seasonNum/$epNum"
                    episodes.add(
                        Episode(
                            data        = streamUrl,
                            name        = ep.name ?: "Episode $epNum",
                            season      = seasonNum,
                            episode     = epNum,
                            posterUrl   = ep.still_path?.let { "$IMG_BASE$it" },
                            description = ep.overview
                        )
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdrop
                this.plot                = json.overview
                this.year                = year
                this.rating              = rating
                this.tags                = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "https://vidsrc.to/embed/movie/$tmdbId") {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdrop
                this.plot                = json.overview
                this.year                = year
                this.rating              = rating
                this.tags                = tags
            }
        }
    }

    // ── Extract video links ───────────────────────────────────────────────────
    override suspend fun loadLinks(
        data             : String,
        isCasting        : Boolean,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(data, data, subtitleCallback, callback)
        return true
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun TmdbResult.toSearchResult(isTV: Boolean): SearchResponse? {
        val title     = this.title ?: this.name ?: return null
        val id        = this.id    ?: return null
        val poster    = this.poster_path?.let { "$IMG_BASE$it" }
        val type      = if (isTV) "tv" else "movie"
        val detailUrl = "$API/$type/$id"

        return if (isTV) {
            newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, detailUrl, TvType.Movie) { this.posterUrl = poster }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Data classes  —  match the TMDB JSON structure exactly
// ─────────────────────────────────────────────────────────────────────────────

data class TmdbPageResponse(
    val page          : Int?             = null,
    val results       : List<TmdbResult>? = null,
    val total_pages   : Int?             = null,
    val total_results : Int?             = null
)

data class TmdbResult(
    val id             : Int?    = null,
    val title          : String? = null,
    val name           : String? = null,
    val poster_path    : String? = null,
    val backdrop_path  : String? = null,
    val overview       : String? = null,
    val release_date   : String? = null,
    val first_air_date : String? = null,
    val vote_average   : Double? = null,
    val media_type     : String? = null
)

data class TmdbDetail(
    val id             : Int?                  = null,
    val title          : String?               = null,
    val name           : String?               = null,
    val poster_path    : String?               = null,
    val backdrop_path  : String?               = null,
    val overview       : String?               = null,
    val release_date   : String?               = null,
    val first_air_date : String?               = null,
    val vote_average   : Double?               = null,
    val genres         : List<TmdbGenre>?      = null,
    val seasons        : List<TmdbSeasonInfo>? = null
)

data class TmdbGenre(val id: Int? = null, val name: String? = null)

data class TmdbSeasonInfo(
    val id            : Int?    = null,
    val season_number : Int?    = null,
    val name          : String? = null
)

data class TmdbSeason(
    val episodes : List<TmdbEpisode>? = null
)

data class TmdbEpisode(
    val id             : Int?    = null,
    val name           : String? = null,
    val overview       : String? = null,
    val episode_number : Int?    = null,
    val season_number  : Int?    = null,
    val still_path     : String? = null
)
