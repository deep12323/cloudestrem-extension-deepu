package com.telegram

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class TelegramSettingsFragment(private val plugin: TelegramPlugin) : BottomSheetDialogFragment() {

    private lateinit var mainContainer: LinearLayout
    private lateinit var formContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            padding = 24
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#161616"))
                val radius = dp(context, 20).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(context).apply {
            text = "Telegram Extension Settings"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#2AABEE"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        mainContainer.addView(titleView)

        formContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        mainContainer.addView(formContainer)

        return mainContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            TelegramRepository.authState.collect { state ->
                updateUi(state)
            }
        }
    }

    private fun updateUi(state: TelegramAuthState) {
        val context = context ?: return
        formContainer.removeAllViews()

        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 8, 0, 8)
        }

        when (state) {
            is TelegramAuthState.Idle -> {
                val tutorialText = TextView(context).apply {
                    text = "To use this plugin securely, you must provide your own Telegram API ID and API Hash.\n1. Go to my.telegram.org and log in.\n2. Click 'API development tools'.\n3. Create an application to get your api_id and api_hash."
                    setTextColor(Color.LTGRAY)
                    textSize = 12f
                }
                val currentApiId = TelegramRepository.getApiId(context)
                val currentApiHash = TelegramRepository.getApiHash(context)

                val apiIdInput = EditText(context).apply {
                    styleEditText(this)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    hint = "API ID (e.g. 1234567)"
                    if (currentApiId != 0) setText(currentApiId.toString())
                    setTextColor(Color.parseColor("#E0E0E0"))
                    setHintTextColor(Color.GRAY)
                }
                val apiHashInput = EditText(context).apply {
                    styleEditText(this)
                    inputType = InputType.TYPE_CLASS_TEXT
                    hint = "API Hash (e.g. 0123456789abcdef)"
                    setText(currentApiHash)
                    setTextColor(Color.parseColor("#E0E0E0"))
                    setHintTextColor(Color.GRAY)
                }
                val btn = Button(context).apply {
                    styleButton(this)
                    text = "Save and Login"
                    setOnClickListener {
                        val idStr = apiIdInput.text.toString().trim()
                        val hash = apiHashInput.text.toString().trim()
                        val id = idStr.toIntOrNull()
                        if (id == null || id <= 0 || hash.isBlank()) {
                            Toast.makeText(context, "Please enter a valid API ID and Hash", Toast.LENGTH_SHORT).show()
                        } else {
                            TelegramRepository.saveApiId(context, id)
                            TelegramRepository.saveApiHash(context, hash)
                            TelegramRepository.startAuth(context)
                        }
                    }
                }
                formContainer.addView(tutorialText, layoutParams)
                formContainer.addView(apiIdInput, layoutParams)
                formContainer.addView(apiHashInput, layoutParams)
                formContainer.addView(btn, layoutParams)
                addDetailedLogView(context, layoutParams)
            }
            is TelegramAuthState.Initializing -> {
                val tv = TextView(context).apply {
                    text = "Initializing TDLib..."
                    setTextColor(Color.parseColor("#E0E0E0"))
                }
                val p = ProgressBar(context)
                formContainer.addView(tv, layoutParams)
                formContainer.addView(p, layoutParams)
                addDetailedLogView(context, layoutParams)
            }
            is TelegramAuthState.WaitPhone -> {
                val tv = TextView(context).apply {
                    text = "Enter Phone Number (e.g. +1234567890):"
                    setTextColor(Color.parseColor("#E0E0E0"))
                }
                val et = EditText(context).apply {
                    styleEditText(this)
                    inputType = InputType.TYPE_CLASS_PHONE
                    hint = "+1234567890"
                    setTextColor(Color.parseColor("#E0E0E0"))
                    setHintTextColor(Color.GRAY)
                }
                val btn = Button(context).apply {
                    styleButton(this)
                    text = "Submit Phone"
                    setOnClickListener {
                        val phone = et.text.toString().trim()
                        if (phone.isNotEmpty()) {
                            TelegramRepository.submitPhone(phone)
                        } else {
                            Toast.makeText(context, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                formContainer.addView(tv, layoutParams)
                formContainer.addView(et, layoutParams)
                formContainer.addView(btn, layoutParams)
            }
            is TelegramAuthState.WaitQr -> {
                val tv = TextView(context).apply {
                    text = "QR Code login is requested. Link: ${state.link}"
                    setTextColor(Color.parseColor("#E0E0E0"))
                }
                formContainer.addView(tv, layoutParams)
            }
            is TelegramAuthState.WaitCode -> {
                val tv = TextView(context).apply {
                    text = "Enter the SMS/App authentication code (length: ${state.codeLength}):"
                    setTextColor(Color.parseColor("#E0E0E0"))
                }
                val et = EditText(context).apply {
                    styleEditText(this)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    hint = "Code"
                    setTextColor(Color.parseColor("#E0E0E0"))
                    setHintTextColor(Color.GRAY)
                }
                val btn = Button(context).apply {
                    styleButton(this)
                    text = "Submit Code"
                    setOnClickListener {
                        val code = et.text.toString().trim()
                        if (code.isNotEmpty()) {
                            TelegramRepository.submitCode(code)
                        } else {
                            Toast.makeText(context, "Please enter the code", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                val resetBtn = Button(context).apply {
                    styleButton(this)
                    text = "Wrong number? Go Back"
                    setOnClickListener {
                        TelegramRepository.disconnect(context)
                    }
                }
                formContainer.addView(tv, layoutParams)
                formContainer.addView(et, layoutParams)
                formContainer.addView(btn, layoutParams)
                formContainer.addView(resetBtn, layoutParams)
            }
            is TelegramAuthState.WaitPassword -> {
                val tv = TextView(context).apply {
                    text = "Enter Two-Step Verification Password:"
                    setTextColor(Color.parseColor("#E0E0E0"))
                }
                val et = EditText(context).apply {
                    styleEditText(this)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    hint = "Password"
                    setTextColor(Color.parseColor("#E0E0E0"))
                    setHintTextColor(Color.GRAY)
                }
                val btn = Button(context).apply {
                    styleButton(this)
                    text = "Submit Password"
                    setOnClickListener {
                        val pass = et.text.toString().trim()
                        if (pass.isNotEmpty()) {
                            TelegramRepository.submitPassword(pass)
                        } else {
                            Toast.makeText(context, "Please enter your password", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                val resetBtn = Button(context).apply {
                    styleButton(this)
                    text = "Forgot password / Go Back"
                    setOnClickListener {
                        TelegramRepository.disconnect(context)
                    }
                }
                formContainer.addView(tv, layoutParams)
                formContainer.addView(et, layoutParams)
                formContainer.addView(btn, layoutParams)
                formContainer.addView(resetBtn, layoutParams)
            }
            is TelegramAuthState.Ready -> {
                val tv = TextView(context).apply {
                    text = "Status: Connected\nUser: ${state.firstName} (ID: ${state.userId})"
                    textSize = 16f
                    setTextColor(Color.GREEN)
                }

                // Catalogue channels configuration
                val channelsLabel = TextView(context).apply {
                    text = "Custom Catalogue Channels (comma-separated usernames or IDs):"
                    setTextColor(Color.parseColor("#E0E0E0"))
                }

                val currentChannels = TelegramRepository.getCustomChannels(context).joinToString(", ")

                val channelsInput = EditText(context).apply {
                    styleEditText(this)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 2
                    hint = "@my_channel, -1001234567"
                    setText(currentChannels)
                    setTextColor(Color.parseColor("#E0E0E0"))
                    setHintTextColor(Color.GRAY)
                }

                val btnSaveChannels = Button(context).apply {
                    styleButton(this)
                    text = "Save Catalogue Channels"
                    setOnClickListener {
                        val input = channelsInput.text.toString()
                        val list = input.split(",", " ", "\n", "\r", ";").map { it.trim() }.filter { it.isNotEmpty() }
                        TelegramRepository.saveCustomChannels(context, list)
                        Toast.makeText(context, "Catalogue channels saved!", Toast.LENGTH_SHORT).show()
                        
                        // Force TDLib to sync chats so raw IDs are cached (both Main and Archive)
                        kotlinx.coroutines.GlobalScope.launch {
                            for (chatList in listOf(org.drinkless.tdlib.TdApi.ChatListMain(), org.drinkless.tdlib.TdApi.ChatListArchive())) {
                                try {
                                    var loaded = false
                                    var attempt = 0
                                    while (!loaded && attempt < 5) {
                                        try {
                                            TelegramClient.sendRequest(org.drinkless.tdlib.TdApi.LoadChats(chatList, 100))
                                            attempt++
                                        } catch (e: Exception) {
                                            loaded = true
                                        }
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }

                val cacheLimitLabel = TextView(context).apply {
                    text = "Cache Limit in MB (0 = No Cache, -1 = No Limit):"
                    setTextColor(Color.parseColor("#E0E0E0"))
                }

                val currentLimit = TelegramRepository.getCacheLimitMb(context)
                val cacheLimitInput = EditText(context).apply {
                    styleEditText(this)
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                    hint = "1"
                    setText(currentLimit.toString())
                    setTextColor(Color.parseColor("#E0E0E0"))
                    setHintTextColor(Color.GRAY)
                }

                val btnSaveCacheLimit = Button(context).apply {
                    styleButton(this)
                    text = "Save Cache Limit"
                    setOnClickListener {
                        val limitStr = cacheLimitInput.text.toString().trim()
                        val limit = limitStr.toLongOrNull() ?: 1L
                        TelegramRepository.saveCacheLimitMb(context, limit)
                        TelegramClient.updateCacheLimit(context)
                        Toast.makeText(context, "Cache limit saved!", Toast.LENGTH_SHORT).show()
                    }
                }

                val bufferLimitLabel = TextView(context).apply {
                    text = "Buffer Size Mode:"
                    setTextColor(Color.parseColor("#E0E0E0"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                val currentBuffer = TelegramRepository.getBufferSizeMb(context)

                val radioGroup = RadioGroup(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 12, 0, 12)
                }

                val radioUnlimited = RadioButton(context).apply {
                    text = "Unlimited"
                    setTextColor(Color.parseColor("#E0E0E0"))
                    textSize = 14f
                    id = View.generateViewId()
                }

                val radioCustom = RadioButton(context).apply {
                    text = "Custom (MB)"
                    setTextColor(Color.parseColor("#E0E0E0"))
                    textSize = 14f
                    id = View.generateViewId()
                }

                val radioParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.WRAP_CONTENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 32, 0)
                }

                radioGroup.addView(radioUnlimited, radioParams)
                radioGroup.addView(radioCustom, radioParams)

                val bufferLimitInput = EditText(context).apply {
                    styleEditText(this)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    hint = "Enter Buffer Size in MB (e.g. 20)"
                    setText(if (currentBuffer <= 0) "20" else currentBuffer.toString())
                    isEnabled = (currentBuffer != -1L)
                    alpha = if (currentBuffer == -1L) 0.5f else 1.0f
                }

                if (currentBuffer == -1L) {
                    radioUnlimited.isChecked = true
                } else {
                    radioCustom.isChecked = true
                }

                radioGroup.setOnCheckedChangeListener { _, checkedId ->
                    if (checkedId == radioUnlimited.id) {
                        bufferLimitInput.isEnabled = false
                        bufferLimitInput.alpha = 0.5f
                    } else {
                        bufferLimitInput.isEnabled = true
                        bufferLimitInput.alpha = 1.0f
                        bufferLimitInput.requestFocus()
                    }
                }

                val btnSaveBufferLimit = Button(context).apply {
                    styleButton(this)
                    text = "Save Buffer Size"
                    setOnClickListener {
                        val limit = if (radioUnlimited.isChecked) {
                            -1L
                        } else {
                            val limitStr = bufferLimitInput.text.toString().trim()
                            limitStr.toLongOrNull() ?: 20L
                        }
                        TelegramRepository.saveBufferSizeMb(context, limit)
                        TelegramStreamingProxy.prefetchSizeMb = limit
                        TelegramClient.updateCacheLimit(context)
                        Toast.makeText(context, "Buffer size saved!", Toast.LENGTH_SHORT).show()
                    }
                }

                val cacheText = TextView(context).apply {
                    text = "Cache Size: Calculating..."
                    setTextColor(Color.LTGRAY)
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val size = TelegramRepository.getCacheSize(context)
                    cacheText.text = "Cache Size: ${formatBytes(size)}"
                }

                val btnClearCache = Button(context).apply {
                    styleButton(this)
                    text = "Clear Cache"
                    setOnClickListener {
                        TelegramRepository.clearCache(context)
                        cacheText.text = "Cache Size: 0 B"
                        Toast.makeText(context, "Cache Cleared", Toast.LENGTH_SHORT).show()
                    }
                }

                val btnLogout = Button(context).apply {
                    styleButton(this)
                    text = "Disconnect / Logout"
                    setOnClickListener {
                        TelegramRepository.disconnect(context)
                    }
                }

                formContainer.addView(tv, layoutParams)
                formContainer.addView(channelsLabel, layoutParams)
                formContainer.addView(channelsInput, layoutParams)
                formContainer.addView(btnSaveChannels, layoutParams)
                formContainer.addView(cacheLimitLabel, layoutParams)
                formContainer.addView(cacheLimitInput, layoutParams)
                formContainer.addView(btnSaveCacheLimit, layoutParams)
                formContainer.addView(bufferLimitLabel, layoutParams)
                formContainer.addView(radioGroup, layoutParams)
                formContainer.addView(bufferLimitInput, layoutParams)
                formContainer.addView(btnSaveBufferLimit, layoutParams)
                formContainer.addView(cacheText, layoutParams)
                formContainer.addView(btnClearCache, layoutParams)
                formContainer.addView(btnLogout, layoutParams)
            }
            is TelegramAuthState.Error -> {
                val tv = TextView(context).apply {
                    text = "Error: ${state.message}"
                    setTextColor(Color.RED)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                val btn = Button(context).apply {
                    styleButton(this)
                    text = "Retry"
                    setOnClickListener {
                        TelegramRepository.disconnect(context)
                        TelegramRepository.startAuth(context)
                    }
                }
                formContainer.addView(tv, layoutParams)
                formContainer.addView(btn, layoutParams)
                addDetailedLogView(context, layoutParams)
            }
        }
    }

    private fun addDetailedLogView(context: Context, layoutParams: LinearLayout.LayoutParams) {
        val logTitle = TextView(context).apply {
            text = "Detailed Initialization Log:"
            setTextColor(Color.parseColor("#E0E0E0"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val logTv = TextView(context).apply {
            text = TelegramClient.readDetailedInitLog(context)
            setTextColor(Color.YELLOW)
            textSize = 10f
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(context).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 280)
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            addView(logTv)
        }

        val helperText = TextView(context).apply {
            text = "The view shows the latest diagnostics. Copy All Logs copies the full initialization log."
            setTextColor(Color.LTGRAY)
            textSize = 11f
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val refreshButton = Button(context).apply {
                    styleButton(this)
            text = "Refresh Log"
            setOnClickListener {
                logTv.text = TelegramClient.readDetailedInitLog(context)
            }
        }

        val copyButton = Button(context).apply {
                    styleButton(this)
            text = "Copy All Logs"
            setOnClickListener {
                val allLogs = TelegramClient.readAllDetailedInitLog(context)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Telegram TDLib init log", allLogs))
                Toast.makeText(context, "All logs copied", Toast.LENGTH_SHORT).show()
            }
        }

        btnRow.addView(
            refreshButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        btnRow.addView(
            copyButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        formContainer.addView(logTitle, layoutParams)
        formContainer.addView(helperText, layoutParams)
        formContainer.addView(scroll)
        formContainer.addView(btnRow, layoutParams)
    }


    private fun styleButton(btn: Button) {
        btn.background = android.graphics.drawable.GradientDrawable().apply {
            colors = intArrayOf(Color.parseColor("#2AABEE"), Color.parseColor("#229ED9"))
            orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = 24f
        }
        btn.setTextColor(Color.parseColor("#E0E0E0"))
        btn.setTypeface(null, android.graphics.Typeface.BOLD)
        btn.setPadding(32, 24, 32, 24)
        btn.isAllCaps = false
    }

    private fun styleEditText(et: EditText) {
        et.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#252525"))
            cornerRadius = 16f
            setStroke(2, Color.parseColor("#353535"))
        }
        et.setTextColor(Color.parseColor("#E0E0E0"))
        et.setHintTextColor(Color.parseColor("#888888"))
        et.setPadding(32, 32, 32, 32)
    }

    private fun dp(context: Context, value: Int): Int {
        val scale = context.resources.displayMetrics.density
        return (value * scale + 0.5f).toInt()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%.0f KB".format(bytes / 1_000.0)
    }

    private var View.padding: Int
        get() = paddingLeft
        set(value) {
            val scale = resources.displayMetrics.density
            val p = (value * scale + 0.5f).toInt()
            setPadding(p, p, p, p)
        }
}
