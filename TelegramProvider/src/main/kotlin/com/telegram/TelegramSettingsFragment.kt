package com.telegram

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
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(context).apply {
            text = "Telegram Extension Settings"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
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
                val tv = TextView(context).apply {
                    text = "You are not connected to Telegram. Click below to start authentication."
                    setTextColor(Color.LIGHTGRAY)
                }
                val btn = Button(context).apply {
                    text = "Login with Telegram"
                    setOnClickListener {
                        TelegramRepository.startAuth(context)
                    }
                }
                formContainer.addView(tv, layoutParams)
                formContainer.addView(btn, layoutParams)
            }
            is TelegramAuthState.Initializing -> {
                val tv = TextView(context).apply {
                    text = "Initializing TDLib..."
                    setTextColor(Color.WHITE)
                }
                val p = ProgressBar(context)
                formContainer.addView(tv, layoutParams)
                formContainer.addView(p, layoutParams)
            }
            is TelegramAuthState.WaitPhone -> {
                val tv = TextView(context).apply {
                    text = "Enter Phone Number (e.g. +1234567890):"
                    setTextColor(Color.WHITE)
                }
                val et = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_PHONE
                    hint = "+1234567890"
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                }
                val btn = Button(context).apply {
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
                    setTextColor(Color.WHITE)
                }
                formContainer.addView(tv, layoutParams)
            }
            is TelegramAuthState.WaitCode -> {
                val tv = TextView(context).apply {
                    text = "Enter the SMS/App authentication code (length: ${state.codeLength}):"
                    setTextColor(Color.WHITE)
                }
                val et = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    hint = "Code"
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                }
                val btn = Button(context).apply {
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
                formContainer.addView(tv, layoutParams)
                formContainer.addView(et, layoutParams)
                formContainer.addView(btn, layoutParams)
            }
            is TelegramAuthState.WaitPassword -> {
                val tv = TextView(context).apply {
                    text = "Enter Two-Step Verification Password:"
                    setTextColor(Color.WHITE)
                }
                val et = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    hint = "Password"
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                }
                val btn = Button(context).apply {
                    text = "Submit Password"
                    setOnClickListener {
                        val pwd = et.text.toString()
                        if (pwd.isNotEmpty()) {
                            TelegramRepository.submitPassword(pwd)
                        } else {
                            Toast.makeText(context, "Please enter your password", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                formContainer.addView(tv, layoutParams)
                formContainer.addView(et, layoutParams)
                formContainer.addView(btn, layoutParams)
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
                    setTextColor(Color.WHITE)
                }

                val currentChannels = TelegramRepository.getCustomChannels(context).joinToString(", ")

                val channelsInput = EditText(context).apply {
                    hint = "@my_channel, -1001234567"
                    setText(currentChannels)
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                }

                val btnSaveChannels = Button(context).apply {
                    text = "Save Catalogue Channels"
                    setOnClickListener {
                        val input = channelsInput.text.toString()
                        val list = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        TelegramRepository.saveCustomChannels(context, list)
                        Toast.makeText(context, "Catalogue channels saved!", Toast.LENGTH_SHORT).show()
                    }
                }

                val cacheText = TextView(context).apply {
                    text = "Cache Size: Calculating..."
                    setTextColor(Color.LIGHTGRAY)
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val size = TelegramRepository.getCacheSize(context)
                    cacheText.text = "Cache Size: ${formatBytes(size)}"
                }

                val btnClearCache = Button(context).apply {
                    text = "Clear Cache"
                    setOnClickListener {
                        TelegramRepository.clearCache(context)
                        cacheText.text = "Cache Size: 0 B"
                        Toast.makeText(context, "Cache Cleared", Toast.LENGTH_SHORT).show()
                    }
                }

                val btnLogout = Button(context).apply {
                    text = "Disconnect / Logout"
                    setOnClickListener {
                        TelegramRepository.disconnect(context)
                    }
                }

                formContainer.addView(tv, layoutParams)
                formContainer.addView(channelsLabel, layoutParams)
                formContainer.addView(channelsInput, layoutParams)
                formContainer.addView(btnSaveChannels, layoutParams)
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
                    text = "Retry"
                    setOnClickListener {
                        TelegramRepository.disconnect(context)
                        TelegramRepository.startAuth(context)
                    }
                }
                formContainer.addView(tv, layoutParams)
                formContainer.addView(btn, layoutParams)
            }
        }
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
