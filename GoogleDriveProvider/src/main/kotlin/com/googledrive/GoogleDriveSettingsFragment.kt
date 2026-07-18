package com.googledrive

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

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
            text = "To access your private Google Drive files, you need to provide your OAuth Client ID, Client Secret, and a Refresh Token generated via Google OAuth 2.0 Playground."
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

        val refreshTokenInput = EditText(context).apply {
            hint = "Refresh Token"
            setText(GoogleDriveRepository.getRefreshToken(context))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        mainContainer.addView(refreshTokenInput)

        val btnSave = Button(context).apply {
            text = "Save Credentials"
            setOnClickListener {
                val clientId = clientIdInput.text.toString().trim()
                val clientSecret = clientSecretInput.text.toString().trim()
                val refreshToken = refreshTokenInput.text.toString().trim()
                GoogleDriveRepository.saveCredentials(context, clientId, clientSecret, refreshToken)
                Toast.makeText(context, "Credentials saved!", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
        mainContainer.addView(btnSave)

        return mainContainer
    }
}
