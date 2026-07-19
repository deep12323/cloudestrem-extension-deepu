package com.googledrive

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class GoogleDriveProvider : MainAPI() {
    override var mainUrl = "https://drive.google.com"
    override var name = "Google Drive"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()

    override val mainPage = mainPageOf(
        "root" to "My Drive Folders",
        "starred" to "Starred",
        "shared" to "Shared with me"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val context = try { GoogleDriveRepository.getContext() } catch(e: Exception) { return null }
        
        val itemsData = when (request.data) {
            "root" -> GoogleDriveRepository.listRootFolders(context)
            "starred" -> GoogleDriveRepository.listStarredItems(context)
            "shared" -> GoogleDriveRepository.listSharedItems(context)
            else -> emptyList()
        }
        
        if (itemsData.isEmpty()) return null

        val items = coroutineScope {
            itemsData.map { file ->
                async {
                    if (file.mimeType == "application/vnd.google-apps.folder") {
                        val thumb = file.thumbnailLink ?: GoogleDriveRepository.getFirstVideoThumbnail(context, file.id)
                        newTvSeriesSearchResponse(file.name, file.id, TvType.TvSeries) {
                            this.posterUrl = thumb ?: "https://raw.githubusercontent.com/deepu2135/cloudestrem-extension-deepu/master/google_drive_icon.png"
                        }
                    } else {
                        newMovieSearchResponse(file.name, file.id, TvType.Movie) {
                            this.posterUrl = file.thumbnailLink ?: "https://raw.githubusercontent.com/deepu2135/cloudestrem-extension-deepu/master/google_drive_icon.png"
                        }
                    }
                }
            }.awaitAll()
        }
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val context = try { GoogleDriveRepository.getContext() } catch(e: Exception) { return null }
        val token = GoogleDriveRepository.getValidAccessToken(context) ?: return null
        
        val searchUrl = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode("name contains '$query' and mimeType != 'application/vnd.google-apps.folder'", "UTF-8")}&fields=files(id,name,mimeType,webContentLink,thumbnailLink)&pageSize=50"
        try {
            val response = app.get(searchUrl, headers = mapOf("Authorization" to "Bearer $token"))
            if (response.isSuccessful) {
                val data: DriveFileList = mapper.readValue(response.text)
                return data.files.map { file ->
                    newMovieSearchResponse(file.name, file.id, TvType.Movie) {
                        this.posterUrl = file.thumbnailLink
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val context = try { GoogleDriveRepository.getContext() } catch(e: Exception) { return null }
        val token = GoogleDriveRepository.getValidAccessToken(context) ?: return null
        
        // Cloudstream automatically prepends mainUrl to URLs. We just need the raw ID.
        val actualId = url.substringAfterLast("/").trim()
        val fileUrl = "https://www.googleapis.com/drive/v3/files/$actualId?fields=id,name,mimeType,webContentLink,thumbnailLink&supportsAllDrives=true"
        try {
            val response = app.get(fileUrl, headers = mapOf("Authorization" to "Bearer $token"))
            if (response.isSuccessful) {
                val file: DriveFile = mapper.readValue(response.text)
                if (file.mimeType == "application/vnd.google-apps.folder") {
                    val children = GoogleDriveRepository.listAllFilesRecursively(context, actualId)
                    val episodes = children.mapIndexed { index, child ->
                        newEpisode(child.id) {
                            this.name = child.name
                            this.posterUrl = child.thumbnailLink ?: "https://raw.githubusercontent.com/deepu2135/cloudestrem-extension-deepu/master/google_drive_icon.png"
                            this.episode = index + 1
                        }
                    }
                    val firstThumb = children.firstOrNull { it.thumbnailLink != null }?.thumbnailLink
                    return newTvSeriesLoadResponse(file.name, url, TvType.TvSeries, episodes) {
                        this.posterUrl = file.thumbnailLink ?: firstThumb ?: "https://raw.githubusercontent.com/deepu2135/cloudestrem-extension-deepu/master/google_drive_icon.png"
                    }
                } else {
                    return newMovieLoadResponse(file.name, url, TvType.Movie, actualId) {
                        this.posterUrl = file.thumbnailLink ?: "https://raw.githubusercontent.com/deepu2135/cloudestrem-extension-deepu/master/google_drive_icon.png"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val context = try { GoogleDriveRepository.getContext() } catch(e: Exception) { return false }
        val token = GoogleDriveRepository.getValidAccessToken(context) ?: return false

        val actualId = data.substringAfterLast("/").trim()
        val streamUrl = "https://www.googleapis.com/drive/v3/files/$actualId?alt=media&supportsAllDrives=true"
        
        callback(
            newExtractorLink(
                source = this.name,
                name = "Google Drive",
                url = streamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Authorization" to "Bearer $token")
            }
        )
        return true
    }
}
