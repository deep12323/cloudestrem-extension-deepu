package com.googledrive

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("expires_in") val expiresIn: Int,
    @JsonProperty("refresh_token") val refreshToken: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DriveFileList(
    val files: List<DriveFile> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val webContentLink: String?,
    val thumbnailLink: String?
)

object GoogleDriveRepository {
    private const val PREFS = "google_drive_prefs"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_CLIENT_SECRET = "client_secret"
    private const val KEY_REFRESH_TOKEN = "refresh_token"

    private var currentAccessToken: String? = null
    private var tokenExpiry: Long = 0
    private val tokenMutex = Mutex()
    val mapper = jacksonObjectMapper()

    private var pluginContext: Context? = null

    fun setContext(context: Context) {
        pluginContext = context
    }

    fun getContext(): Context = pluginContext ?: throw RuntimeException("Context not initialized")

    fun getClientId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLIENT_ID, "") ?: ""
    fun getClientSecret(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLIENT_SECRET, "") ?: ""
    fun getRefreshToken(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REFRESH_TOKEN, "") ?: ""

    fun isAuthenticated(context: Context): Boolean = getRefreshToken(context).isNotBlank()

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_REFRESH_TOKEN)
            .apply()
        currentAccessToken = null
        tokenExpiry = 0
    }

    fun saveClientIdSecret(context: Context, clientId: String, clientSecret: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CLIENT_ID, clientId)
            .putString(KEY_CLIENT_SECRET, clientSecret)
            .apply()
    }

    suspend fun exchangeAuthCodeForTokens(context: Context, code: String, redirectUri: String): Boolean {
        val clientId = getClientId(context)
        val clientSecret = getClientSecret(context)
        if (clientId.isBlank() || clientSecret.isBlank()) return false
        
        try {
            val response = app.post(
                "https://oauth2.googleapis.com/token",
                data = mapOf(
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "grant_type" to "authorization_code"
                )
            )
            if (response.isSuccessful) {
                val tokenData: TokenResponse = mapper.readValue(response.text)
                val refresh = tokenData.refreshToken
                
                if (refresh != null) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_REFRESH_TOKEN, refresh)
                        .apply()
                }
                
                currentAccessToken = tokenData.accessToken
                tokenExpiry = System.currentTimeMillis() + (tokenData.expiresIn * 1000L)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    suspend fun getValidAccessToken(context: Context): String? {
        tokenMutex.withLock {
            if (currentAccessToken != null && System.currentTimeMillis() < tokenExpiry - 60000) {
                return currentAccessToken
            }
            
            val clientId = getClientId(context)
            val clientSecret = getClientSecret(context)
            val refreshToken = getRefreshToken(context)
            
            if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) return null
            
            try {
                val response = app.post(
                    "https://oauth2.googleapis.com/token",
                    data = mapOf(
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                        "refresh_token" to refreshToken,
                        "grant_type" to "refresh_token"
                    )
                )
                if (response.isSuccessful) {
                    val tokenData: TokenResponse = mapper.readValue(response.text)
                    currentAccessToken = tokenData.accessToken
                    tokenExpiry = System.currentTimeMillis() + (tokenData.expiresIn * 1000L)
                    return currentAccessToken
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
    }

    suspend fun listRootFolders(context: Context): List<DriveFile> {
        return queryFiles(context, "'root' in parents")
    }

    suspend fun listStarredItems(context: Context): List<DriveFile> {
        return queryFiles(context, "starred = true")
    }

    suspend fun listSharedItems(context: Context): List<DriveFile> {
        return queryFiles(context, "sharedWithMe = true")
    }

    suspend fun listFilesInFolder(context: Context, folderId: String): List<DriveFile> {
        return queryFiles(context, "'$folderId' in parents and mimeType != 'application/vnd.google-apps.folder'")
    }

    suspend fun listAllFilesRecursively(context: Context, folderId: String): List<DriveFile> {
        val result = mutableListOf<DriveFile>()
        val queue = mutableListOf(folderId)
        
        while (queue.isNotEmpty()) {
            val currentFolder = queue.removeAt(0)
            val children = queryFiles(context, "'$currentFolder' in parents")
            for (child in children) {
                if (child.mimeType == "application/vnd.google-apps.folder") {
                    queue.add(child.id)
                } else {
                    result.add(child)
                }
            }
        }
        return result
    }

    suspend fun getFirstVideoThumbnail(context: Context, folderId: String): String? {
        val token = getValidAccessToken(context) ?: return null
        val query = "'$folderId' in parents and mimeType contains 'video/'"
        val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name,mimeType,thumbnailLink)&pageSize=1&supportsAllDrives=true&includeItemsFromAllDrives=true&corpora=allDrives"
        
        try {
            val response = app.get(url, headers = mapOf("Authorization" to "Bearer $token"))
            if (response.isSuccessful) {
                val data: DriveFileList = mapper.readValue(response.text)
                return data.files.firstOrNull()?.thumbnailLink
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private suspend fun queryFiles(context: Context, query: String): List<DriveFile> {
        val token = getValidAccessToken(context) ?: return emptyList()
        val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name,mimeType,webContentLink,thumbnailLink)&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true&corpora=allDrives"
        
        try {
            val response = app.get(url, headers = mapOf("Authorization" to "Bearer $token"))
            if (response.isSuccessful) {
                val data: DriveFileList = mapper.readValue(response.text)
                return data.files.filter { 
                    it.mimeType == "application/vnd.google-apps.folder" || 
                    it.mimeType.startsWith("video/") || 
                    it.mimeType.startsWith("audio/")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }
}
