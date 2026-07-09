package com.telegram

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TelegramApiException(message: String) : Exception(message)

object TelegramClient {
    private const val TAG = "TelegramClient"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Idle)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private var client: Client? = null

    private var isLibraryLoaded = false
    private var libraryLoadError: String? = null

    var isAvailable: Boolean = false
        private set

    private fun loadNativeLibrary(context: Context): Boolean {
        if (isLibraryLoaded) return true
        if (libraryLoadError != null) return false

        try {
            System.loadLibrary("tdjni")
            isLibraryLoaded = true
            isAvailable = true
            return true
        } catch (e: Throwable) {
            Log.d(TAG, "System.loadLibrary failed, attempting custom extraction: ${e.message}")
        }

        try {
            val manifestUrl = TelegramClient::class.java.classLoader?.getResource("manifest.json")?.toString()
            if (manifestUrl == null || !manifestUrl.startsWith("jar:file:")) {
                throw Exception("Could not locate plugin file path (url: $manifestUrl)")
            }
            val cs3Path = manifestUrl.substringAfter("file:").substringBefore("!")
            val cs3File = File(cs3Path)
            if (!cs3File.exists()) {
                throw Exception("Plugin file does not exist at $cs3Path")
            }

            val destFile = File(context.filesDir, "libtdjni.so")

            java.util.zip.ZipFile(cs3File).use { zip ->
                var foundEntry: java.util.zip.ZipEntry? = null
                for (abi in android.os.Build.SUPPORTED_ABIS) {
                    val entryName = "lib/$abi/libtdjni.so"
                    val entry = zip.getEntry(entryName)
                    if (entry != null) {
                        foundEntry = entry
                        Log.d(TAG, "Found matching ABI $abi inside plugin zip")
                        break
                    }
                }

                val targetEntry = foundEntry ?: throw Exception("No compatible ABI found in plugin lib/ directories")

                if (!destFile.exists() || destFile.length() < 10_000_000) {
                    Log.d(TAG, "Extracting libtdjni.so from zip...")
                    destFile.setWritable(true)
                    zip.getInputStream(targetEntry).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    Log.d(TAG, "libtdjni.so already exists and size matches, skipping extraction")
                }
            }

            // Set strict permissions to comply with Android 10+ dynamic library loading rules
            destFile.setReadable(true, true)
            destFile.setExecutable(true, true)
            destFile.setWritable(false, true)

            org.drinkless.tdlib.Client.load(destFile.absolutePath)
            isLibraryLoaded = true
            isAvailable = true
            Log.i(TAG, "Successfully loaded native library via custom extraction!")
            return true
        } catch (e: Throwable) {
            val err = "Failed to extract and load native library: ${e.message}"
            Log.e(TAG, err, e)
            libraryLoadError = err
            return false
        }
    }

    fun initialize(context: Context) {
        if (client != null) return
        _authState.value = TelegramAuthState.Initializing
        clearInitLog(context)
        stepLog(context, "loading native library")
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
                sendTdlibParameters(context)
                stepLog(context, "SetTdlibParameters sent")
            } catch (e: Throwable) {
                Log.e(TAG, "TDLib Client.create failed", e)
                stepLog(context, "EXCEPTION: ${e.message}")
                _authState.value = TelegramAuthState.Error("TDLib initialization failed: ${e.message}")
            }
        }
    }

    private fun stepLog(context: Context, step: String) {
        Log.d(TAG, "STEP: $step")
        try {
            File(context.filesDir, "tdlib_init_log.txt")
                .appendText("${System.currentTimeMillis()} $step\n")
        } catch (_: Throwable) {}
    }

    fun readInitLog(context: Context): String =
        try { File(context.filesDir, "tdlib_init_log.txt").readText().takeLast(2000) }
        catch (_: Throwable) { "" }

    fun clearInitLog(context: Context) =
        try { File(context.filesDir, "tdlib_init_log.txt").delete() } catch (_: Throwable) {}

    private fun sendTdlibParameters(context: Context) {
        val dbDir = File(context.filesDir, "tdlib").absolutePath
        val filesDir = File(context.filesDir, "tdlib_files").absolutePath
        client?.send(TdApi.SetTdlibParameters().also { p ->
            p.apiId = TelegramConfig.API_ID
            p.apiHash = TelegramConfig.API_HASH
            p.databaseDirectory = dbDir
            p.filesDirectory = filesDir
            p.databaseEncryptionKey = ByteArray(0)
            p.useFileDatabase = true
            p.useChatInfoDatabase = true
            p.useMessageDatabase = true // We want to enable message database to search globally efficiently
            p.useSecretChats = false
            p.systemLanguageCode = "en"
            p.deviceModel = "Android Device"
            p.systemVersion = "Android"
            p.applicationVersion = "1.0"
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
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null), null)
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
}
