package com.example.marsel

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) requestPermissionLauncher.launch(notGranted.toTypedArray())

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.setGeolocationEnabled(true)

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }
        }

        webView.addJavascriptInterface(WifiDirectBridge(), "AndroidWifiDirect")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/app.html")
        startWifiServer()
    }

    inner class WifiDirectBridge {
        @JavascriptInterface
        fun sendWifiMessage(message: String) {
            sendMessage("192.168.49.1", message)
        }
    }

    private fun startWifiServer() {
        thread {
            try {
                val serverSocket = ServerSocket(8888)
                while (true) {
                    val client = serverSocket.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val message = reader.readLine()
                    Log.d("MARSEL_WIFI", "Message recu : $message")
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.recevoirWifiMessage(${escapeJs(message)})",
                            null
                        )
                    }
                    reader.close()
                    client.close()
                }
            } catch (e: Exception) {
                Log.e("MARSEL_WIFI", "Erreur serveur : ${e.message}")
            }
        }
    }

    private fun sendMessage(host: String, message: String) {
        thread {
            try {
                val socket = Socket(host, 8888)
                val json = "{\"sourceUserId\":\"Marsel\",\"message\":\"$message\",\"status\":\"DIRECT_SENT\"}"
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write(json + "\n")
                writer.flush()
                writer.close()
                socket.close()
            } catch (e: Exception) {
                Log.e("MARSEL_WIFI", "Erreur client : ${e.message}")
            }
        }
    }

    private fun escapeJs(text: String): String {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
