package com.example.marsel

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

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
                    "MARSEL_WIFI",
                    "Erreur serveur : ${e.message}"
                )
            }
        }
    }

    private fun sendMessage(
        host: String,
        message: String
    ) {

        thread {

            try {

                val socket = Socket(host, 8888)

                val json = """
                    {
                      "sourceUserId":"Marsel",
                      "message":"$message",
                      "status":"DIRECT_SENT"
                    }
                """.trimIndent()

                val writer = OutputStreamWriter(
                    socket.getOutputStream()
                )

                writer.write(json + "\n")
                writer.flush()

                writer.close()
                socket.close()

            } catch (e: Exception) {

                Log.e(
                    "MARSEL_WIFI",
                    "Erreur client : ${e.message}"
                )
            }
        }
    }

    private fun escapeJs(text: String): String {

        return "\"" +
                text
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n") +
                "\""
    }
}