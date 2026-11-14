package com.moviesdrive

import com.fasterxml.jackson.annotation.JsonProperty

data class MovieDownloadLink(
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("size") val size: String? = null
)

data class EpisodeDownloadLink(
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("size") val size: String? = null
)

data class CategoryItem(
    val name: String,
    val slug: String,
    var isEnabled: Boolean = true,
    val isCustom: Boolean = false
)

data class TypesenseDoc(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("permalink") val permalink: String? = null,
    @JsonProperty("post_title") val post_title: String? = null,
    @JsonProperty("post_thumbnail") val post_thumbnail: String? = null,
    @JsonProperty("category") val category: List<String>? = null,
    @JsonProperty("imdb_id") val imdb_id: String? = null
)

data class TypesenseHit(
    @JsonProperty("document") val document: TypesenseDoc? = null
)

data class TypesenseResponse(
    @JsonProperty("found") val found: Int? = null,
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("hits") val hits: List<TypesenseHit>? = null
)

data class WpRendered(
    @JsonProperty("rendered") val rendered: String? = null
)

data class WpPost(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("date") val date: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("title") val title: WpRendered? = null,
    @JsonProperty("content") val content: WpRendered? = null
)
