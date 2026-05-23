package com.example.marsel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.VIBRATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

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
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return when {
                    url.startsWith("tel:") -> {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                        true
                    }
                    url.startsWith("mailto:") -> {
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                        true
                    }
                    url.startsWith("sms:") -> {
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                        true
                    }
                    else -> false
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }
        }

        // Register bridges
        webView.addJavascriptInterface(WifiDirectBridge(), "AndroidWifiDirect")
        webView.addJavascriptInterface(MarselBridge(), "AndroidBridge")

        setContentView(webView)
        webView.loadUrl("file:///android_asset/app.html")
        startWifiServer()
    }

    // -------------------------------------------------------------------------
    // MarselBridge – JavaScript Interface
    // -------------------------------------------------------------------------
    inner class MarselBridge {

        /** Turn on the camera flash LED */
        @JavascriptInterface
        fun activateFlash() {
            try {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
                cameraManager.setTorchMode(cameraId, true)
                Log.d("MARSEL_BRIDGE", "Flash activé")
            } catch (e: Exception) {
                Log.e("MARSEL_BRIDGE", "activateFlash error: ${e.message}")
            }
        }

        /** Turn off the camera flash LED */
        @JavascriptInterface
        fun stopFlash() {
            try {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
                cameraManager.setTorchMode(cameraId, false)
                Log.d("MARSEL_BRIDGE", "Flash désactivé")
            } catch (e: Exception) {
                Log.e("MARSEL_BRIDGE", "stopFlash error: ${e.message}")
            }
        }

        /** Start audio recording */
        @JavascriptInterface
        fun startAudioRecord() {
            if (isRecording) return
            try {
                val outputFile = File(externalCacheDir, "marsel_record_${System.currentTimeMillis()}.m4a")
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this@MainActivity)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(outputFile.absolutePath)
                    prepare()
                    start()
                }
                mediaRecorder = recorder
                isRecording = true
                Log.d("MARSEL_BRIDGE", "Enregistrement audio démarré: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("MARSEL_BRIDGE", "startAudioRecord error: ${e.message}")
            }
        }

        /** Stop audio recording */
        @JavascriptInterface
        fun stopAudioRecord() {
            if (!isRecording) return
            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
                mediaRecorder = null
                isRecording = false
                Log.d("MARSEL_BRIDGE", "Enregistrement audio arrêté")
            } catch (e: Exception) {
                Log.e("MARSEL_BRIDGE", "stopAudioRecord error: ${e.message}")
                mediaRecorder = null
                isRecording = false
            }
        }

        /** Vibrate the device for given milliseconds */
        @JavascriptInterface
        fun vibrate(ms: Long) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    val vibrator = vibratorManager.defaultVibrator
                    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(ms)
                    }
                }
                Log.d("MARSEL_BRIDGE", "Vibration: ${ms}ms")
            } catch (e: Exception) {
                Log.e("MARSEL_BRIDGE", "vibrate error: ${e.message}")
            }
        }

        /** Returns "WIFI", "MOBILE", or "NONE" */
        @JavascriptInterface
        fun getNetworkType(): String {
            return try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork ?: return "NONE"
                val capabilities = cm.getNetworkCapabilities(network) ?: return "NONE"
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WIFI"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "WIFI"
                    else -> "NONE"
                }
            } catch (e: Exception) {
                Log.e("MARSEL_BRIDGE", "getNetworkType error: ${e.message}")
                "NONE"
            }
        }
    }

    // -------------------------------------------------------------------------
    // WiFi Direct Bridge (legacy)
    // -------------------------------------------------------------------------
    inner class WifiDirectBridge {
        @JavascriptInterface
        fun sendWifiMessage(message: String) {
            sendMessage("192.168.49.1", message)
        }
    }

    // -------------------------------------------------------------------------
    // WiFi Direct Server
    // -------------------------------------------------------------------------
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

    override fun onDestroy() {
        super.onDestroy()
        // Clean up recording if app is destroyed while active
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            isRecording = false
        } catch (e: Exception) { /* ignore */ }
    }
}
