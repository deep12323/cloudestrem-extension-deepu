package com.moviesdrive

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

open class MoviesDriveHubCloud : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.pw"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolveHubCloud(url, null, null, null, callback)
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

        fun parseSizeToBytes(sizeStr: String?, fallbackQuality: Int = 0): Long {
            if (!sizeStr.isNullOrBlank()) {
                val match = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|B)\b""", RegexOption.IGNORE_CASE).find(sizeStr)
                if (match != null) {
                    val num = match.groupValues[1].toDoubleOrNull() ?: 0.0
                    val unit = match.groupValues[2].uppercase()
                    val bytes = when (unit) {
                        "TB", "TIB" -> (num * 1024L * 1024L * 1024L * 1024L).toLong()
                        "GB", "GIB" -> (num * 1024L * 1024L * 1024L).toLong()
                        "MB", "MIB" -> (num * 1024L * 1024L).toLong()
                        "KB", "KIB" -> (num * 1024L).toLong()
                        else -> num.toLong()
                    }
                    if (bytes > 0L) return bytes
                }
            }
            return when (fallbackQuality) {
                Qualities.P2160.value -> 8_000_000_000L
                Qualities.P1440.value -> 4_000_000_000L
                Qualities.P1080.value -> 2_500_000_000L
                Qualities.P720.value -> 1_000_000_000L
                Qualities.P480.value -> 450_000_000L
                Qualities.P360.value -> 250_000_000L
                else -> 0L
            }
        }

        suspend fun resolveHubCloud(
            hubUrl: String,
            linkName: String?,
            qualityStr: String?,
            sizeStr: String?,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            return try {
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )

                // If hubUrl is already a direct gateway link, use it directly
                val nextUrl = if (hubUrl.contains("hubcloud.php")) {
                    hubUrl
                } else {
                    val resp1 = app.get(hubUrl, headers = headers, timeout = 10L)
                    val html1 = resp1.text

                    var gateway = Regex("""href=["'](https?://[^"'\s]*hubcloud\.php[^"'\s]*)["']""").find(html1)?.groupValues?.get(1)
                    if (gateway == null) {
                        gateway = Regex("""<a[^>]+id=["']download["'][^>]+href=["']([^"'\s]+)["']""").find(html1)?.groupValues?.get(1)
                    }
                    if (gateway == null) {
                        gateway = Regex("""<a[^>]+class=["'][^"']*btn-primary[^"']*["'][^>]+href=["']([^"'\s]+)["']""").find(html1)?.groupValues?.get(1)
                    }
                    if (gateway == null) return false

                    if (!gateway.startsWith("http")) {
                        if (gateway.startsWith("/")) {
                            val baseUri = java.net.URI(hubUrl)
                            "${baseUri.scheme}://${baseUri.host}$gateway"
                        } else {
                            "$hubUrl/$gateway"
                        }
                    } else {
                        gateway
                    }
                }

                val resp2 = app.get(nextUrl, headers = headers + ("Referer" to hubUrl), timeout = 10L)
                val html2 = resp2.text

                // Extract file size from HTML if not provided or to refine
                val htmlSize = Regex("""(?i)<i[^>]*id=["']size["'][^>]*>([^<]+)</i>""").find(html2)?.groupValues?.get(1)?.trim()
                    ?: Regex("""(?i)File Size\s*<i[^>]*>([^<]+)</i>""").find(html2)?.groupValues?.get(1)?.trim()
                val effectiveSize = when {
                    !sizeStr.isNullOrBlank() -> sizeStr.trim()
                    !htmlSize.isNullOrBlank() && !htmlSize.equals("NAN", ignoreCase = true) -> htmlSize.trim()
                    else -> null
                }

                val quality = getQualityInt(qualityStr ?: linkName)
                val cleanLabel = linkName?.trim()?.ifBlank { null }
                val sizeTag = if (!effectiveSize.isNullOrBlank() && !(cleanLabel?.contains(effectiveSize, ignoreCase = true) ?: false)) " [$effectiveSize]" else ""
                val resPrefix = if (!qualityStr.isNullOrBlank() && !(cleanLabel?.contains(qualityStr, ignoreCase = true) ?: false)) "$qualityStr " else ""
                val displayTag = cleanLabel ?: "$resPrefix$sizeTag".trim()

                var foundAny = false
                val seenUrls = mutableSetOf<String>()

                // 1. Direct FSL Server (Cloudflare R2 Storage)
                val fslMatches = Regex("""href=["'](https?://[^"'\s]*\.cloudflarestorage\.com[^"'\s]*)["']""").findAll(html2)
                for ((index, match) in fslMatches.withIndex()) {
                    val fslUrl = match.groupValues[1].replace("&amp;", "&")
                    if (seenUrls.add(fslUrl)) {
                        val serverTag = if (index == 0) "HubCloud FSL" else "HubCloud FSL ${index + 1}"
                        val linkTitle = if (cleanLabel != null) "$serverTag • $cleanLabel$sizeTag" else "$serverTag $displayTag"
                        callback(
                            newExtractorLink(
                                source = serverTag,
                                name = linkTitle.trim(),
                                url = fslUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                        foundAny = true
                    }
                }

                // 2. High-speed 10Gbps Server (Google UploadServer CDN via gpdl / pixel)
                val pixelHubMatches = Regex("""href=["'](https?://(?:pixel|gpdl)\.hubcloud\.[^"'\s]+)["']|href=["'](https?://[^"'\s]*hubcloud\.[^"'\s]*\?id=[^"'\s]+)["']""").findAll(html2)
                for (match in pixelHubMatches) {
                    val rawMatch = match.groupValues[1].ifBlank { match.groupValues[2] }
                    if (rawMatch.isBlank() || rawMatch.contains("/admin", ignoreCase = true)) continue
                    val pixelHubUrl = rawMatch.replace("&amp;", "&")
                    try {
                        val pixelResp = app.get(pixelHubUrl, headers = headers, timeout = 6L)
                        val finalUrl = pixelResp.okhttpResponse.request.url.toString()
                        val directGoogleUrl = if (finalUrl.contains("link=")) {
                            URLDecoder.decode(finalUrl.substringAfter("link="), "UTF-8")
                        } else {
                            finalUrl
                        }
                        if (directGoogleUrl.startsWith("http") && seenUrls.add(directGoogleUrl)) {
                            val linkTitle = if (cleanLabel != null) "HubCloud 10Gbps • $cleanLabel$sizeTag" else "HubCloud 10Gbps $displayTag"
                            callback(
                                newExtractorLink(
                                    source = "HubCloud 10Gbps",
                                    name = linkTitle.trim(),
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
                        val decoded = String(android.util.Base64.decode(hbMatch.groupValues[1], android.util.Base64.DEFAULT)).replace("&amp;", "&")
                        if (decoded.startsWith("http") && seenUrls.add(decoded)) {
                            val linkTitle = if (cleanLabel != null) "HubCloud Fast • $cleanLabel$sizeTag" else "HubCloud Fast $displayTag"
                            callback(
                                newExtractorLink(
                                    source = "HubCloud Stream",
                                    name = linkTitle.trim(),
                                    url = decoded,
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
                    if (seenUrls.add(directPxl)) {
                        val linkTitle = if (cleanLabel != null) "PixelDrain • $cleanLabel$sizeTag" else "PixelDrain $displayTag"
                        callback(
                            newExtractorLink(
                                source = "PixelDrain",
                                name = linkTitle.trim(),
                                url = directPxl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                        foundAny = true
                    }
                }

                foundAny
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
