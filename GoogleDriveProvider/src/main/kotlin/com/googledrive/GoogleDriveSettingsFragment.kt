package com.googledrive

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class GoogleDriveSettingsFragment(private val plugin: GoogleDrivePlugin) : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161616"))
                val radius = dp(context, 20).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header Title with Google Blue color
        val titleView = TextView(context).apply {
            text = "📁 Google Drive Extension Settings"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4285F4"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        mainContainer.addView(titleView)

        val formContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val scrollView = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
            addView(formContainer)
        }
        mainContainer.addView(scrollView)

        // Status Card (Connected / Disconnected)
        val isAuth = GoogleDriveRepository.isAuthenticated(context)
        val statusCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 24, 32, 24)
            background = GradientDrawable().apply {
                if (isAuth) {
                    setColor(Color.parseColor("#1B382B"))
                    setStroke(2, Color.parseColor("#34A853"))
                } else {
                    setColor(Color.parseColor("#3B3012"))
                    setStroke(2, Color.parseColor("#FBBC05"))
                }
                cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        val statusText = TextView(context).apply {
            text = if (isAuth) "Status: Connected to Google Drive ✅" else "Status: Not Logged In ⚠️"
            setTextColor(if (isAuth) Color.parseColor("#34A853") else Color.parseColor("#FBBC05"))
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusCard.addView(statusText)

        if (isAuth) {
            val btnLogout = Button(context).apply {
                styleOutlineButton(this, Color.parseColor("#EA4335"))
                text = "Logout"
                setOnClickListener {
                    GoogleDriveRepository.logout(context)
                    Toast.makeText(context, "Logged out from Google Drive", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            }
            statusCard.addView(btnLogout)
        }

        formContainer.addView(statusCard)

        // Tutorial Box
        val descView = TextView(context).apply {
            text = "1. Enter your Google OAuth Client ID and Secret below.\n2. Tap 'Login via WebView' to grant read-only access to your Google Drive files."
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                cornerRadius = 12f
            }
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }
        formContainer.addView(descView)

        // Client ID Input
        val clientIdLabel = TextView(context).apply {
            text = "OAuth Client ID:"
            setTextColor(Color.parseColor("#E0E0E0"))
            setTypeface(null, Typeface.BOLD)
        }
        formContainer.addView(clientIdLabel)

        val clientIdInput = EditText(context).apply {
            styleEditText(this)
            hint = "e.g. xxxxx.apps.googleusercontent.com"
            setText(GoogleDriveRepository.getClientId(context))
        }
        formContainer.addView(clientIdInput)

        // Client Secret Input
        val clientSecretLabel = TextView(context).apply {
            text = "OAuth Client Secret:"
            setTextColor(Color.parseColor("#E0E0E0"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 0) }
        }
        formContainer.addView(clientSecretLabel)

        val clientSecretInput = EditText(context).apply {
            styleEditText(this)
            hint = "e.g. GOCSPX-xxxxxxxxx"
            setText(GoogleDriveRepository.getClientSecret(context))
        }
        formContainer.addView(clientSecretInput)

        // Action Buttons
        val btnLogin = Button(context).apply {
            styleGradientButton(this, Color.parseColor("#4285F4"), Color.parseColor("#34A853"))
            text = if (isAuth) "Re-Authenticate / Change Account" else "Login via WebView"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 24, 0, 0) }
        }
        formContainer.addView(btnLogin)

        val webViewContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 450)
            ).apply {
                setMargins(0, 24, 0, 0)
            }
            visibility = View.GONE
        }
        formContainer.addView(webViewContainer)

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

    private fun styleGradientButton(btn: Button, colorStart: Int, colorEnd: Int) {
        btn.background = GradientDrawable().apply {
            colors = intArrayOf(colorStart, colorEnd)
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = 24f
        }
        btn.setTextColor(Color.WHITE)
        btn.setTypeface(null, Typeface.BOLD)
        btn.setPadding(32, 24, 32, 24)
        btn.isAllCaps = false
    }

    private fun styleOutlineButton(btn: Button, strokeColor: Int) {
        btn.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(2, strokeColor)
            cornerRadius = 16f
        }
        btn.setTextColor(strokeColor)
        btn.setTypeface(null, Typeface.BOLD)
        btn.setPadding(24, 12, 24, 12)
        btn.isAllCaps = false
    }

    private fun styleEditText(et: EditText) {
        et.background = GradientDrawable().apply {
            setColor(Color.parseColor("#252525"))
            cornerRadius = 16f
            setStroke(2, Color.parseColor("#353535"))
        }
        et.setTextColor(Color.parseColor("#E0E0E0"))
        et.setHintTextColor(Color.parseColor("#888888"))
        et.setPadding(32, 24, 32, 24)
    }

    private fun dp(context: Context, value: Int): Int {
        val scale = context.resources.displayMetrics.density
        return (value * scale + 0.5f).toInt()
    }
}
