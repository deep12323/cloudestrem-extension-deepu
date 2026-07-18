package com.googledrive

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
                ViewGroup.LayoutParams.WRAP_CONTENT
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
            text = "Step 1: Enter your OAuth Client ID and Secret.\nStep 2: Click 'Open Google Login'. Authorize the app. When the browser redirects to a 'Site can't be reached' page, copy the entire URL from the address bar.\nStep 3: Paste the URL or Auth Code below and click Exchange."
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
            text = "1. Open Google Login"
            setOnClickListener {
                val clientId = clientIdInput.text.toString().trim()
                val clientSecret = clientSecretInput.text.toString().trim()
                if (clientId.isBlank() || clientSecret.isBlank()) {
                    Toast.makeText(context, "Enter Client ID and Secret first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                GoogleDriveRepository.saveClientIdSecret(context, clientId, clientSecret)
                
                val url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=${clientId}&redirect_uri=http://127.0.0.1&response_type=code&scope=https://www.googleapis.com/auth/drive.readonly&access_type=offline&prompt=consent"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
        }
        mainContainer.addView(btnLogin)

        val codeInput = EditText(context).apply {
            hint = "Auth Code or Redirect URL"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        mainContainer.addView(codeInput)

        val btnSave = Button(context).apply {
            text = "2. Save & Exchange Code"
            setOnClickListener {
                var code = codeInput.text.toString().trim()
                if (code.isBlank()) return@setOnClickListener
                
                // If the user pasted the full URL, extract the code parameter
                if (code.startsWith("http")) {
                    try {
                        val uri = Uri.parse(code)
                        val extracted = uri.getQueryParameter("code")
                        if (!extracted.isNullOrBlank()) {
                            code = extracted
                        }
                    } catch (e: Exception) {}
                }
                
                Toast.makeText(context, "Exchanging code...", Toast.LENGTH_SHORT).show()
                viewLifecycleOwner.lifecycleScope.launch {
                    val success = GoogleDriveRepository.exchangeAuthCodeForTokens(context, code)
                    if (success) {
                        Toast.makeText(context, "Login Successful!", Toast.LENGTH_LONG).show()
                        dismiss()
                    } else {
                        Toast.makeText(context, "Failed to login. Check your credentials.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        mainContainer.addView(btnSave)

        return mainContainer
    }
}
