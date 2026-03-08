package com.gramcinema

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

// ─────────────────────────────────────────────────────────────────────────────
//  GramCinemaProvider  —  CloudStream 3 plugin for bollywood.eu.org
//
//  Metadata : https://bollywood.eu.org/tmdb/3/  (TMDB proxy, no key needed)
//  Streaming: https://tga-hd.api.hashhackers.com/files/search  (Hash Hackers)
// ─────────────────────────────────────────────────────────────────────────────

class GramCinemaProvider : MainAPI() {

    override var mainUrl     = "https://bollywood.eu.org"
    override var name        = "Gram Cinema"
    override val lang        = "hi"
    override val hasMainPage = true
    override val hasSearch   = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ── API bases ─────────────────────────────────────────────────────────────
    private val TMDB_API   = "https://bollywood.eu.org/tmdb/3"
    private val STREAM_API = "https://tga-hd.api.hashhackers.com"
    private val IMG_BASE   = "https://image.tmdb.org/t/p/w500"

    // ── Home page rows ────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$TMDB_API/trending/movie/week"                      to "🔥 Trending Movies",
        "$TMDB_API/movie/popular"                            to "🎬 Popular Movies",
        "$TMDB_API/movie/top_rated"                          to "⭐ Top Rated Movies",
        "$TMDB_API/trending/tv/week"                         to "📺 Trending TV Shows",
        "$TMDB_API/tv/popular"                               to "🌟 Popular TV Shows",
        "$TMDB_API/discover/movie?with_original_language=hi" to "🇮🇳 Bollywood",
        "$TMDB_API/discover/movie?with_original_language=ta" to "🎭 Tamil Movies",
        "$TMDB_API/discover/movie?with_original_language=te" to "🎭 Telugu Movies"
    )

    // ── Home page row loader ──────────────────────────────────────────────────
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
        val movies = app.get("$TMDB_API/search/movie?query=${query.encodeUrl()}")
            .parsedSafe<TmdbPageResponse>()
            ?.results?.mapNotNull { it.toSearchResult(false) } ?: emptyList()

        val shows = app.get("$TMDB_API/search/tv?query=${query.encodeUrl()}")
            .parsedSafe<TmdbPageResponse>()
            ?.results?.mapNotNull { it.toSearchResult(true) } ?: emptyList()

        return movies + shows
    }

    // ── Detail / load page ────────────────────────────────────────────────────
    // url format: https://bollywood.eu.org/tmdb/3/movie/12345
    //          or https://bollywood.eu.org/tmdb/3/tv/12345
    override suspend fun load(url: String): LoadResponse {
        val isTV   = url.contains("/tv/")
        val tmdbId = url.trimEnd('/').substringAfterLast('/')

        val endpoint = if (isTV) "$TMDB_API/tv/$tmdbId" else "$TMDB_API/movie/$tmdbId"
        val json     = app.get(endpoint).parsedSafe<TmdbDetail>()
            ?: throw ErrorLoadingException("Could not load details")

        val title    = json.title ?: json.name ?: "Unknown"
        val poster   = json.poster_path?.let { "$IMG_BASE$it" }
        val backdrop = json.backdrop_path?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val year     = (json.release_date ?: json.first_air_date)?.take(4) ?: ""
        val rating   = json.vote_average?.times(10)?.toInt()
        val tags     = json.genres?.mapNotNull { it.name }

        return if (isTV) {
            val episodes = mutableListOf<Episode>()

            json.seasons?.forEach { season ->
                val seasonNum = season.season_number ?: return@forEach
                if (seasonNum == 0) return@forEach   // skip Specials

                val sJson = app.get("$TMDB_API/tv/$tmdbId/season/$seasonNum")
                    .parsedSafe<TmdbSeason>()

                sJson?.episodes?.forEach { ep ->
                    val epNum = ep.episode_number ?: return@forEach
                    // Encode search query into data so loadLinks can use it
                    // Format:  TITLE|YEAR|S|E
                    val data = "${title}|${year}|${seasonNum}|${epNum}"
                    episodes.add(
                        Episode(
                            data        = data,
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
                this.year                = year.toIntOrNull()
                this.rating              = rating
                this.tags                = tags
            }
        } else {
            // Movie data = "TITLE|YEAR"
            val data = "${title}|${year}"
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdrop
                this.plot                = json.overview
                this.year                = year.toIntOrNull()
                this.rating              = rating
                this.tags                = tags
            }
        }
    }

    // ── Extract video links from Hash Hackers API ─────────────────────────────
    override suspend fun loadLinks(
        data             : String,
        isCasting        : Boolean,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")

        // Build the search query
        val query = when (parts.size) {
            4 -> {
                // TV: TITLE|YEAR|season|episode
                val title   = parts[0]
                val year    = parts[1]
                val season  = parts[2].toIntOrNull() ?: 1
                val episode = parts[3].toIntOrNull() ?: 1
                val s = season.toString().padStart(2, '0')
                val e = episode.toString().padStart(2, '0')
                "$title $year S${s}E${e}"
            }
            2 -> "${parts[0]} ${parts[1]}"  // Movie: TITLE|YEAR
            else -> data
        }

        // Search Hash Hackers file API
        val searchUrl = "$STREAM_API/files/search?q=${query.encodeUrl()}&page=1"
        val result    = app.get(searchUrl).parsedSafe<HHSearchResponse>()

        result?.files?.forEach { file ->
            val fileName = file.file_name ?: return@forEach
            val fileId   = file.id        ?: return@forEach

            // Determine quality from filename
            val quality = when {
                fileName.contains("2160p", ignoreCase = true) ||
                fileName.contains("4k",    ignoreCase = true)  -> Qualities.UHD4K.value
                fileName.contains("1080p", ignoreCase = true)  -> Qualities.P1080.value
                fileName.contains("720p",  ignoreCase = true)  -> Qualities.P720.value
                fileName.contains("480p",  ignoreCase = true)  -> Qualities.P480.value
                fileName.contains("360p",  ignoreCase = true)  -> Qualities.P360.value
                else                                            -> Qualities.Unknown.value
            }

            // Format file size nicely for the label
            val sizeLabel = file.file_size?.toLongOrNull()?.let { bytes ->
                when {
                    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
                    bytes >= 1_048_576     -> "%.0f MB".format(bytes / 1_048_576.0)
                    else                   -> "$bytes B"
                }
            } ?: ""

            val streamUrl = "$STREAM_API/stream/$fileId"

            callback.invoke(
                ExtractorLink(
                    source   = this.name,
                    name     = "[$sizeLabel] $fileName",
                    url      = streamUrl,
                    referer  = mainUrl,
                    quality  = quality,
                    isM3u8   = false
                )
            )
        }

        return true
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun TmdbResult.toSearchResult(isTV: Boolean): SearchResponse? {
        val title     = this.title ?: this.name ?: return null
        val id        = this.id    ?: return null
        val poster    = this.poster_path?.let { "$IMG_BASE$it" }
        val type      = if (isTV) "tv" else "movie"
        val detailUrl = "$TMDB_API/$type/$id"
        return if (isTV) {
            newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, detailUrl, TvType.Movie) { this.posterUrl = poster }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TMDB data classes
// ─────────────────────────────────────────────────────────────────────────────

data class TmdbPageResponse(
    val page          : Int?              = null,
    val results       : List<TmdbResult>? = null,
    val total_pages   : Int?              = null,
    val total_results : Int?              = null
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

data class TmdbSeason(val episodes: List<TmdbEpisode>? = null)

data class TmdbEpisode(
    val id             : Int?    = null,
    val name           : String? = null,
    val overview       : String? = null,
    val episode_number : Int?    = null,
    val season_number  : Int?    = null,
    val still_path     : String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
//  Hash Hackers streaming API data classes
// ─────────────────────────────────────────────────────────────────────────────

data class HHSearchResponse(
    val total_files   : Int?          = null,
    val total_pages   : Int?          = null,
    val current_page  : Int?          = null,
    val files         : List<HHFile>? = null
)

data class HHFile(
    val id        : String? = null,
    val file_name : String? = null,
    val file_size : String? = null,
    val user_id   : String? = null
)
