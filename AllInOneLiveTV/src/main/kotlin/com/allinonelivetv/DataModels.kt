package com.allinonelivetv

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class JioKeyPair(
    @JsonProperty("keyId") val keyId: String? = null,
    @JsonProperty("key") val key: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JioChannel(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("keyId") val keyId: String? = null,
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("keys") val keys: List<JioKeyPair>? = null,
    @JsonProperty("logo") val logo: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SonyChannel(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("m3u8") val m3u8: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SportsStream(
    @JsonProperty("channel_name") val channelName: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
    @JsonProperty("kid") val kid: String? = null,
    @JsonProperty("key") val key: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SportsEventInfo(
    @JsonProperty("teamA") val teamA: String? = null,
    @JsonProperty("teamB") val teamB: String? = null,
    @JsonProperty("teamAFlag") val teamAFlag: String? = null,
    @JsonProperty("teamBFlag") val teamBFlag: String? = null,
    @JsonProperty("eventName") val eventName: String? = null,
    @JsonProperty("event_logo") val eventLogo: String? = null,
    @JsonProperty("startTime") val startTime: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SportsMatch(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("Category") val category: String? = null,
    @JsonProperty("event_name") val eventName: String? = null,
    @JsonProperty("eventInfo") val eventInfo: SportsEventInfo? = null,
    @JsonProperty("streams") val streams: List<SportsStream>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SportsDataResponse(
    @JsonProperty("matches") val matches: List<SportsMatch>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FancodeStream(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("playlist_url") val playlistUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FancodeMatch(
    @JsonProperty("match_id") val matchId: Any? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("match_name") val matchName: String? = null,
    @JsonProperty("event_name") val eventName: String? = null,
    @JsonProperty("event_category") val eventCategory: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("startTime") val startTime: String? = null,
    @JsonProperty("streams") val streams: List<FancodeStream>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FancodeResponse(
    @JsonProperty("matches") val matches: List<FancodeMatch>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChannelPayload(
    val source: String, // "jio", "sony", "sports", "fancode"
    val id: String,
    val name: String,
    val logo: String? = null,
    val streamUrl: String? = null,
    val keyId: String? = null,
    val key: String? = null,
    val category: String? = null,
    val streamsJson: String? = null
)
