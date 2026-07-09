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
    private const val PREFETCH_SIZE = 20 * 1024 * 1024L  // 20 MB prefetch window sent to TDLib
    private const val DOWNLOAD_TIMEOUT_MS = 30_000L
    private const val DOWNLOAD_PRIORITY = 32              // max TDLib priority
    private const val POLL_INTERVAL_MS = 100L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var port: Int = 0
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
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
            if (path.startsWith("/file/")) {
                fileId = path.substringAfter("/file/").substringBefore("?").toIntOrNull()
            } else if (path.startsWith("/thumbnail/")) {
                fileId = path.substringAfter("/thumbnail/").substringBefore("?").toIntOrNull()
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
                streamFile(fileId, rangeHeader, output)
            }
        } catch (e: IOException) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun streamFile(fileId: Int, rangeHeader: String?, output: java.io.OutputStream) {
        val prev = lastStreamedFileId
        if (prev != null && prev != fileId) {
            scope.launch { deleteFile(prev) }
        }
        lastStreamedFileId = fileId

        val (rangeStart, rangeEnd) = parseRange(rangeHeader)

        // Get file info
        val fileInfo = getFileInfo(fileId) ?: run {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }
        val totalSize = fileInfo.second
        val localPath = fileInfo.first
        Log.d(TAG, "Streaming fileId=$fileId totalSize=$totalSize range=$rangeHeader")

        if (totalSize <= 0L) {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }

        val start = rangeStart ?: 0L
        val end = rangeEnd ?: (totalSize - 1L)
        val length = end - start + 1

        val status = if (rangeHeader != null) "206 Partial Content" else "200 OK"
        val headers = StringBuilder().apply {
            append("HTTP/1.1 $status\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (rangeHeader != null) {
                append("Content-Range: bytes $start-$end/$totalSize\r\n")
            }
            append("Content-Type: video/mp4\r\n")
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

    fun getUrl(fileId: Int): String {
        return "http://localhost:$port/file/$fileId"
    }

    fun getThumbnailUrl(fileId: Int): String {
        return "http://localhost:$port/thumbnail/$fileId"
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

        val bytes = downloadChunk(fileId, fileInfo.first, 0L, totalSize)
        if (bytes != null && bytes.isNotEmpty()) {
            output.write(bytes)
            output.flush()
        }
    }

    private suspend fun downloadChunk(
        fileId: Int,
        @Suppress("UNUSED_PARAMETER") localPath: String?,
        offset: Long,
        limit: Int
    ): ByteArray? {
        withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                req.fileId = fileId
                req.priority = DOWNLOAD_PRIORITY
                req.offset = offset
                req.limit = PREFETCH_SIZE
                req.synchronous = false
            })
        }

        val dataBytes = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            var attempts = 0
            while (attempts < 300 && running) {
                val data = TelegramClient.sendRequest(
                    TdApi.ReadFilePart(fileId, offset, limit.toLong())
                ) as? TdApi.Data
                
                if (data != null && data.data.isNotEmpty()) {
                    return@withTimeoutOrNull data.data
                }
                
                val file = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File
                if (file?.local?.isDownloadingCompleted == true) {
                    val finalData = TelegramClient.sendRequest(
                        TdApi.ReadFilePart(fileId, offset, limit.toLong())
                    ) as? TdApi.Data
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
        val file = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File ?: return null
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
