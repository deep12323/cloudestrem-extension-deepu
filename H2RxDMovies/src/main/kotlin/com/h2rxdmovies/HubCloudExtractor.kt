package com.h2rxdmovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

open class HubCloud : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.pw"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolveHubCloud(url, null, callback)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun getQualityInt(resolution: String?): Int {
            if (resolution == null) return Qualities.Unknown.value
            val res = resolution.lowercase()
            return when {
                res.contains("2160") || res.contains("4k") -> Qualities.P2160.value
                res.contains("1440") -> Qualities.P1440.value
                res.contains("1080") -> Qualities.P1080.value
                res.contains("720") -> Qualities.P720.value
                res.contains("480") -> Qualities.P480.value
                res.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
        }

        suspend fun resolveHubCloud(
            hubUrl: String,
            downloadItem: DownloadItem?,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            return try {
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                val resp1 = app.get(hubUrl, headers = headers, timeout = 15L)
                val html1 = resp1.text

                // Find download / gateway URL
                var nextUrl = Regex("""href=["'](https?://[^"'\s]*hubcloud\.php[^"'\s]*)["']""").find(html1)?.groupValues?.get(1)
                if (nextUrl == null) {
                    nextUrl = Regex("""<a[^>]+id=["']download["'][^>]+href=["']([^"'\s]+)["']""").find(html1)?.groupValues?.get(1)
                }
                if (nextUrl == null) {
                    nextUrl = Regex("""<a[^>]+class=["'][^"']*btn-primary[^"']*["'][^>]+href=["']([^"'\s]+)["']""").find(html1)?.groupValues?.get(1)
                }

                if (nextUrl == null) return false

                if (!nextUrl.startsWith("http")) {
                    nextUrl = if (nextUrl.startsWith("/")) {
                        val baseUri = java.net.URI(hubUrl)
                        "${baseUri.scheme}://${baseUri.host}$nextUrl"
                    } else {
                        "$hubUrl/$nextUrl"
                    }
                }

                val resp2 = app.get(nextUrl, headers = headers + ("Referer" to hubUrl), timeout = 15L)
                val html2 = resp2.text

                val quality = getQualityInt(downloadItem?.resolution)
                val resPrefix = if (!downloadItem?.resolution.isNullOrBlank()) "${downloadItem?.resolution} " else ""
                val sizeSuffix = if (!downloadItem?.size.isNullOrBlank()) " [${downloadItem?.size}]" else ""

                var foundAny = false

                // 1. Direct FSL Server (Cloudflare R2 Storage)
                val fslMatch = Regex("""href=["'](https?://[^"'\s]*\.cloudflarestorage\.com[^"'\s]*)["']""").find(html2)
                if (fslMatch != null) {
                    val fslUrl = fslMatch.groupValues[1].replace("&amp;", "&")
                    callback(
                        newExtractorLink(
                            source = "HubCloud FSL",
                            name = "HubCloud FSL (Direct R2) $resPrefix$sizeSuffix".trim(),
                            url = fslUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    )
                    foundAny = true
                }

                // 2. High-speed 10Gbps Server (Google UploadServer CDN)
                val pixelHubMatch = Regex("""href=["'](https?://pixel\.hubcloud\.[^"'\s]+)["']""").find(html2)
                if (pixelHubMatch != null) {
                    try {
                        val pixelHubUrl = pixelHubMatch.groupValues[1].replace("&amp;", "&")
                        val pixelResp = app.get(pixelHubUrl, headers = headers, timeout = 10L)
                        val finalUrl = pixelResp.okhttpResponse.request.url.toString()
                        val directGoogleUrl = if (finalUrl.contains("link=")) {
                            URLDecoder.decode(finalUrl.substringAfter("link="), "UTF-8")
                        } else {
                            finalUrl
                        }
                        if (directGoogleUrl.startsWith("http")) {
                            callback(
                                newExtractorLink(
                                    source = "HubCloud 10Gbps",
                                    name = "HubCloud 10Gbps (Google CDN) $resPrefix$sizeSuffix".trim(),
                                    url = directGoogleUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.headers = mapOf("User-Agent" to USER_AGENT)
                                }
                            )
                            foundAny = true
                        }
                    } catch (_: Exception) {}
                }

                // 3. HBPlay direct link (Watch Online player base64)
                val hbMatch = Regex("""href=["']https?://hbplay\.pages\.dev/\?u=([^"'\s&]+)""").find(html2)
                if (hbMatch != null) {
                    try {
                        val decoded = String(android.util.Base64.decode(hbMatch.groupValues[1], android.util.Base64.DEFAULT))
                        val cleanDecoded = decoded.replace("&amp;", "&")
                        val fslClean = fslMatch?.groupValues?.get(1)?.replace("&amp;", "&")
                        if (cleanDecoded.startsWith("http") && cleanDecoded != fslClean) {
                            callback(
                                newExtractorLink(
                                    source = "HubCloud Stream",
                                    name = "HubCloud Fast $resPrefix$sizeSuffix".trim(),
                                    url = cleanDecoded,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.headers = mapOf("User-Agent" to USER_AGENT)
                                }
                            )
                            foundAny = true
                        }
                    } catch (_: Exception) {}
                }

                // 4. PixelDrain
                val pxlMatch = Regex("""href=["'](https?://pixeldrain\.[^"'\s/]+/u/([a-zA-Z0-9]+))["']""").find(html2)
                if (pxlMatch != null) {
                    val fileId = pxlMatch.groupValues[2]
                    val directPxl = "https://pixeldrain.com/api/file/$fileId"
                    callback(
                        newExtractorLink(
                            source = "PixelDrain",
                            name = "PixelDrain $resPrefix$sizeSuffix".trim(),
                            url = directPxl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    )
                    foundAny = true
                }

                foundAny
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}

class HubCloudIst : HubCloud() {
    override val name = "HubCloud.ist"
    override val mainUrl = "https://hubcloud.ist"
}
