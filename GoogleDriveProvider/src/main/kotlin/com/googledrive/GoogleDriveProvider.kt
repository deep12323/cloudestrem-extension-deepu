package com.googledrive

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class GoogleDriveProvider : MainAPI() {
    override var mainUrl = "https://drive.google.com"
    override var name = "Google Drive"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val context = try { GoogleDriveRepository.getContext() } catch(e: Exception) { return null }
        val rootFolders = GoogleDriveRepository.listRootFolders(context)
        if (rootFolders.isEmpty()) return null

        val items = rootFolders.map { folder ->
            newTvSeriesSearchResponse(folder.name, folder.id, TvType.TvSeries) {
                this.posterUrl = folder.thumbnailLink
            }
        }
        return newHomePageResponse(HomePageList("My Drive Folders", items), false)
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
        val fileUrl = "https://www.googleapis.com/drive/v3/files/$url?fields=id,name,mimeType,webContentLink,thumbnailLink"
        try {
            val response = app.get(fileUrl, headers = mapOf("Authorization" to "Bearer $token"))
            if (response.isSuccessful) {
                val file: DriveFile = mapper.readValue(response.text)
                if (file.mimeType == "application/vnd.google-apps.folder") {
                    val children = GoogleDriveRepository.listFilesInFolder(context, url)
                    val episodes = children.mapIndexed { index, child ->
                        newEpisode(child.id) {
                            this.name = child.name
                            this.posterUrl = child.thumbnailLink
                            this.episode = index + 1
                        }
                    }
                    return newTvSeriesLoadResponse(file.name, url, TvType.TvSeries, episodes) {
                        this.posterUrl = file.thumbnailLink
                    }
                } else {
                    return newMovieLoadResponse(file.name, url, TvType.Movie, url) {
                        this.posterUrl = file.thumbnailLink
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
        val streamUrl = "https://www.googleapis.com/drive/v3/files/$data?alt=media"
        
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
