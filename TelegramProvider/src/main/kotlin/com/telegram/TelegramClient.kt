package com.telegram

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TelegramApiException(message: String) : Exception(message)

object TelegramClient {
    private const val TAG = "TelegramClient"
    private const val NATIVE_LIB_NAME = "libtdjni.so"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isAutoCleanerRunning = false

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Idle)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private var client: Client? = null

    private var isLibraryLoaded = false
    private var libraryLoadError: String? = null

    var isAvailable: Boolean = false
        private set

    private fun loadNativeLibrary(context: Context): Boolean {
        if (libraryLoadError != null) return false
        if (isLibraryLoaded) return true

        stepLog(context, nativeDiagnostics(context))
        stepLog(context, "trying System.loadLibrary(tdjni)")
        try {
            System.loadLibrary("tdjni")
            isLibraryLoaded = true
            isAvailable = true
            stepLog(context, "System.loadLibrary(tdjni) succeeded")
            return true
        } catch (e: Throwable) {
            stepLog(context, "System.loadLibrary failed: ${e.message}")
        }

        try {
            stepLog(context, "locating manifest.json")
            val manifestUrl = TelegramClient::class.java.classLoader?.getResource("manifest.json")?.toString()
            stepLog(context, "manifestUrl is $manifestUrl")
            if (manifestUrl == null || !manifestUrl.startsWith("jar:file:")) {
                throw Exception("Could not locate plugin file path (url: $manifestUrl)")
            }
            val cs3Path = manifestUrl.substringAfter("file:").substringBefore("!")
            val cs3File = File(cs3Path)
            stepLog(context, "cs3 file path is ${cs3File.absolutePath}, exists=${cs3File.exists()}")
            if (!cs3File.exists()) {
                throw Exception("Plugin file does not exist at $cs3Path")
            }

            stepLog(context, "opening plugin zip file")
            val destFile = ZipFile(cs3File).use { zip ->
                var foundEntry: ZipEntry? = null
                var foundAbi: String? = null
                val abis = android.os.Build.SUPPORTED_ABIS.joinToString(", ")
                stepLog(context, "supported ABIs: $abis")
                for (abi in android.os.Build.SUPPORTED_ABIS) {
                    val entryName = "lib/$abi/$NATIVE_LIB_NAME"
                    val entry = zip.getEntry(entryName)
                    if (entry != null) {
                        foundEntry = entry
                        foundAbi = abi
                        stepLog(context, "found matching ABI $abi inside zip")
                        break
                    }
                }

                val targetEntry = foundEntry ?: throw Exception("No compatible ABI found in plugin lib/ directories")
                val abi = foundAbi ?: throw Exception("Matched native library ABI was not recorded")
                stepLog(context, "target entry size=${targetEntry.size}, crc=${targetEntry.crc}")
                stepLog(context, "target entry compressedSize=${targetEntry.compressedSize}, method=${targetEntry.method}")

                val nativeDir = nativeLibraryDir(context)
                if (!nativeDir.exists() && !nativeDir.mkdirs()) {
                    throw Exception("Failed to create native library directory: ${nativeDir.absolutePath}")
                }

                val versionSuffix = if (targetEntry.crc >= 0) targetEntry.crc.toString(16) else targetEntry.size.toString()
                val classLoaderHash = TelegramClient::class.java.classLoader?.hashCode()?.toString(16) ?: "unknown"
                val targetFile = File(nativeDir, "libtdjni-$abi-$versionSuffix-$classLoaderHash.so")
                
                // Cleanup old native libraries to prevent disk space leaks on plugin reloads
                nativeDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name != targetFile.name && file.name.startsWith("libtdjni-")) {
                        try { file.delete() } catch (_: Throwable) {}
                    }
                }

                stepLog(
                    context,
                    "destFile path is ${targetFile.absolutePath}, exists=${targetFile.exists()}, length=${targetFile.length()}"
                )

                if (!targetFile.exists() || targetFile.length() != targetEntry.size || !crcMatches(targetFile, targetEntry.crc)) {
                    stepLog(context, "extracting $NATIVE_LIB_NAME to codeCacheDir...")
                    targetFile.delete()
                    targetFile.setWritable(true)
                    zip.getInputStream(targetEntry).use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    stepLog(context, "extraction completed successfully")
                } else {
                    stepLog(context, "$NATIVE_LIB_NAME size and crc match target entry, skipping extraction")
                }

                targetFile
            }

            stepLog(context, "setting readable/executable permissions on destFile")
            prepareNativeLibraryPermissions(destFile)
            stepLog(
                context,
                "permissions set: canRead=${destFile.canRead()}, canExecute=${destFile.canExecute()}, canWrite=${destFile.canWrite()}"
            )
            if (!destFile.canRead() || !destFile.canExecute() || destFile.canWrite()) {
                throw Exception("Native library permissions are invalid after chmod")
            }

            stepLog(context, "calling Client.load(${destFile.absolutePath})")
            Client.load(destFile.absolutePath)
            isLibraryLoaded = true
            isAvailable = true
            stepLog(context, "System.load succeeded!")
            return true
        } catch (e: Throwable) {
            val err = "Failed to extract and load: ${e.message}"
            stepLog(context, "ERROR: $err")
            stepLog(context, "ERROR STACK: ${Log.getStackTraceString(e)}")
            Log.e(TAG, err, e)
            libraryLoadError = err
            clearNativeLibraryCache(context)
            return false
        }
    }

    private fun nativeLibraryDir(context: Context): File =
        File(context.codeCacheDir, "telegram_tdlib_native")

    private fun crcMatches(file: File, expectedCrc: Long): Boolean {
        if (expectedCrc < 0) return true
        if (!file.exists()) return false

        val crc = CRC32()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value == expectedCrc
    }

    private fun prepareNativeLibraryPermissions(file: File) {
        file.setReadable(true, true)
        file.setExecutable(true, true)
        file.setWritable(false, false)
        file.setReadOnly()
    }

    fun clearNativeLibraryCache(context: Context) {
        try { File(context.filesDir, NATIVE_LIB_NAME).delete() } catch (_: Throwable) {}
        try { nativeLibraryDir(context).deleteRecursively() } catch (_: Throwable) {}
    }

    private fun nativeDiagnostics(context: Context): String {
        val nativeDir = nativeLibraryDir(context)
        val nativeFiles = nativeDir.listFiles()
            ?.sortedBy { it.name }
            ?.joinToString(separator = "; ") { file ->
                "${file.name}(length=${file.length()}, canRead=${file.canRead()}, canExecute=${file.canExecute()}, canWrite=${file.canWrite()})"
            }
            ?: "<none>"

        return buildString {
            append("diagnostics: ")
            append("package=${context.packageName}, ")
            append("sdk=${Build.VERSION.SDK_INT}, ")
            append("device=${Build.MANUFACTURER} ${Build.MODEL}, ")
            append("supportedAbis=${Build.SUPPORTED_ABIS.joinToString("/")}, ")
            append("filesDir=${context.filesDir.absolutePath}, ")
            append("codeCacheDir=${context.codeCacheDir.absolutePath}, ")
            append("nativeDir=${nativeDir.absolutePath}, ")
            append("nativeFiles=$nativeFiles, ")
            append("classLoader=${TelegramClient::class.java.classLoader}")
        }
    }

    fun initialize(context: Context) {
        if (client != null) return
        _authState.value = TelegramAuthState.Initializing
        clearInitLog(context)
        stepLog(context, "loading native library (v1.5MB dex)")
        val isLoaded = loadNativeLibrary(context)
        stepLog(context, "library loaded result: $isLoaded")
        if (!isLoaded) {
            _authState.value = TelegramAuthState.Error(libraryLoadError ?: "TDLib native library not available")
            return
        }

        scope.launch {
            if (client != null) return@launch
            stepLog(context, "checking library availability")
            if (!isAvailable) {
                _authState.value = TelegramAuthState.Error(libraryLoadError ?: "TDLib native library not available")
                return@launch
            }
            stepLog(context, "library loaded OK")
            _authState.value = TelegramAuthState.Initializing
            try {
                val dbDir = File(context.filesDir, "tdlib")
                val filesDir = File(context.filesDir, "tdlib_files")
                if (!dbDir.exists()) dbDir.mkdirs()
                if (!filesDir.exists()) filesDir.mkdirs()

                stepLog(context, "calling Client.create")
                client = Client.create(
                    { update -> handleUpdate(context, update) },
                    { e -> Log.e(TAG, "Update exception", e) },
                    { e -> Log.e(TAG, "Default exception", e) }
                )
                stepLog(context, "Client.create returned")
            } catch (e: Throwable) {
                Log.e(TAG, "TDLib Client.create failed", e)
                stepLog(context, "EXCEPTION: ${e.message}")
                stepLog(context, "EXCEPTION STACK: ${Log.getStackTraceString(e)}")
                _authState.value = TelegramAuthState.Error("TDLib initialization failed: ${e.message}")
            }
        }
    }

    private fun stepLog(context: Context, step: String) {
        Log.d(TAG, "STEP: $step")
        try {
            File(context.filesDir, "tdlib_init_log.txt")
                .appendText("${System.currentTimeMillis()} [${Thread.currentThread().name}] $step\n")
        } catch (_: Throwable) {}
    }

    private fun readInitLogFile(context: Context): String =
        try { File(context.filesDir, "tdlib_init_log.txt").readText() }
        catch (_: Throwable) { "" }

    fun readInitLog(context: Context, maxChars: Int = 2_000): String =
        readInitLogFile(context).takeLast(maxChars)

    fun readDetailedInitLog(context: Context): String {
        return buildDetailedInitLog(context, readInitLog(context, 16_000)).takeLast(20_000)
    }

    fun readAllDetailedInitLog(context: Context): String =
        buildDetailedInitLog(context, readInitLogFile(context))

    private fun buildDetailedInitLog(context: Context, initLog: String): String {
        return buildString {
            appendLine("Diagnostic Snapshot:")
            appendLine(nativeDiagnostics(context))
            appendLine("isLibraryLoaded=$isLibraryLoaded, isAvailable=$isAvailable, libraryLoadError=${libraryLoadError ?: "<none>"}")
            appendLine()
            appendLine("Initialization Log:")
            append(if (initLog.isBlank()) "<empty>" else initLog)
        }
    }

    fun clearInitLog(context: Context) =
        try { File(context.filesDir, "tdlib_init_log.txt").delete() } catch (_: Throwable) {}

    private fun sendTdlibParameters(context: Context) {
        val dbDir = File(context.applicationContext.filesDir, "tdlib").absolutePath
        val filesDir = File(context.applicationContext.filesDir, "tdlib_files").absolutePath
        client?.send(TdApi.SetTdlibParameters().also { p ->
            p.apiId = TelegramRepository.getApiId(context)
            p.apiHash = TelegramRepository.getApiHash(context)
            p.databaseDirectory = dbDir
            p.filesDirectory = filesDir
            p.databaseEncryptionKey = getOrGenerateDbKey(context)
            p.useFileDatabase = true
            p.useChatInfoDatabase = true
            p.useMessageDatabase = true // MUST be true for SearchChatMessages and SearchMessages to work!
            p.useSecretChats = false
            p.systemLanguageCode = "en"
            p.deviceModel = "Android Device"
            p.systemVersion = "Android"
            p.applicationVersion = "1.0"
        }, { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "SetTdlibParameters failed: ${result.code} ${result.message}")
                if (result.message.contains("key", ignoreCase = true) || result.code == 401) {
                    stepLog(context, "Database key is invalid, deleting database to recover...")
                    context.getSharedPreferences("tdlib_prefs", Context.MODE_PRIVATE).edit().remove("db_key").apply()
                    try { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("tdlib_db_key_alias") } } catch (_: Throwable) {}
                    try { File(dbDir).deleteRecursively() } catch (_: Throwable) {}
                    try { File(filesDir).deleteRecursively() } catch (_: Throwable) {}
                    reset()
                    initialize(context)
                } else {
                    _authState.value = TelegramAuthState.Error("TDLib init failed: ${result.message}")
                }
                return@send
            }
            // Automatically clean up any orphaned streaming files from previous crashed sessions
            optimizeStorage()
            // After parameters are set, configure cache size limits
            updateCacheLimit(context)
            startBackgroundVideoCleaner(context)
        })
    }
    
    fun updateCacheLimit(context: Context) {
        val cacheMb = TelegramRepository.getCacheLimitMb(context)
        val bufferMb = TelegramRepository.getBufferSizeMb(context)
        updateCacheLimit(cacheMb, bufferMb)
    }

    fun updateCacheLimit(cacheMb: Long, bufferMb: Long = TelegramStreamingProxy.prefetchSizeMb) {
        val tdlibLimit = when {
            cacheMb == -1L || bufferMb == -1L -> 0L // 0 means unlimited in TDLib
            cacheMb <= 0L && bufferMb <= 0L -> 1024L * 1024L * 1L // 1MB for No Cache (can't use 0 because 0 is unlimited)
            else -> maxOf(cacheMb, bufferMb) * 1024L * 1024L
        }
        client?.send(TdApi.SetOption("storage_max_size", TdApi.OptionValueInteger(tdlibLimit)), null)
        client?.send(TdApi.SetOption("storage_max_time_from_last_access", TdApi.OptionValueInteger(3600L)), null)
    }

    private fun startBackgroundVideoCleaner(context: Context) {
        if (isAutoCleanerRunning) return
        isAutoCleanerRunning = true
        scope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L) // check every 30 minutes
                val cacheMb = TelegramRepository.getCacheLimitMb(context)
                val bufferMb = TelegramRepository.getBufferSizeMb(context)
                if (cacheMb == -1L || bufferMb == -1L) {
                    continue
                }
                val limitMb = maxOf(cacheMb, bufferMb)
                if (limitMb > 0) {
                    optimizeVideoStorage(limitMb)
                } else if (limitMb <= 0L) {
                    optimizeVideoStorage(0L) // clear immediately if set to no cache
                }
            }
        }
    }

    private fun optimizeVideoStorage(limitMb: Long) {
        val sizeLimit = if (limitMb <= 0L) 0L else limitMb * 1024L * 1024L
        client?.send(TdApi.OptimizeStorage().also { req ->
            req.size = sizeLimit
            req.ttl = 0
            req.count = 0
            req.immunityDelay = 1800 // 30 mins cooldown to prevent deleting currently playing video chunks
            req.fileTypes = arrayOf(
                TdApi.FileTypeVideo(),
                TdApi.FileTypeVideoNote(),
                TdApi.FileTypeAudio(),
                TdApi.FileTypeVoiceNote()
            )
            req.chatIds = longArrayOf()
            req.excludeChatIds = longArrayOf()
            req.returnDeletedFileStatistics = false
            req.chatLimit = 0
        }, null)
    }

    fun optimizeStorage() {
        client?.send(TdApi.OptimizeStorage().also { req ->
            req.size = 0
            req.ttl = 0
            req.count = 0
            req.immunityDelay = 0
            req.fileTypes = arrayOf(
                TdApi.FileTypeVideo(),
                TdApi.FileTypeVideoNote(),
                TdApi.FileTypeAudio(),
                TdApi.FileTypeVoiceNote()
            )
            req.chatIds = longArrayOf()
            req.excludeChatIds = longArrayOf()
            req.returnDeletedFileStatistics = false
            req.chatLimit = 0
        }, null)
    }

    private fun handleUpdate(context: Context, obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(context, obj.authorizationState)
            is TdApi.Error -> {
                val state = _authState.value
                if (state is TelegramAuthState.Initializing ||
                    state is TelegramAuthState.WaitPhone ||
                    state is TelegramAuthState.WaitQr ||
                    state is TelegramAuthState.WaitCode ||
                    state is TelegramAuthState.WaitPassword) {
                    _authState.value = TelegramAuthState.Error(obj.message)
                }
            }
        }
    }

    private fun handleAuthState(context: Context, state: TdApi.AuthorizationState) {
        Log.d(TAG, "authState -> ${state::class.simpleName}")
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendTdlibParameters(context)
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _authState.value = TelegramAuthState.WaitPhone
            }
            is TdApi.AuthorizationStateWaitCode -> {
                val len = when (val t = state.codeInfo.type) {
                    is TdApi.AuthenticationCodeTypeTelegramMessage -> t.length
                    is TdApi.AuthenticationCodeTypeSms -> t.length
                    else -> 5
                }
                _authState.value = TelegramAuthState.WaitCode(len)
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                _authState.value = TelegramAuthState.WaitQr(state.link)
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = TelegramAuthState.WaitPassword
            }
            is TdApi.AuthorizationStateReady -> {
                scope.launch {
                    val user = sendRequest(TdApi.GetMe()) as? TdApi.User
                    
                    // Force TDLib to load chats from server into local cache
                    // This ensures raw -100 IDs can be resolved properly.
                    // Load both Main and Archive chat lists so archived channels work as catalogue.
                    for (chatList in listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())) {
                        try {
                            var loaded = false
                            var attempt = 0
                            while (!loaded && attempt < 5) {
                                try {
                                    sendRequest(TdApi.LoadChats(chatList, 100))
                                    attempt++
                                } catch (e: Exception) {
                                    loaded = true // usually 404 Not Found when all chats are loaded
                                }
                            }
                        } catch (e: Exception) {}
                    }

                    File(context.filesDir, "tdlib_session_ok").createNewFile()
                    _authState.value = TelegramAuthState.Ready(
                        firstName = user?.firstName ?: "",
                        userId = user?.id ?: 0L
                    )
                }
            }
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateClosed -> {
                _authState.value = TelegramAuthState.Idle
            }
            else -> {}
        }
    }

    fun requestQrCode() {
        client?.send(TdApi.RequestQrCodeAuthentication(LongArray(0)), null)
    }

    fun submitPhone(phone: String) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, TdApi.PhoneNumberAuthenticationSettings(false, false, false, false, false, null, emptyArray())), null)
    }

    fun submitCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code), null)
    }

    fun submitPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password), null)
    }

    suspend fun sendRequest(
        function: TdApi.Function<out TdApi.Object>,
        timeoutMs: Long = 10_000L
    ): TdApi.Object? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val c = client
            if (c == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            c.send(function) { result ->
                if (cont.isActive) {
                    if (result is TdApi.Error) cont.resumeWithException(TelegramApiException(result.message))
                    else cont.resume(result)
                }
            }
        }
    }

    fun reset() {
        client?.send(TdApi.Close(), null)
        client = null
        _authState.value = TelegramAuthState.Idle
    }

    private fun getOrGenerateDbKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("tdlib_prefs", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            val encodedKey = prefs.getString("db_key_legacy", null)
            if (encodedKey != null) {
                return Base64.decode(encodedKey, Base64.DEFAULT)
            }
            val rawDbKey = ByteArray(32)
            java.security.SecureRandom().nextBytes(rawDbKey)
            prefs.edit().putString("db_key_legacy", Base64.encodeToString(rawDbKey, Base64.DEFAULT)).apply()
            return rawDbKey
        }

        val encryptedKeyBase64 = prefs.getString("db_key", null)
        
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val alias = "tdlib_db_key_alias"
        
        if (encryptedKeyBase64 == null) {
            val keyGenerator = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, 
                "AndroidKeyStore"
            )
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(spec)
            val secretKey = keyGenerator.generateKey()

            val rawDbKey = ByteArray(32)
            java.security.SecureRandom().nextBytes(rawDbKey)

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedDbKey = cipher.doFinal(rawDbKey)
            
            val combined = iv + encryptedDbKey
            prefs.edit().putString("db_key", Base64.encodeToString(combined, Base64.DEFAULT)).apply()
            
            return rawDbKey
        } else {
            try {
                val combined = Base64.decode(encryptedKeyBase64, Base64.DEFAULT)
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                val secretKey = keyStore.getKey(alias, null) as? javax.crypto.SecretKey
                
                if (secretKey == null) {
                    throw IllegalStateException("Keystore key is null")
                }
                
                val iv = combined.copyOfRange(0, 12)
                val encryptedDbKey = combined.copyOfRange(12, combined.size)
                
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
                return cipher.doFinal(encryptedDbKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt DB key, resetting TDLib", e)
                prefs.edit().remove("db_key").apply()
                try { keyStore.deleteEntry(alias) } catch (_: Exception) {}
                try { java.io.File(context.applicationContext.filesDir, "tdlib").deleteRecursively() } catch (_: Exception) {}
                try { java.io.File(context.applicationContext.filesDir, "tdlib_files").deleteRecursively() } catch (_: Exception) {}
                return getOrGenerateDbKey(context)
            }
        }
    }
}
