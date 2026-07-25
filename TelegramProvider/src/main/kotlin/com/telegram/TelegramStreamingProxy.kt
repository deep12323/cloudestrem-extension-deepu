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
    private const val CHUNK_SIZE = 1024 * 1024       // 1 MB served per ExoPlayer request (TDLib max limit)
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
    @Volatile private var authToken: String = ""

    fun start() {
        if (serverSocket != null) return
        port = findFreePort()
        serverSocket = ServerSocket(port)
        running = true
        authToken = java.util.UUID.randomUUID().toString()
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
        lastStreamedFileId?.let {
            if (!isCacheEnabled()) {
                scope.launch { deleteFile(it) }
            }
        }
        lastStreamedFileId = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.d(TAG, "Streaming proxy stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        val clientJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        var watchdogJob: kotlinx.coroutines.Job? = null

        try {
            socket.soTimeout = 30000
            val inputStream = socket.getInputStream()
            val reader = inputStream.bufferedReader()
            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1] // /file/{fileId} or /thumbnail/{fileId}

            val queryStrAll = path.substringAfter("?", "")
            val token = queryStrAll.split("&").find { it.startsWith("token=") }?.substringAfter("=")
            if (authToken.isNotEmpty() && token != null && token != authToken) {
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray())
                output.close()
                socket.close()
                return
            }

            var fileId: Int? = null
            var isThumbnail = false
            var urlSize = 0L
            var fileName: String? = null
            var mergedFileIds: List<Int>? = null
            var mergedSizes: List<Long>? = null
            var zipInnerName: String? = null
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
                                    is TdApi.MessageAudio -> fileId = content.audio.albumCoverThumbnail?.file?.id
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
                isThumbnail = true
            } else if (path.startsWith("/merged/")) {
                val segment = path.substringAfter("/merged/").substringBefore("?")
                val slashParts = segment.split("/", limit = 2)
                mergedFileIds = slashParts[0].split(",").mapNotNull { it.toIntOrNull() }
                if (slashParts.size > 1) {
                    fileName = java.net.URLDecoder.decode(slashParts[1], "UTF-8")
                }
                val queryStr = path.substringAfter("?", "")
                mergedSizes = queryStr.split("&").find { it.startsWith("sizes=") }
                    ?.substringAfter("=")?.split(",")?.mapNotNull { it.toLongOrNull() }
                urlSize = mergedSizes?.sum() ?: 0L
                // Set fileId to first part for compatibility
                fileId = mergedFileIds?.firstOrNull()
            } else if (path.startsWith("/zip/")) {
                val segment = path.substringAfter("/zip/").substringBefore("?")
                val slashParts = segment.split("/", limit = 2)
                fileId = slashParts[0].toIntOrNull()
                zipInnerName = if (slashParts.size > 1) java.net.URLDecoder.decode(slashParts[1], "UTF-8") else null
                val queryStr = path.substringAfter("?", "")
                urlSize = queryStr.split("&").find { it.startsWith("size=") }
                    ?.substringAfter("=")?.toLongOrNull() ?: 0L
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

            // Start watchdog AFTER headers are fully parsed to avoid InputStream race.
            // ExoPlayer sends no more data after headers, so read() returning -1 means disconnect.
            watchdogJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    while (clientJob?.isActive == true) {
                        try {
                            val res = reader.read()
                            if (res == -1) break
                        } catch (e: java.net.SocketTimeoutException) {
                            continue
                        } catch (e: Exception) {
                            break
                        }
                    }
                } finally {
                    clientJob?.cancel()
                }
            }

            // Stream file, merged parts, zip entry, or serve thumbnail
            if (isThumbnail) {
                serveThumbnail(fileId, output)
            } else if (mergedFileIds != null && mergedSizes != null && mergedFileIds!!.size == mergedSizes!!.size) {
                streamMergedFile(mergedFileIds!!, mergedSizes!!, fileName, rangeHeader, output)
            } else if (zipInnerName != null) {
                streamZipEntry(fileId, zipInnerName!!, rangeHeader, output, urlSize)
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
                        
                        // Immediately stop background downloading to save data
                        scope.launch {
                            runCatching {
                                TelegramClient.sendRequest(TdApi.CancelDownloadFile().also { req ->
                                    req.fileId = fileId
                                    req.onlyIfPending = false
                                })
                            }
                        }
                        
                        scope.launch {
                            delay(30_000)
                            if (!isCacheEnabled() && (activeStreams[fileId] ?: 0) <= 0) {
                                deleteFile(fileId)
                            }
                        }
                    }
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            Log.d(TAG, "Client stream cancelled")
        } catch (e: IOException) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}", e)
        } finally {
            watchdogJob?.cancel()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun streamFile(fileId: Int, fileName: String?, rangeHeader: String?, output: java.io.OutputStream, urlSize: Long) {
        val prev = lastStreamedFileId
        if (prev != null && prev != fileId && (activeStreams[prev] ?: 0) <= 0) {
            if (!isCacheEnabled()) {
                scope.launch { deleteFile(prev) }
            }
        }
        lastStreamedFileId = fileId

        // Force cancel any active download for this file to ensure TDLib instantly respects our new offset priority
        runCatching {
            TelegramClient.sendRequest(TdApi.CancelDownloadFile(fileId, false))
        }

        val (rangeStart, rangeEnd) = parseRange(rangeHeader)

        // Get file info
        val fileInfo = getFileInfo(fileId)
        val exactSize = fileInfo?.second?.takeIf { it > 0 } ?: urlSize.takeIf { it > 0 }
        val totalSize = exactSize ?: fileInfo?.third?.takeIf { it > 0 } ?: 0L
        val localPath = fileInfo?.first
        
        Log.d(TAG, "Streaming fileId=$fileId totalSize=$totalSize range=$rangeHeader prefetchMb=$prefetchSizeMb")

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
            
        val mimeType = getMimeType(ext)

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

        var activeDownloadEnd = -1L

        var offset = start
        while (offset <= end && running) {
            val chunkSize = minOf(CHUNK_SIZE.toLong(), end - offset + 1).toInt()

            if (offset >= activeDownloadEnd) {
                // Cancel previous download range before starting a new one.
                // Without this, TDLib accumulates successive DownloadFile ranges
                // and downloads far beyond the configured buffer size.
                runCatching {
                    TelegramClient.sendRequest(TdApi.CancelDownloadFile(fileId, false))
                }

                val tdlibPrefetch = when {
                    prefetchSizeMb == -1L -> 0L // 0 in TDLib means unlimited
                    prefetchSizeMb <= 0L -> chunkSize.toLong()
                    else -> maxOf(chunkSize.toLong(), prefetchSizeMb * 1024L * 1024L)
                }
                val alignedOffset = offset - (offset % (1024 * 1024))
                runCatching {
                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                        req.fileId = fileId
                        req.priority = DOWNLOAD_PRIORITY
                        req.offset = alignedOffset
                        req.limit = tdlibPrefetch
                        req.synchronous = false
                    })
                }
                activeDownloadEnd = if (tdlibPrefetch == 0L) totalSize else alignedOffset + tdlibPrefetch
            }

            val bytes = downloadChunk(fileId, offset, chunkSize)
            if (bytes == null || bytes.isEmpty()) break
            output.write(bytes)
            output.flush()
            offset += bytes.size
        }
    }

    private suspend fun streamMergedFile(
        fileIds: List<Int>,
        sizes: List<Long>,
        fileName: String?,
        rangeHeader: String?,
        output: java.io.OutputStream
    ) {
        val totalSize = sizes.sum()
        if (totalSize <= 0L || fileIds.isEmpty()) {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }

        val (rangeStart, rangeEnd) = parseRange(rangeHeader)
        val start: Long
        val end: Long

        if (rangeStart == null && rangeEnd != null) {
            start = maxOf(0L, totalSize - rangeEnd)
            end = totalSize - 1L
        } else {
            start = rangeStart ?: 0L
            end = rangeEnd ?: (totalSize - 1L)
        }
        val length = end - start + 1

        val ext = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() } ?: ""
        val mimeType = getMimeType(ext)

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

        // Build cumulative offset table: partStartOffsets[i] = sum of sizes[0..i-1]
        val partStartOffsets = LongArray(sizes.size)
        for (i in 1 until sizes.size) {
            partStartOffsets[i] = partStartOffsets[i - 1] + sizes[i - 1]
        }

        var offset = start
        while (offset <= end && running) {
            // Find which part this offset belongs to
            var partIndex = partStartOffsets.indexOfLast { it <= offset }
            if (partIndex < 0) partIndex = 0

            val partFileId = fileIds[partIndex]
            val partOffset = offset - partStartOffsets[partIndex]
            val partRemaining = sizes[partIndex] - partOffset
            val globalRemaining = end - offset + 1
            val chunkSize = minOf(CHUNK_SIZE.toLong(), partRemaining, globalRemaining).toInt()

            // Ensure download is active for this part
            val prefetchBytes = when {
                prefetchSizeMb == -1L -> 0L
                prefetchSizeMb <= 0L -> chunkSize.toLong()
                else -> maxOf(chunkSize.toLong(), prefetchSizeMb * 1024L * 1024L)
            }
            val alignedPartOffset = partOffset - (partOffset % (1024 * 1024))
            runCatching {
                TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                    req.fileId = partFileId
                    req.priority = DOWNLOAD_PRIORITY
                    req.offset = alignedPartOffset
                    req.limit = prefetchBytes
                    req.synchronous = false
                })
            }

            val bytes = downloadChunk(partFileId, partOffset, chunkSize)
            if (bytes == null || bytes.isEmpty()) break
            output.write(bytes)
            output.flush()
            offset += bytes.size
        }
    }

    private suspend fun streamZipEntry(
        fileId: Int,
        innerFileName: String,
        rangeHeader: String?,
        output: java.io.OutputStream,
        zipSize: Long
    ) {
        val totalZipSize = if (zipSize > 0L) zipSize else {
            val info = getFileInfo(fileId)
            info?.second?.takeIf { it > 0 } ?: info?.third?.takeIf { it > 0 } ?: 0L
        }

        if (totalZipSize <= 0L) {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }

        // Ensure download is started for the ZIP file
        runCatching {
            TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                req.fileId = fileId
                req.priority = DOWNLOAD_PRIORITY
                req.offset = 0
                req.limit = 0 // download entire file
                req.synchronous = false
            })
        }

        // Step 1: Read EOCD (End of Central Directory) - last 22 bytes minimum
        // EOCD signature: 0x06054b50
        val eocdSearchSize = minOf(65557L, totalZipSize).toInt() // Max EOCD size with comment
        val eocdOffset = totalZipSize - eocdSearchSize
        val eocdData = downloadChunk(fileId, eocdOffset, eocdSearchSize)
        if (eocdData == null || eocdData.size < 22) {
            output.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\n\r\nFailed to read ZIP EOCD".toByteArray())
            return
        }

        // Find EOCD signature (search backwards)
        var eocdPos = -1
        for (i in eocdData.size - 22 downTo 0) {
            if (eocdData[i] == 0x50.toByte() &&
                eocdData[i + 1] == 0x4B.toByte() &&
                eocdData[i + 2] == 0x05.toByte() &&
                eocdData[i + 3] == 0x06.toByte()
            ) {
                eocdPos = i
                break
            }
        }

        if (eocdPos < 0) {
            output.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\n\r\nNo EOCD signature found - not a valid ZIP".toByteArray())
            return
        }

        // Parse EOCD
        fun readUInt16(data: ByteArray, off: Int): Int =
            (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
        fun readUInt32(data: ByteArray, off: Int): Long =
            (data[off].toInt() and 0xFF).toLong() or
            ((data[off + 1].toInt() and 0xFF).toLong() shl 8) or
            ((data[off + 2].toInt() and 0xFF).toLong() shl 16) or
            ((data[off + 3].toInt() and 0xFF).toLong() shl 24)

        val cdSize = readUInt32(eocdData, eocdPos + 12)
        val cdOffset = readUInt32(eocdData, eocdPos + 16)

        // Step 2: Read the Central Directory
        val cdData = downloadChunk(fileId, cdOffset, cdSize.toInt())
        if (cdData == null || cdData.isEmpty()) {
            output.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\n\r\nFailed to read Central Directory".toByteArray())
            return
        }

        // Step 3: Parse CD entries to find the target file
        // Central Directory file header signature: 0x02014b50
        data class ZipEntry(
            val name: String,
            val compressionMethod: Int,
            val compressedSize: Long,
            val uncompressedSize: Long,
            val localHeaderOffset: Long
        )

        val entries = mutableListOf<ZipEntry>()
        var pos = 0
        while (pos + 46 <= cdData.size) {
            if (cdData[pos] != 0x50.toByte() || cdData[pos + 1] != 0x4B.toByte() ||
                cdData[pos + 2] != 0x01.toByte() || cdData[pos + 3] != 0x02.toByte()
            ) break

            val compressionMethod = readUInt16(cdData, pos + 10)
            val compressedSize = readUInt32(cdData, pos + 20)
            val uncompressedSize = readUInt32(cdData, pos + 24)
            val nameLength = readUInt16(cdData, pos + 28)
            val extraLength = readUInt16(cdData, pos + 30)
            val commentLength = readUInt16(cdData, pos + 32)
            val localHeaderOffset = readUInt32(cdData, pos + 42)

            val nameBytes = cdData.copyOfRange(pos + 46, pos + 46 + nameLength)
            val name = String(nameBytes, Charsets.UTF_8)

            entries.add(ZipEntry(name, compressionMethod, compressedSize, uncompressedSize, localHeaderOffset))
            pos += 46 + nameLength + extraLength + commentLength
        }

        // Find the target entry (case-insensitive match, also try matching just the filename part)
        val target = entries.find { it.name.equals(innerFileName, ignoreCase = true) }
            ?: entries.find { it.name.substringAfterLast('/').equals(innerFileName, ignoreCase = true) }
            ?: entries.find { 
                val entryExt = it.name.substringAfterLast('.', "").lowercase()
                entryExt in listOf("mkv", "mp4", "avi", "mov", "webm", "flv", "ts", "m2ts",
                    "mp3", "flac", "aac", "ogg", "opus", "wav", "m4a")
            }

        if (target == null) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nFile '$innerFileName' not found in ZIP archive".toByteArray())
            return
        }

        if (target.compressionMethod != 0) {
            output.write("HTTP/1.1 422 Unprocessable Entity\r\nContent-Type: text/plain\r\n\r\nFile '${target.name}' is compressed (method ${target.compressionMethod}). Only STORED (uncompressed) files can be streamed.".toByteArray())
            return
        }

        // Step 4: Read the Local File Header to find the actual data offset
        // Local file header: 30 bytes fixed + variable name + extra
        val localHeader = downloadChunk(fileId, target.localHeaderOffset, 30)
        if (localHeader == null || localHeader.size < 30) {
            output.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\n\r\nFailed to read local file header".toByteArray())
            return
        }
        val localNameLen = readUInt16(localHeader, 26)
        val localExtraLen = readUInt16(localHeader, 28)
        val dataOffset = target.localHeaderOffset + 30 + localNameLen + localExtraLen

        val innerFileSize = target.uncompressedSize // Same as compressedSize for STORED

        Log.d(TAG, "ZIP streaming: entry='${target.name}' size=$innerFileSize dataOffset=$dataOffset compressionMethod=${target.compressionMethod}")

        // Step 5: Stream the inner file data with HTTP range support
        val (rangeStart, rangeEnd) = parseRange(rangeHeader)
        val reqStart: Long
        val reqEnd: Long

        if (rangeStart == null && rangeEnd != null) {
            reqStart = maxOf(0L, innerFileSize - rangeEnd)
            reqEnd = innerFileSize - 1L
        } else {
            reqStart = rangeStart ?: 0L
            reqEnd = rangeEnd ?: (innerFileSize - 1L)
        }
        val length = reqEnd - reqStart + 1

        val ext = target.name.substringAfterLast('.', "").lowercase()
        val mimeType = getMimeType(ext)

        val status = if (rangeHeader != null) "206 Partial Content" else "200 OK"
        val headers = StringBuilder().apply {
            append("HTTP/1.1 $status\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (rangeHeader != null) {
                append("Content-Range: bytes $reqStart-$reqEnd/$innerFileSize\r\n")
            }
            append("Content-Type: $mimeType\r\n")
            append("Connection: close\r\n\r\n")
        }.toString()

        output.write(headers.toByteArray())

        // Stream the data — offset into the actual ZIP file is dataOffset + reqStart
        var offset = reqStart
        var activeDownloadEnd = -1L
        while (offset <= reqEnd && running) {
            val chunkSize = minOf(CHUNK_SIZE.toLong(), reqEnd - offset + 1).toInt()
            val zipOffset = dataOffset + offset

            if (zipOffset >= activeDownloadEnd) {
                runCatching {
                    TelegramClient.sendRequest(TdApi.CancelDownloadFile(fileId, false))
                }
                val tdlibPrefetch = when {
                    prefetchSizeMb == -1L -> 0L
                    prefetchSizeMb <= 0L -> chunkSize.toLong()
                    else -> maxOf(chunkSize.toLong(), prefetchSizeMb * 1024L * 1024L)
                }
                val alignedOffset = zipOffset - (zipOffset % (1024 * 1024))
                runCatching {
                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                        req.fileId = fileId
                        req.priority = DOWNLOAD_PRIORITY
                        req.offset = alignedOffset
                        req.limit = tdlibPrefetch
                        req.synchronous = false
                    })
                }
                activeDownloadEnd = if (tdlibPrefetch == 0L) totalZipSize else alignedOffset + tdlibPrefetch
            }

            val bytes = downloadChunk(fileId, zipOffset, chunkSize)
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
        return "http://127.0.0.1:$port/file/$fileId/$encodedName?size=$expectedSize&token=$authToken"
    }

    fun getThumbnailUrl(chatId: Long, messageId: Long): String {
        return "http://127.0.0.1:$port/thumbnail/$chatId/$messageId?token=$authToken"
    }

    fun getMergedUrl(fileIds: List<Int>, fileName: String, sizes: List<Long>): String {
        val ids = fileIds.joinToString(",")
        val szs = sizes.joinToString(",")
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:$port/merged/$ids/$encodedName?sizes=$szs&token=$authToken"
    }

    fun getZipStreamUrl(fileId: Int, innerFileName: String, zipSize: Long): String {
        val encodedInner = java.net.URLEncoder.encode(innerFileName, "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:$port/zip/$fileId/$encodedInner?size=$zipSize&token=$authToken"
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

        runCatching {
            TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                req.fileId = fileId
                req.priority = DOWNLOAD_PRIORITY
                req.offset = 0
                req.limit = totalSize.toLong()
                req.synchronous = false
            })
        }

        var currentOffset = 0L
        while (currentOffset < totalSize && running) {
            val remaining = (totalSize - currentOffset).toInt()
            val chunk = downloadChunk(fileId, currentOffset, remaining)
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
        offset: Long,
        limit: Int
    ): ByteArray? {
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

    private suspend fun getFileInfo(fileId: Int): Triple<String?, Long, Long>? {
        val file = try {
            TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File
        } catch (e: Exception) {
            null
        } ?: return null
        val localPath = file.local?.path?.takeIf { it.isNotBlank() }
        return Triple(localPath, file.size, file.expectedSize)
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

    private fun isCacheEnabled(): Boolean {
        return try {
            val context = TelegramRepository.getContext()
            val cacheLimit = TelegramRepository.getCacheLimitMb(context)
            cacheLimit > 1L || cacheLimit == -1L
        } catch (e: Exception) {
            false
        }
    }

    private fun getMimeType(ext: String): String = when (ext) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "ts", "m2ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "wma" -> "audio/x-ms-wma"
        "m4a" -> "audio/mp4"
        "aiff" -> "audio/aiff"
        else -> "video/mp4"
    }
}
