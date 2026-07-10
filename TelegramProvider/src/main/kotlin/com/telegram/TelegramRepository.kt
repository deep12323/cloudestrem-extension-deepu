package com.telegram

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi
import java.io.File

data class TelegramVideoMessage(
    val messageId: Long,
    val chatId: Long,
    val fileName: String,
    val fileId: Int,
    val fileSize: Long,
    val duration: Int,
    val mimeType: String,
    val caption: String,
    val thumbnailFileId: Int? = null
)

object TelegramRepository {
    private const val TAG = "TelegramRepository"

    private var appContext: Context? = null

    val authState: StateFlow<TelegramAuthState> = TelegramClient.authState

    fun sessionMarker(context: Context) = File(context.filesDir, "tdlib_session_ok")

    fun wipeTdlibFiles(context: Context) {
        sessionMarker(context).delete()
        File(context.filesDir, "tdlib").deleteRecursively()
        File(context.filesDir, "tdlib_files").deleteRecursively()
        TelegramClient.clearNativeLibraryCache(context)
        Log.d(TAG, "TDLib database and native library wiped")
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        TelegramStreamingProxy.start()
        val hasValidSession = sessionMarker(context).exists()
        if (!hasValidSession) {
            File(context.filesDir, "tdlib").deleteRecursively()
            File(context.filesDir, "tdlib_files").deleteRecursively()
        } else {
            TelegramClient.initialize(context)
        }
    }

    fun getContext(): Context {
        return appContext ?: throw Exception("TelegramRepository is not initialized with context")
    }

    fun isAuthenticated(): Boolean {
        return TelegramClient.authState.value is TelegramAuthState.Ready
    }

    suspend fun waitUntilAuthenticated(): Boolean {
        var elapsed = 0L
        val timeoutMs = 3000L
        while (elapsed < timeoutMs) {
            val state = TelegramClient.authState.value
            if (state is TelegramAuthState.Ready) return true
            if (state is TelegramAuthState.WaitPhone || state is TelegramAuthState.WaitCode || state is TelegramAuthState.WaitPassword) return false
            kotlinx.coroutines.delay(100)
            elapsed += 100
        }
        return false
    }

    fun startAuth(context: Context) = TelegramClient.initialize(context)
    fun requestQrCode() = TelegramClient.requestQrCode()
    fun submitPhone(phone: String) = TelegramClient.submitPhone(phone)
    fun submitCode(code: String) = TelegramClient.submitCode(code)
    fun submitPassword(password: String) = TelegramClient.submitPassword(password)

    fun disconnect(context: Context) {
        TelegramClient.reset()
        wipeTdlibFiles(context)
    }

    fun getCacheSize(context: Context): Long {
        val dir = File(context.filesDir, "tdlib_files")
        return if (dir.exists()) dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    fun clearCache(context: Context) {
        File(context.filesDir, "tdlib_files").listFiles()?.forEach { it.deleteRecursively() }
    }

    suspend fun getChatId(identifier: String): Long? {
        val clean = identifier.trim()
        if (clean.isEmpty()) return null

        clean.toLongOrNull()?.let { return it }

        val username = clean.removePrefix("@")
        return try {
            val chat = TelegramClient.sendRequest(TdApi.SearchPublicChat(username)) as? TdApi.Chat
            chat?.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve username $username: ${e.message}")
            null
        }
    }

    suspend fun getChannelVideos(identifier: String, page: Int, limit: Int = 50): Pair<String, List<TelegramVideoMessage>>? {
        val chatId = getChatId(identifier) ?: return null

        var title = identifier
        try {
            val chat = TelegramClient.sendRequest(TdApi.GetChat(chatId)) as? TdApi.Chat
            if (chat != null) title = chat.title
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load title for channel $identifier: ${e.message}")
        }
        
        val results = mutableListOf<TelegramVideoMessage>()
        val seen = mutableSetOf<Pair<String, Long>>()

        val filters = listOf(
            TdApi.SearchMessagesFilterDocument(),
            TdApi.SearchMessagesFilterVideo()
        )

        val pageOffset = (page - 1) * limit

        for (filter in filters) {
            try {
                val historyResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                    req.chatId = chatId
                    req.query = ""
                    req.senderId = null
                    req.fromMessageId = 0
                    req.offset = pageOffset
                    req.limit = limit
                    req.filter = filter
                    req.topicId = null
                })
                
                val found = (historyResult as? TdApi.FoundChatMessages) ?: continue
                for (msg in found.messages) {
                    extractVideoMessage(msg, seen, results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search messages failed for channel $identifier: ${e.message}")
            }
        }
        
        results.sortByDescending { it.messageId }

        return title to results
    }

    private var cachedCustomChannels: List<String> = emptyList()

    fun getCustomChannels(context: Context): List<String> {
        val prefs = context.getSharedPreferences("telegram_plugin_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("custom_channels", "") ?: ""
        if (raw.isBlank()) {
            cachedCustomChannels = emptyList()
            return emptyList()
        }
        val list = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        cachedCustomChannels = list
        return list
    }

    fun saveCustomChannels(context: Context, channels: List<String>) {
        val prefs = context.getSharedPreferences("telegram_plugin_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_channels", channels.joinToString(",")).apply()
        cachedCustomChannels = channels
    }

    suspend fun searchVideoMessages(
        query: String,
        limit: Int = 50
    ): List<TelegramVideoMessage> {
        val results = mutableListOf<TelegramVideoMessage>()
        val seen = mutableSetOf<Pair<String, Long>>()

        val filters = listOf(
            TdApi.SearchMessagesFilterDocument(),
            TdApi.SearchMessagesFilterVideo()
        )

        if (cachedCustomChannels.isEmpty()) {
            try {
                cachedCustomChannels = getCustomChannels(getContext())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load custom channels: ${e.message}")
            }
        }

        // 1. Search specifically in custom channels (works even if not joined, if public)
        for (chan in cachedCustomChannels) {
            val chatId = getChatId(chan) ?: continue
            for (filter in filters) {
                try {
                    val historyResult = TelegramClient.sendRequest(TdApi.SearchChatMessages().also { req ->
                        req.chatId = chatId
                        req.query = query
                        req.senderId = null
                        req.fromMessageId = 0
                        req.offset = 0
                        req.limit = limit
                        req.filter = filter
                        req.topicId = null
                    })
                    val found = (historyResult as? TdApi.FoundChatMessages) ?: continue
                    for (msg in found.messages) {
                        extractVideoMessage(msg, seen, results)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SearchChatMessages error for $chan: ${e.message}")
                }
            }
        }

        // 2. Search globally across all joined chats
        for (filter in filters) {
            try {
                val result = TelegramClient.sendRequest(TdApi.SearchMessages().also { req ->
                    req.chatList = null  // null = search all chats
                    req.query = query
                    req.offset = ""
                    req.limit = limit
                    req.filter = filter
                })
                val found = (result as? TdApi.FoundMessages) ?: continue
                for (msg in found.messages) {
                    extractVideoMessage(msg, seen, results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SearchMessages error: ${e.message}")
            }
        }

        results.sortByDescending { it.messageId }
        return results
    }

    private fun extractVideoMessage(msg: TdApi.Message, seen: MutableSet<Pair<String, Long>>, results: MutableList<TelegramVideoMessage>) {
        when (val content = msg.content) {
            is TdApi.MessageDocument -> {
                val mime = content.document.mimeType ?: ""
                val filename = content.document.fileName ?: "Default_Name.mkv"
                val ext = filename.substringAfterLast('.', "").lowercase()
                val isVideo = mime.startsWith("video/") || mime.contains("matroska") || ext in listOf("mkv", "mp4", "avi", "mov", "flv", "wmv", "webm")
                if (!isVideo) return
                val key = filename to content.document.document.size
                if (seen.add(key)) {
                    results.add(TelegramVideoMessage(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        fileName = filename,
                        fileId = content.document.document.id,
                        fileSize = content.document.document.size,
                        duration = 0,
                        mimeType = mime,
                        caption = content.caption?.text ?: "",
                        thumbnailFileId = content.document.thumbnail?.file?.id
                    ))
                }
            }
            is TdApi.MessageVideo -> {
                val filename = content.video.fileName ?: "Default_Name.mp4"
                val key = filename to content.video.video.size
                if (seen.add(key)) {
                    results.add(TelegramVideoMessage(
                        messageId = msg.id,
                        chatId = msg.chatId,
                        fileName = filename,
                        fileId = content.video.video.id,
                        fileSize = content.video.video.size,
                        duration = content.video.duration,
                        mimeType = content.video.mimeType ?: "video/mp4",
                        caption = content.caption?.text ?: "",
                        thumbnailFileId = content.video.thumbnail?.file?.id
                    ))
                }
            }
        }
    }

    fun getStreamUrl(fileId: Int, fileName: String): String = TelegramStreamingProxy.getUrl(fileId, fileName)

    fun getThumbnailUrl(fileId: Int): String = TelegramStreamingProxy.getThumbnailUrl(fileId)
}
