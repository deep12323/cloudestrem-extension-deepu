package com.allinonelivetv

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder
import java.net.URLEncoder

class AllInOneLiveProvider : MainAPI() {
    override var mainUrl = "https://allinonelivetv.pages.dev"
    override var name = "Allinone Live TV"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val jioMutex = Mutex()
    private var cachedJioChannels: List<JioChannel>? = null
    private var lastJioFetchTime: Long = 0

    private val sonyMutex = Mutex()
    private var cachedSonyChannels: List<SonyChannel>? = null
    private var lastSonyFetchTime: Long = 0

    private val sportsMutex = Mutex()
    private var cachedSportsMatches: List<SportsMatch>? = null
    private var lastSportsFetchTime: Long = 0

    private val fancodeMutex = Mutex()
    private var cachedFancodeMatches: List<FancodeMatch>? = null
    private var lastFancodeFetchTime: Long = 0

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val JIO_URL = "https://giotv-6op.pages.dev/jstr4web.json"
        private const val SONY_URL = "https://allinonereborn2.online/sony/sliv3.json"
        private const val SPORTS_URL = "https://raw.githubusercontent.com/sm-monirulislam/Upcoming-and-Live-Sports-Data/main/Sports_data.json"
        private const val FANCODE_URL = "https://allinonereborn2.online/fctest/json/fancode_latest.json"

        private val HTTP_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json"
        )
    }

    override val mainPage = mainPageOf(
        "sports_live" to "🔥 Live Sports & Events",
        "sonyliv" to "📺 SonyLiv Channels",
        "jio_Sports" to "🏏 JioTV - Sports",
        "jio_Entertainment" to "🎭 JioTV - Entertainment",
        "jio_Movies" to "🎬 JioTV - Movies",
        "jio_News" to "📰 JioTV - News",
        "jio_Kids" to "👶 JioTV - Kids",
        "jio_Music" to "🎵 JioTV - Music",
        "jio_Infotainment" to "🌍 JioTV - Infotainment & Lifestyle",
        "jio_Hindi" to "🇮🇳 JioTV - Hindi",
        "jio_English" to "🌐 JioTV - English",
        "jio_Tamil" to "🌴 JioTV - Tamil",
        "jio_Telugu" to "🌺 JioTV - Telugu",
        "jio_Malayalam" to "🥥 JioTV - Malayalam",
        "jio_Bengali" to "🎨 JioTV - Bengali",
        "jio_Marathi" to "🚩 JioTV - Marathi",
        "jio_Punjabi" to "🌾 JioTV - Punjabi",
        "jio_Gujarati" to "🦁 JioTV - Gujarati",
        "jio_Kannada" to "🏛️ JioTV - Kannada",
        "fancode" to "⚡ Fancode Matches",
        "jio_All" to "📺 All JioTV Channels"
    )

    private suspend fun getJioChannels(): List<JioChannel> {
        jioMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedJioChannels != null && (now - lastJioFetchTime) < CACHE_TTL_MS) {
                return cachedJioChannels!!
            }
            return try {
                val json = app.get(JIO_URL, headers = HTTP_HEADERS).text
                val list: List<JioChannel> = mapper.readValue(json)
                cachedJioChannels = list
                lastJioFetchTime = now
                list
            } catch (e: Exception) {
                cachedJioChannels ?: emptyList()
            }
        }
    }

    private suspend fun getSonyChannels(): List<SonyChannel> {
        sonyMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedSonyChannels != null && (now - lastSonyFetchTime) < CACHE_TTL_MS) {
                return cachedSonyChannels!!
            }
            return try {
                val json = app.get(SONY_URL, headers = HTTP_HEADERS).text
                val map: Map<String, SonyChannel> = mapper.readValue(json)
                val list = map.values.toList()
                cachedSonyChannels = list
                lastSonyFetchTime = now
                list
            } catch (e: Exception) {
                cachedSonyChannels ?: emptyList()
            }
        }
    }

    private suspend fun getSportsMatches(): List<SportsMatch> {
        sportsMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedSportsMatches != null && (now - lastSportsFetchTime) < CACHE_TTL_MS) {
                return cachedSportsMatches!!
            }
            return try {
                val json = app.get(SPORTS_URL, headers = HTTP_HEADERS).text
                val res: SportsDataResponse = mapper.readValue(json)
                val list = res.matches ?: emptyList()
                cachedSportsMatches = list
                lastSportsFetchTime = now
                list
            } catch (e: Exception) {
                cachedSportsMatches ?: emptyList()
            }
        }
    }

    private suspend fun getJioCookie(): String? {
        return try {
            val json = app.get("https://allinonereborn2.online/jstrweb2/cookies.json", headers = HTTP_HEADERS).text
            val list: List<Map<String, Any>> = mapper.readValue(json)
            list.firstNotNullOfOrNull { it["cookie"] as? String }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getFancodeMatches(): List<FancodeMatch> {
        fancodeMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedFancodeMatches != null && (now - lastFancodeFetchTime) < CACHE_TTL_MS) {
                return cachedFancodeMatches!!
            }
            return try {
                val json = app.get(FANCODE_URL, headers = HTTP_HEADERS).text
                val res: FancodeResponse = mapper.readValue(json)
                val list = res.matches ?: emptyList()
                cachedFancodeMatches = list
                lastFancodeFetchTime = now
                list
            } catch (e: Exception) {
                cachedFancodeMatches ?: emptyList()
            }
        }
    }

    private fun buildPayloadUrl(payload: ChannelPayload): String {
        val json = mapper.writeValueAsString(payload)
        return "$mainUrl/watch?p=" + URLEncoder.encode(json, "UTF-8")
    }

    private fun extractPayload(urlOrData: String): ChannelPayload? {
        return try {
            val queryParam = if (urlOrData.contains("p=")) {
                urlOrData.substringAfter("p=").substringBefore("&")
            } else {
                urlOrData
            }
            val json = URLDecoder.decode(queryParam, "UTF-8")
            mapper.readValue<ChannelPayload>(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun JioChannel.toSearchResponse(): SearchResponse? {
        val channelName = name?.trim() ?: return null
        val channelId = id ?: channelName
        val keyIdVal = keyId ?: keys?.firstOrNull()?.keyId
        val keyVal = key ?: keys?.firstOrNull()?.key
        val payload = ChannelPayload(
            source = "jio",
            id = channelId,
            name = channelName,
            logo = logo,
            streamUrl = url,
            keyId = keyIdVal,
            key = keyVal,
            category = category
        )
        return newLiveSearchResponse(
            name = channelName,
            url = buildPayloadUrl(payload),
            type = TvType.Live
        ) {
            this.posterUrl = logo
        }
    }

    private fun SonyChannel.toSearchResponse(): SearchResponse? {
        val channelTitle = title?.trim() ?: id?.trim() ?: return null
        val payload = ChannelPayload(
            source = "sony",
            id = id ?: channelTitle,
            name = channelTitle,
            logo = logo,
            streamUrl = m3u8,
            category = genre ?: "SonyLiv"
        )
        return newLiveSearchResponse(
            name = channelTitle,
            url = buildPayloadUrl(payload),
            type = TvType.Live
        ) {
            this.posterUrl = logo
        }
    }

    private fun SportsMatch.toSearchResponse(): SearchResponse? {
        val info = eventInfo
        val matchTitle = when {
            !info?.eventName.isNullOrBlank() -> info?.eventName!!.trim()
            !eventName.isNullOrBlank() -> eventName!!.trim()
            !info?.teamA.isNullOrBlank() && !info?.teamB.isNullOrBlank() -> "${info.teamA} vs ${info.teamB}"
            else -> "Live Sports Match"
        }
        val poster = info?.eventLogo?.takeIf { it.isNotBlank() }
            ?: info?.teamAFlag?.takeIf { it.isNotBlank() }
            ?: info?.teamBFlag?.takeIf { it.isNotBlank() }

        val activeStreams = streams?.filter { !it.streamUrl.isNullOrBlank() } ?: emptyList()
        val firstStream = activeStreams.firstOrNull()
        val streamsJsonStr = if (activeStreams.isNotEmpty()) mapper.writeValueAsString(activeStreams) else null

        val payload = ChannelPayload(
            source = "sports",
            id = matchTitle,
            name = matchTitle,
            logo = poster,
            streamUrl = firstStream?.streamUrl,
            keyId = firstStream?.kid,
            key = firstStream?.key,
            category = category ?: "Sports",
            streamsJson = streamsJsonStr
        )

        return newLiveSearchResponse(
            name = matchTitle,
            url = buildPayloadUrl(payload),
            type = TvType.Live
        ) {
            this.posterUrl = poster
        }
    }

    private fun FancodeMatch.toSearchResponse(): SearchResponse? {
        val matchTitle = title?.trim() ?: matchName?.trim() ?: return null
        val activeStream = streams?.firstOrNull { !it.playlistUrl.isNullOrBlank() }
        val payload = ChannelPayload(
            source = "fancode",
            id = matchId?.toString() ?: matchTitle,
            name = matchTitle,
            logo = image,
            streamUrl = activeStream?.playlistUrl,
            category = eventCategory ?: "Cricket"
        )
        return newLiveSearchResponse(
            name = matchTitle,
            url = buildPayloadUrl(payload),
            type = TvType.Live
        ) {
            this.posterUrl = image
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val section = request.data
        val items = mutableListOf<SearchResponse>()

        when {
            section == "sports_live" -> {
                val matches = getSportsMatches()
                val liveMatches = matches.filter { it.status.equals("LIVE", ignoreCase = true) }
                val targetList = if (liveMatches.isNotEmpty()) liveMatches else matches
                items.addAll(targetList.mapNotNull { it.toSearchResponse() })
            }
            section == "sonyliv" -> {
                val sonys = getSonyChannels()
                items.addAll(sonys.mapNotNull { it.toSearchResponse() })
            }
            section == "fancode" -> {
                val fcs = getFancodeMatches()
                items.addAll(fcs.mapNotNull { it.toSearchResponse() })
            }
            section.startsWith("jio_") -> {
                val categoryTarget = section.removePrefix("jio_")
                val jios = getJioChannels()
                val filtered = if (categoryTarget == "All") {
                    jios
                } else {
                    jios.filter { it.category?.equals(categoryTarget, ignoreCase = true) == true }
                }
                items.addAll(filtered.mapNotNull { it.toSearchResponse() })
            }
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        val results = mutableListOf<SearchResponse>()

        // Search Sony channels
        val sonyChannels = getSonyChannels()
        for (sony in sonyChannels) {
            val titleMatches = sony.title?.lowercase()?.contains(q) == true
            val idMatches = sony.id?.lowercase()?.contains(q) == true
            val genreMatches = sony.genre?.lowercase()?.contains(q) == true
            if (titleMatches || idMatches || genreMatches) {
                sony.toSearchResponse()?.let { results.add(it) }
            }
        }

        // Search Sports matches
        val sportsMatches = getSportsMatches()
        for (sm in sportsMatches) {
            val eventMatches = sm.eventName?.lowercase()?.contains(q) == true
            val infoEventMatches = sm.eventInfo?.eventName?.lowercase()?.contains(q) == true
            val teamAMatches = sm.eventInfo?.teamA?.lowercase()?.contains(q) == true
            val teamBMatches = sm.eventInfo?.teamB?.lowercase()?.contains(q) == true
            val catMatches = sm.category?.lowercase()?.contains(q) == true
            if (eventMatches || infoEventMatches || teamAMatches || teamBMatches || catMatches) {
                sm.toSearchResponse()?.let { results.add(it) }
            }
        }

        // Search Jio channels
        val jioChannels = getJioChannels()
        for (jio in jioChannels) {
            val nameMatches = jio.name?.lowercase()?.contains(q) == true
            val catMatches = jio.category?.lowercase()?.contains(q) == true
            if (nameMatches || catMatches) {
                jio.toSearchResponse()?.let { results.add(it) }
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val payload = extractPayload(url) ?: return null
        return newLiveStreamLoadResponse(
            name = payload.name,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = payload.logo
            this.plot = "Watch ${payload.name} Live on Allinone Live TV (${payload.category ?: payload.source.uppercase()})"
            this.tags = listOfNotNull("Live", payload.category, payload.source.uppercase())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = extractPayload(data) ?: return false

        when (payload.source) {
            "sony" -> {
                val streamUrl = payload.streamUrl ?: return false
                val proxyUrl = "https://allinonereborn2.online/livtest3/stream_proxy.php?url=" + URLEncoder.encode(streamUrl, "UTF-8")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "${payload.name} (HLS)",
                        url = proxyUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://suniliv.pages.dev/"
                        this.headers = mapOf("Referer" to "https://suniliv.pages.dev/")
                    }
                )
            }
            "sports" -> {
                val streams: List<SportsStream> = if (!payload.streamsJson.isNullOrBlank()) {
                    try {
                        mapper.readValue(payload.streamsJson)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else if (!payload.streamUrl.isNullOrBlank()) {
                    listOf(SportsStream(payload.name, payload.streamUrl, payload.keyId, payload.key))
                } else {
                    emptyList()
                }

                for (stream in streams) {
                    val streamUrl = stream.streamUrl
                    if (streamUrl.isNullOrBlank()) continue
                    val streamTitle = stream.channelName ?: payload.name

                    if (!stream.kid.isNullOrBlank() && !stream.key.isNullOrBlank()) {
                        callback(
                            newDrmExtractorLink(
                                source = name,
                                name = "$streamTitle (DASH ClearKey)",
                                url = streamUrl,
                                type = ExtractorLinkType.DASH,
                                uuid = CLEARKEY_UUID
                            ) {
                                this.kid = stream.kid
                                this.key = stream.key
                            }
                        )
                    } else {
                        val type = if (streamUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$streamTitle (Live)",
                                url = streamUrl,
                                type = type
                            )
                        )
                    }
                }
            }
            "jio" -> {
                val streamUrl = payload.streamUrl ?: return false
                val jioHeaders = mapOf(
                    "Referer" to "https://www.jiotv.com/",
                    "User-Agent" to "plaYtv/7.1.5"
                )

                val activeCookie = getJioCookie()
                val baseStreamUrl = streamUrl.substringBefore("?")
                val liveUrl = if (!activeCookie.isNullOrBlank()) {
                    "$baseStreamUrl?$activeCookie"
                } else {
                    streamUrl
                }

                if (!payload.keyId.isNullOrBlank() && !payload.key.isNullOrBlank()) {
                    callback(
                        newDrmExtractorLink(
                            source = name,
                            name = "${payload.name} (Live Token DASH)",
                            url = liveUrl,
                            type = ExtractorLinkType.DASH,
                            uuid = CLEARKEY_UUID
                        ) {
                            this.kid = payload.keyId
                            this.key = payload.key
                            this.referer = "https://www.jiotv.com/"
                            this.headers = jioHeaders
                        }
                    )
                    if (liveUrl != streamUrl) {
                        callback(
                            newDrmExtractorLink(
                                source = name,
                                name = "${payload.name} (Direct DASH)",
                                url = streamUrl,
                                type = ExtractorLinkType.DASH,
                                uuid = CLEARKEY_UUID
                            ) {
                                this.kid = payload.keyId
                                this.key = payload.key
                                this.referer = "https://www.jiotv.com/"
                                this.headers = jioHeaders
                            }
                        )
                    }
                } else {
                    val type = if (streamUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "${payload.name} (Live)",
                            url = liveUrl,
                            type = type
                        ) {
                            this.referer = "https://www.jiotv.com/"
                            this.headers = jioHeaders
                        }
                    )
                }
            }
            "fancode" -> {
                if (!payload.streamUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "${payload.name} (Live)",
                            url = payload.streamUrl,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                }
            }
        }

        return true
    }
}
