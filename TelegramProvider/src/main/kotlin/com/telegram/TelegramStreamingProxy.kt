package com.telegram

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object TelegramStreamingProxy {
    private const val TAG = "TelegramProxy"
    private const val CHUNK_SIZE = 2 * 1024 * 1024       // 2 MB served per ExoPlayer request
    var prefetchSizeMb = 20L                             // Prefetch window sent to TDLib (dynamically configured)
    private const val DOWNLOAD_TIMEOUT_MS = 30_000L
    private const val DOWNLOAD_PRIORITY = 32              // max TDLib priority
    private const val POLL_INTERVAL_MS = 100L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var port: Int = 0
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val activeStreams = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Volatile private var lastStreamedFileId: Int? = null

    fun start() {
        if (serverSocket != null) return
        port = findFreePort()
        serverSocket = ServerSocket(port)
        running = true
        Log.d(TAG, "Streaming proxy starting on port $port")

        thread(name = "TelegramProxyListener") {
            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    scope.launch {
                        handleClient(socket)
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "Error accepting client: ${e.message}")
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        lastStreamedFileId?.let { scope.launch { deleteFile(it) } }
        lastStreamedFileId = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.d(TAG, "Streaming proxy stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val reader = socket.getInputStream().bufferedReader()
            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1] // /file/{fileId} or /thumbnail/{fileId}
            var fileId: Int? = null
            var isThumbnail = false
            var urlSize = 0L
            var fileName: String? = null
            if (path.startsWith("/file/")) {
                val segment = path.substringAfter("/file/").substringBefore("?")
                fileId = segment.substringBefore("/").toIntOrNull()
                val encodedName = segment.substringAfter("/", "").takeIf { it.isNotBlank() }
                if (encodedName != null) {
                    fileName = java.net.URLDecoder.decode(encodedName, "UTF-8")
                }
                val queryStr = path.substringAfter("?", "")
                if (queryStr.isNotBlank()) {
                    urlSize = queryStr.split("&").find { it.startsWith("size=") }?.substringAfter("=")?.toLongOrNull() ?: 0L
                }
            } else if (path.startsWith("/thumbnail/")) {
                val segment = path.substringAfter("/thumbnail/").substringBefore("?")
                val thumbParts = segment.split("/")
                if (thumbParts.size == 2) {
                    val chatId = thumbParts[0].toLongOrNull()
                    val messageId = thumbParts[1].toLongOrNull()
                    if (chatId != null && messageId != null) {
                        try {
                            val msg = TelegramClient.sendRequest(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message
                            if (msg != null) {
                                when (val content = msg.content) {
                                    is TdApi.MessageVideo -> fileId = content.video.thumbnail?.file?.id
                                    is TdApi.MessageDocument -> fileId = content.document.thumbnail?.file?.id
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
                isThumbnail = true
            }

            val output = socket.getOutputStream()
            if (fileId == null) {
                output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                output.close()
                return
            }

            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            // Stream file or serve thumbnail
            if (isThumbnail) {
                serveThumbnail(fileId, output)
            } else {
                synchronized(activeStreams) {
                    activeStreams[fileId] = (activeStreams[fileId] ?: 0) + 1
                }
                try {
                    streamFile(fileId, fileName, rangeHeader, output, urlSize)
                } finally {
                    val count = synchronized(activeStreams) {
                        val current = (activeStreams[fileId] ?: 1) - 1
                        activeStreams[fileId] = current
                        current
                    }
                    if (count <= 0) {
                        synchronized(activeStreams) { activeStreams.remove(fileId) }
                        scope.launch {
                            delay(5000)
                            if ((activeStreams[fileId] ?: 0) <= 0) {
                                deleteFile(fileId)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun streamFile(fileId: Int, fileName: String?, rangeHeader: String?, output: java.io.OutputStream, urlSize: Long) {
        val prev = lastStreamedFileId
        if (prev != null && prev != fileId) {
            scope.launch { deleteFile(prev) }
        }
        lastStreamedFileId = fileId

        val (rangeStart, rangeEnd) = parseRange(rangeHeader)

        // Get file info
        val fileInfo = getFileInfo(fileId)
        val totalSize = fileInfo?.second?.takeIf { it > 0 } ?: urlSize
        val localPath = fileInfo?.first
        
        Log.d(TAG, "Streaming fileId=$fileId totalSize=$totalSize range=$rangeHeader")

        if (totalSize <= 0L) {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }

        val start: Long
        val end: Long

        if (rangeStart == null && rangeEnd != null) {
            // Suffix byte range: e.g., bytes=-500 means the last 500 bytes
            start = maxOf(0L, totalSize - rangeEnd)
            end = totalSize - 1L
        } else {
            start = rangeStart ?: 0L
            end = rangeEnd ?: (totalSize - 1L)
        }
        val length = end - start + 1

        val ext = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
            ?: localPath?.substringAfterLast('.', "")?.lowercase() ?: ""
            
        val mimeType = when (ext) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "ts", "m2ts" -> "video/mp2t"
            else -> "video/mp4"
        }

        val status = if (rangeHeader != null) "206 Partial Content" else "200 OK"
        val headers = StringBuilder().apply {
            append("HTTP/1.1 $status\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (rangeHeader != null) {
                append("Content-Range: bytes $start-$end/$totalSize\r\n")
            }
            append("Content-Type: $mimeType\r\n")
            append("Connection: close\r\n\r\n")
        }.toString()

        output.write(headers.toByteArray())

        var offset = start
        while (offset <= end && running) {
            val chunkSize = minOf(CHUNK_SIZE.toLong(), end - offset + 1).toInt()
            val bytes = downloadChunk(fileId, localPath, offset, chunkSize)
            if (bytes == null || bytes.isEmpty()) break
            output.write(bytes)
            output.flush()
            offset += bytes.size
        }
    }

    private suspend fun deleteFile(fileId: Int) {
        runCatching {
            TelegramClient.sendRequest(TdApi.CancelDownloadFile().also { req ->
                req.fileId = fileId
                req.onlyIfPending = false
            })
        }
        runCatching {
            TelegramClient.sendRequest(TdApi.DeleteFile().also { it.fileId = fileId })
            Log.d(TAG, "Deleted cached file $fileId")
        }
    }

    fun getUrl(fileId: Int, fileName: String, expectedSize: Long = 0L): String {
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:$port/file/$fileId/$encodedName?size=$expectedSize"
    }

    fun getThumbnailUrl(chatId: Long, messageId: Long): String {
        return "http://127.0.0.1:$port/thumbnail/$chatId/$messageId"
    }

    private suspend fun serveThumbnail(fileId: Int, output: java.io.OutputStream) {
        val fileInfo = getFileInfo(fileId) ?: run {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }
        val totalSize = fileInfo.second.toInt()
        if (totalSize <= 0) {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }

        val headers = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: $totalSize\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray())

        var currentOffset = 0L
        while (currentOffset < totalSize && running) {
            val remaining = (totalSize - currentOffset).toInt()
            val chunk = downloadChunk(fileId, fileInfo.first, currentOffset, remaining)
            if (chunk == null || chunk.isEmpty()) {
                break
            }
            output.write(chunk)
            output.flush()
            currentOffset += chunk.size
        }
    }

    private suspend fun downloadChunk(
        fileId: Int,
        @Suppress("UNUSED_PARAMETER") localPath: String?,
        offset: Long,
        limit: Int
    ): ByteArray? {
        withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            try {
                val tdlibPrefetch = when {
                    prefetchSizeMb == -1L -> 0L // 0 in TDLib means unlimited
                    prefetchSizeMb <= 0L -> limit.toLong() // 0 in settings means no prefetch (only fetch the current chunk)
                    else -> maxOf(limit.toLong(), prefetchSizeMb * 1024L * 1024L)
                }
                TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                    req.fileId = fileId
                    req.priority = DOWNLOAD_PRIORITY
                    req.offset = offset
                    req.limit = tdlibPrefetch
                    req.synchronous = false
                })
            } catch (e: Exception) {
                Log.e(TAG, "DownloadFile failed at offset $offset: ${e.message}")
            }
        }

        val dataBytes = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            var attempts = 0
            while (attempts < 300 && running) {
                val data = try {
                    TelegramClient.sendRequest(
                        TdApi.ReadFilePart(fileId, offset, limit.toLong())
                    ) as? TdApi.Data
                } catch (e: Exception) {
                    null
                }
                
                if (data != null && data.data.isNotEmpty()) {
                    return@withTimeoutOrNull data.data
                }
                
                val file = try { TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File } catch (e: Exception) { null }
                if (file?.local?.isDownloadingCompleted == true) {
                    val finalData = try {
                        TelegramClient.sendRequest(
                            TdApi.ReadFilePart(fileId, offset, limit.toLong())
                        ) as? TdApi.Data
                    } catch (e: Exception) { null }
                    return@withTimeoutOrNull finalData?.data
                }
                
                delay(POLL_INTERVAL_MS)
                attempts++
            }
            null
        }
        return dataBytes
    }

    private suspend fun getFileInfo(fileId: Int): Pair<String?, Long>? {
        val file = try {
            TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File
        } catch (e: Exception) {
            null
        } ?: return null
        val totalSize = file.size.takeIf { it > 0 } ?: file.expectedSize
        val localPath = file.local?.path?.takeIf { it.isNotBlank() }
        return Pair(localPath, totalSize.toLong())
    }

    private fun parseRange(header: String?): Pair<Long?, Long?> {
        if (header == null) return Pair(null, null)
        return try {
            val range = header.removePrefix("bytes=")
            val parts = range.split("-")
            val start = parts.getOrNull(0)?.toLongOrNull()
            val end = parts.getOrNull(1)?.toLongOrNull()
            Pair(start, end)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
