package com.h2rxdmovies

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaItem(
    @JsonProperty("id") val id: Int,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("release_date") val release_date: String? = null,
    @JsonProperty("release_year") val release_year: Int? = null,
    @JsonProperty("rating_num") val rating_num: Double? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("downloads_count") val downloads_count: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("audios") val audios: List<String>? = null,
    @JsonProperty("sources") val sources: List<String>? = null,
    @JsonProperty("cast") val cast: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogResponse(
    @JsonProperty("success") val success: Boolean = false,
    @JsonProperty("page") val page: Int = 1,
    @JsonProperty("limit") val limit: Int = 20,
    @JsonProperty("total") val total: Int = 0,
    @JsonProperty("totalPages") val totalPages: Int = 1,
    @JsonProperty("results") val results: List<MediaItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaDetailResponse(
    @JsonProperty("success") val success: Boolean = false,
    @JsonProperty("data") val data: MediaItem? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DownloadItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_id") val media_id: Int? = null,
    @JsonProperty("group_name") val group_name: String? = null,
    @JsonProperty("file_name") val file_name: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("size_bytes") val size_bytes: Long? = null,
    @JsonProperty("download_url") val download_url: String? = null,
    @JsonProperty("hubcloud_url") val hubcloud_url: String? = null,
    @JsonProperty("item_type") val item_type: String? = null,
    @JsonProperty("season_num") val season_num: Int? = null,
    @JsonProperty("episode_num") val episode_num: Int? = null,
    @JsonProperty("resolution") val resolution: String? = null,
    @JsonProperty("codec") val codec: String? = null,
    @JsonProperty("audio_channels") val audio_channels: String? = null,
    @JsonProperty("rip_team") val rip_team: String? = null,
    @JsonProperty("is_10bit") val is_10bit: Int? = null,
    @JsonProperty("has_esubs") val has_esubs: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovieDownloadsResponse(
    @JsonProperty("success") val success: Boolean = false,
    @JsonProperty("count") val count: Int = 0,
    @JsonProperty("downloads") val downloads: List<DownloadItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonItem(
    @JsonProperty("season") val season: Int = 0,
    @JsonProperty("episodes_count") val episodes_count: Int = 0,
    @JsonProperty("packs_count") val packs_count: Int = 0,
    @JsonProperty("episodes") val episodes: List<EpisodeItem> = emptyList(),
    @JsonProperty("packs") val packs: List<DownloadItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeItem(
    @JsonProperty("episode") val episode: Int = 0,
    @JsonProperty("downloads") val downloads: List<DownloadItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeriesDownloadsResponse(
    @JsonProperty("success") val success: Boolean = false,
    @JsonProperty("series") val series: MediaItem? = null,
    @JsonProperty("seasons") val seasons: List<SeasonItem> = emptyList()
)
