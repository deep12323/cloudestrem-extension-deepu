package com.googledrive

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class GoogleDriveSettingsFragment(private val plugin: GoogleDrivePlugin) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(context).apply {
            text = "Google Drive Settings"
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

        val descView = TextView(context).apply {
            text = "Enter your OAuth Client ID and Secret. Then click 'Login via WebView'."
            setTextColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 32)
            }
        }
        mainContainer.addView(descView)

        val clientIdInput = EditText(context).apply {
            hint = "Client ID"
            setText(GoogleDriveRepository.getClientId(context))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        mainContainer.addView(clientIdInput)

        val clientSecretInput = EditText(context).apply {
            hint = "Client Secret"
            setText(GoogleDriveRepository.getClientSecret(context))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        mainContainer.addView(clientSecretInput)

        val btnLogin = Button(context).apply {
            text = "Login via WebView"
        }
        mainContainer.addView(btnLogin)

        val webViewContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1500 // fixed height for webview to render properly inside bottom sheet
            ).apply {
                setMargins(0, 32, 0, 0)
            }
            visibility = View.GONE
        }
        mainContainer.addView(webViewContainer)

        btnLogin.setOnClickListener {
            val clientId = clientIdInput.text.toString().trim()
            val clientSecret = clientSecretInput.text.toString().trim()
            if (clientId.isBlank() || clientSecret.isBlank()) {
                Toast.makeText(context, "Enter Client ID and Secret first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            GoogleDriveRepository.saveClientIdSecret(context, clientId, clientSecret)
            
            btnLogin.isEnabled = false
            webViewContainer.visibility = View.VISIBLE
            webViewContainer.removeAllViews()

            val webView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                // Fake user agent to bypass Google's "disallowed_useragent" WebView block
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
                
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                            val uri = android.net.Uri.parse(url)
                            val code = uri.getQueryParameter("code")
                            if (!code.isNullOrBlank()) {
                                Toast.makeText(context, "Exchanging code...", Toast.LENGTH_SHORT).show()
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val success = GoogleDriveRepository.exchangeAuthCodeForTokens(context, code, "http://127.0.0.1:8080/callback")
                                    if (success) {
                                        Toast.makeText(context, "Login Successful!", Toast.LENGTH_LONG).show()
                                        dismiss()
                                    } else {
                                        Toast.makeText(context, "Failed to login.", Toast.LENGTH_LONG).show()
                                        btnLogin.isEnabled = true
                                        webViewContainer.visibility = View.GONE
                                    }
                                }
                                return true
                            }
                        }
                        return false
                    }
                }
            }
            webViewContainer.addView(webView)
            
            val authUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=${clientId}&redirect_uri=http://127.0.0.1:8080/callback&response_type=code&scope=https://www.googleapis.com/auth/drive.readonly&access_type=offline&prompt=consent"
            webView.loadUrl(authUrl)
        }

        return mainContainer
    }
}
