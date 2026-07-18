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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException

class GoogleDriveSettingsFragment(private val plugin: GoogleDrivePlugin) : BottomSheetDialogFragment() {

    private var currentServerSocket: ServerSocket? = null

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
            text = "Enter your OAuth Client ID and Secret (Web Application type). Ensure you have added 'http://127.0.0.1:8080/callback' to your Authorized redirect URIs in Google Cloud Console. Then click 'Automatic Login'."
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

        val statusText = TextView(context).apply {
            text = ""
            setTextColor(Color.YELLOW)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 16)
            }
        }
        mainContainer.addView(statusText)

        val btnLogin = Button(context).apply {
            text = "Automatic Login"
            setOnClickListener {
                val clientId = clientIdInput.text.toString().trim()
                val clientSecret = clientSecretInput.text.toString().trim()
                if (clientId.isBlank() || clientSecret.isBlank()) {
                    Toast.makeText(context, "Enter Client ID and Secret first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                GoogleDriveRepository.saveClientIdSecret(context, clientId, clientSecret)
                
                statusText.text = "Waiting for login in browser... (Timeout: 2 mins)"
                this.isEnabled = false

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val redirectUri = "http://127.0.0.1:8080/callback"
                    var code: String? = null
                    
                    try {
                        currentServerSocket?.close()
                        currentServerSocket = ServerSocket(8080)
                        currentServerSocket?.soTimeout = 120000 // 2 minutes timeout
                        
                        val url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=${clientId}&redirect_uri=${redirectUri}&response_type=code&scope=https://www.googleapis.com/auth/drive.readonly&access_type=offline&prompt=consent"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        withContext(Dispatchers.Main) {
                            startActivity(intent)
                        }
                        
                        val socket = currentServerSocket?.accept()
                        if (socket != null) {
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            val output: OutputStream = socket.getOutputStream()
                            val requestLine = reader.readLine()
                            if (requestLine != null && requestLine.contains("GET /callback?code=")) {
                                val codePart = requestLine.split(" ")[1].split("code=")[1].split("&")[0]
                                code = codePart
                                
                                val responseHtml = "<html><body><h2>Login Successful!</h2><p>You can close this window and return to CloudStream.</p></body></html>"
                                val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${responseHtml.length}\r\nConnection: close\r\n\r\n$responseHtml"
                                output.write(httpResponse.toByteArray())
                                output.flush()
                            } else {
                                val responseHtml = "<html><body><h2>Login Failed</h2><p>Invalid request received.</p></body></html>"
                                val httpResponse = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html\r\nContent-Length: ${responseHtml.length}\r\nConnection: close\r\n\r\n$responseHtml"
                                output.write(httpResponse.toByteArray())
                                output.flush()
                            }
                            socket.close()
                        }
                    } catch (e: SocketTimeoutException) {
                        withContext(Dispatchers.Main) {
                            statusText.text = "Login timed out."
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        currentServerSocket?.close()
                        currentServerSocket = null
                        
                        if (code != null) {
                            withContext(Dispatchers.Main) {
                                statusText.text = "Exchanging code..."
                            }
                            val success = GoogleDriveRepository.exchangeAuthCodeForTokens(context, code, redirectUri)
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(context, "Login Successful!", Toast.LENGTH_LONG).show()
                                    dismiss()
                                } else {
                                    statusText.text = "Failed to exchange code. Check credentials."
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                if (statusText.text.toString().startsWith("Waiting")) {
                                    statusText.text = "Login aborted or failed."
                                }
                                this@apply.isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        mainContainer.addView(btnLogin)

        val btnCancel = Button(context).apply {
            text = "Cancel Login"
            setOnClickListener {
                currentServerSocket?.close()
                currentServerSocket = null
                statusText.text = "Login cancelled."
                btnLogin.isEnabled = true
            }
        }
        mainContainer.addView(btnCancel)

        return mainContainer
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            currentServerSocket?.close()
        } catch (e: Exception) {}
        currentServerSocket = null
    }
}
