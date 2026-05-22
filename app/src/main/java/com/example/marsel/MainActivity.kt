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
    ) { /* permission accordée ou refusée, le WebView gère via onGeolocationPermissionsShowPrompt */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Demande des permissions GPS au démarrage
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

        webView.addJavascriptInterface(
            WifiDirectBridge(),
            "AndroidWifiDirect"
        )

        setContentView(webView)

        webView.loadUrl("file:///android_asset/app.html")

        startWifiServer()
    }

    inner class WifiDirectBridge {

        @JavascriptInterface
        fun sendWifiMessage(message: String) {

            sendMessage(
                "192.168.49.1",
                message
            )
        }
    }

    private fun startWifiServer() {

        thread {

            try {

                val serverSocket = ServerSocket(8888)

                while (true) {

                    val client = serverSocket.accept()

                    val reader = BufferedReader(
                        InputStreamReader(
                            client.getInputStream()
                        )
                    )

                    val message = reader.readLine()

                    Log.d(
                        "MARSEL_WIFI",
                        "Message reçu : $message"
                    )

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

                Log.e(
                    "MARSEL_W