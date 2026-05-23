package com.example.marsel

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    // -------------------------------------------------------------------------
    // Core WebView
    // -------------------------------------------------------------------------
    private lateinit var webView: WebView
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false

    // -------------------------------------------------------------------------
    // WiFi P2P fields
    // -------------------------------------------------------------------------
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var wifiP2pChannel: WifiP2pManager.Channel
    private lateinit var wifiP2pReceiver: WifiP2pBroadcastReceiver
    private val peerDevices = mutableListOf<WifiP2pDevice>()
    private var isGroupOwner = false
    private var groupOwnerAddress: String? = null
    private val pendingRelayMessages = mutableListOf<String>()
    private val processedMessageIds = mutableSetOf<String>()
    private var wifiP2pEnabled = false

    // -------------------------------------------------------------------------
    // Location fields
    // -------------------------------------------------------------------------
    private lateinit var locationManager: LocationManager
    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0
    private var hasLocation = false
    private var locationListenerGps: LocationListener? = null
    private var locationListenerNetwork: LocationListener? = null

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------
    companion object {
        private const val CHANNEL_ID = "marsel_alerts"
        private const val MARSEL_ALERT_ID = 1001
        private const val TAG = "MARSEL"
    }

    // -------------------------------------------------------------------------
    // Permission launcher
    // -------------------------------------------------------------------------
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // After permissions resolved, initialise location if newly granted
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            initLocationManager()
        }
    }

    // =========================================================================
    // onCreate
    // =========================================================================
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Notification channel (must be created before any notification) ---
        createNotificationChannel()

        // --- Request permissions ---
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.VIBRATE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CHANGE_NETWORK_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) requestPermissionLauncher.launch(notGranted.toTypedArray())

        // --- WiFi P2P setup ---
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        wifiP2pChannel = wifiP2pManager.initialize(this, mainLooper, null)
        wifiP2pReceiver = WifiP2pBroadcastReceiver()

        // --- Location manager ---
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        initLocationManager()

        // --- WebView ---
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

        // --- JavaScript bridges ---
        webView.addJavascriptInterface(WifiDirectBridge(), "AndroidWifiDirect")
        webView.addJavascriptInterface(MarselBridge(), "AndroidBridge")

        setContentView(webView)
        webView.loadUrl("file:///android_asset/app.html")

        // --- Start servers ---
        startWifiServer()       // legacy port 8888
        startRelayServer()      // relay port 8890
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================
    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        registerReceiver(wifiP2pReceiver, intentFilter)
        startLocationUpdatesInternal()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(wifiP2pReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "onPause unregister receiver: ${e.message}")
        }
        // Do NOT stop relay server — keep running in background
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdatesInternal()
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) cameraManager.setTorchMode(cameraId, false)
        } catch (e: Exception) { /* ignore */ }
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            isRecording = false
        } catch (e: Exception) { /* ignore */ }
    }

    // =========================================================================
    // Notification channel
    // =========================================================================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alertes Marsel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications d'alerte Marsel"
                enableLights(true)
                enableVibration(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    // =========================================================================
    // Location management
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun initLocationManager() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // Seed with last-known
        try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
                lastLat = loc.latitude
                lastLng = loc.longitude
                hasLocation = true
            }
            if (!hasLocation) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { loc ->
                    lastLat = loc.latitude
                    lastLng = loc.longitude
                    hasLocation = true
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "initLocationManager: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdatesInternal() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLat = location.latitude
                lastLng = location.longitude
                hasLocation = true
                val acc = location.accuracy
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onLocationUpdate && window.onLocationUpdate({lat:$lastLat,lng:$lastLng,accuracy:$acc})",
                        null
                    )
                }
            }
            @Deprecated("Kept for API < 29 compatibility")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    10_000L,
                    10f,
                    listener
                )
                locationListenerGps = listener
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "GPS updates: ${e.message}")
        }

        val netListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // Only use network if GPS has not provided a fix yet
                if (!hasLocation) {
                    lastLat = location.latitude
                    lastLng = location.longitude
                    hasLocation = true
                }
                val acc = location.accuracy
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onLocationUpdate && window.onLocationUpdate({lat:${location.latitude},lng:${location.longitude},accuracy:$acc})",
                        null
                    )
                }
            }
            @Deprecated("Kept for API < 29 compatibility")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    10_000L,
                    10f,
                    netListener
                )
                locationListenerNetwork = netListener
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Network location updates: ${e.message}")
        }
    }

    private fun stopLocationUpdatesInternal() {
        try {
            locationListenerGps?.let { locationManager.removeUpdates(it) }
            locationListenerNetwork?.let { locationManager.removeUpdates(it) }
        } catch (e: Exception) {
            Log.w(TAG, "stopLocationUpdates: ${e.message}")
        }
        locationListenerGps = null
        locationListenerNetwork = null
    }

    // =========================================================================
    // WiFi Direct Broadcast Receiver
    // =========================================================================
    inner class WifiP2pBroadcastReceiver : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    wifiP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "WiFi P2P state: ${if (wifiP2pEnabled) "ENABLED" else "DISABLED"}")
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiP2pManager.requestPeers(wifiP2pChannel) { peers ->
                        peerDevices.clear()
                        peerDevices.addAll(peers.deviceList)
                        val peerJson = peers.deviceList.joinToString(",", "[", "]") {
                            """{"name":"${it.deviceName.replace("\"", "\\\"")}","address":"${it.deviceAddress}"}"""
                        }
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "window.onPeersDiscovered && window.onPeersDiscovered($peerJson)",
                                null
                            )
                        }
                        // Auto-connect to first peer if we have relay messages pending
                        if (pendingRelayMessages.isNotEmpty() && peers.deviceList.isNotEmpty()) {
                            connectToPeer(peers.deviceList.first())
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (networkInfo?.isConnected == true) {
                        wifiP2pManager.requestConnectionInfo(wifiP2pChannel) { info ->
                            isGroupOwner = info.isGroupOwner
                            groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                            val address = groupOwnerAddress ?: return@requestConnectionInfo

                            Log.d(TAG, "P2P connected. GO=$isGroupOwner, GOAddr=$address")

                            // Clients send pending relay messages to the GO
                            if (!isGroupOwner) {
                                val snapshot = pendingRelayMessages.toList()
                                pendingRelayMessages.clear()
                                snapshot.forEach { msg ->
                                    sendRelayToPeer(address, msg)
                                }
                            }

                            runOnUiThread {
                                webView.evaluateJavascript(
                                    "window.onP2PConnected && window.onP2PConnected({isOwner:$isGroupOwner,address:'$address'})",
                                    null
                                )
                            }
                        }
                    } else {
                        isGroupOwner = false
                        groupOwnerAddress = null
                        Log.d(TAG, "P2P disconnected")
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // No specific handling needed; could expose device info to JS if required
                    Log.d(TAG, "This device changed")
                }
            }
        }
    }

    // =========================================================================
    // Relay server on port 8890
    // =========================================================================
    private fun startRelayServer() {
        thread {
            try {
                val server = ServerSocket(8890)
                Log.d(TAG, "Relay server listening on port 8890")
                while (true) {
                    val client = server.accept()
                    thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val line = reader.readLine()
                            if (line != null) processRelayMessage(line)
                            reader.close()
                            client.close()
                        } catch (e: Exception) {
                            Log.e("MARSEL_RELAY", "Client handler error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MARSEL_RELAY", "Relay server error: ${e.message}")
            }
        }
    }

    // =========================================================================
    // processRelayMessage
    // =========================================================================
    private fun processRelayMessage(json: String) {
        try {
            val messageId = extractJsonString(json, "messageId") ?: run {
                Log.w("MARSEL_RELAY", "No messageId in relay message")
                return
            }

            // Duplicate prevention
            synchronized(processedMessageIds) {
                if (processedMessageIds.contains(messageId)) {
                    Log.d("MARSEL_RELAY", "Duplicate message $messageId, dropping")
                    return
                }
                processedMessageIds.add(messageId)
                // Prevent unbounded growth
                if (processedMessageIds.size > 1000) {
                    processedMessageIds.iterator().let { it.next(); it.remove() }
                }
            }

            // Notify JS to display on map
            val safeJson = json.replace("'", "\\'")
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onRelayMessageReceived && window.onRelayMessageReceived('$safeJson')",
                    null
                )
            }

            val hopCount = extractJsonInt(json, "hopCount") ?: 0
            val maxHops = extractJsonInt(json, "maxHops") ?: 10

            if (hopCount >= maxHops) {
                Log.d("MARSEL_RELAY", "Message $messageId reached maxHops ($maxHops), dropping")
                return
            }

            val pseudo = extractJsonString(json, "pseudo") ?: "Utilisateur"
            showNotificationDirect("Alerte Marsel", "$pseudo a declenche une alerte d'urgence")

            val netType = getNetworkTypeDirect()
            if (netType == "WIFI" || netType == "MOBILE") {
                // Forward to contacts via SMS
                forwardEmergencyToContacts(json)
            } else {
                // No internet — increment hop and relay further
                val updatedJson = json.replace(
                    "\"hopCount\":$hopCount",
                    "\"hopCount\":${hopCount + 1}"
                )
                synchronized(pendingRelayMessages) {
                    pendingRelayMessages.add(updatedJson)
                }
                // Send to GO if we're a client
                groupOwnerAddress?.let { addr ->
                    if (!isGroupOwner) sendRelayToPeer(addr, updatedJson)
                }
            }
        } catch (e: Exception) {
            Log.e("MARSEL_RELAY", "processRelayMessage: ${e.message}")
        }
    }

    // =========================================================================
    // Send relay message to a specific peer
    // =========================================================================
    private fun sendRelayToPeer(address: String, messageJson: String) {
        thread {
            try {
                val socket = Socket(address, 8890)
                socket.soTimeout = 5000
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write(messageJson + "\n")
                writer.flush()
                writer.close()
                socket.close()
                Log.d("MARSEL_RELAY", "Relayed to $address")
            } catch (e: Exception) {
                Log.e("MARSEL_RELAY", "Relay to $address failed: ${e.message}")
            }
        }
    }

    // =========================================================================
    // Connect to a WiFi P2P peer
    // =========================================================================
    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        wifiP2pManager.connect(wifiP2pChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connecting to ${device.deviceName}")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connect to ${device.deviceName} failed: reason=$reason")
            }
        })
    }

    // =========================================================================
    // Helper: JSON extraction without external library
    // =========================================================================
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val pattern = """"$key"\s*:\s*(\d+)""".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    // =========================================================================
    // Network type (callable from background threads)
    // =========================================================================
    private fun getNetworkTypeDirect(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return "NONE"
                val caps = cm.getNetworkCapabilities(network) ?: return "NONE"
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "WIFI"
                    else -> "NONE"
                }
            } else {
                @Suppress("DEPRECATION")
                when (cm.activeNetworkInfo?.type) {
                    ConnectivityManager.TYPE_WIFI -> "WIFI"
                    ConnectivityManager.TYPE_MOBILE -> "MOBILE"
                    else -> "NONE"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getNetworkTypeDirect: ${e.message}")
            "NONE"
        }
    }

    // =========================================================================
    // Notification helper (callable from any thread)
    // =========================================================================
    private fun showNotificationDirect(title: String, body: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(MARSEL_ALERT_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "showNotificationDirect: ${e.message}")
        }
    }

    // =========================================================================
    // SMS helper (callable from any thread)
    // =========================================================================
    private fun sendSMSDirect(phoneNumber: String, message: String) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w("MARSEL_SMS", "SEND_SMS permission not granted")
                return
            }
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }
            Log.d("MARSEL_SMS", "SMS sent to $phoneNumber")
        } catch (e: SecurityException) {
            Log.e("MARSEL_SMS", "SecurityException sending SMS to $phoneNumber: ${e.message}")
        } catch (e: Exception) {
            Log.e("MARSEL_SMS", "SMS failed to $phoneNumber: ${e.message}")
        }
    }

    // =========================================================================
    // Forward emergency to contacts via SMS
    // =========================================================================
    private fun forwardEmergencyToContacts(json: String) {
        try {
            val contactsMatch = """"contacts"\s*:\s*\[([^\]]*)\]""".toRegex().find(json)
            contactsMatch?.groupValues?.getOrNull(1)?.let { contactsJson ->
                val mobilePattern = """"mobile"\s*:\s*"([^"]+)"""".toRegex()
                val pseudo = extractJsonString(json, "pseudo") ?: "Utilisateur"
                val lat = """"lat"\s*:\s*([\d.eE+\-]+)""".toRegex().find(json)?.groupValues?.getOrNull(1) ?: ""
                val lng = """"lng"\s*:\s*([\d.eE+\-]+)""".toRegex().find(json)?.groupValues?.getOrNull(1) ?: ""
                val mapsLink = if (lat.isNotEmpty() && lng.isNotEmpty()) {
                    "https://maps.google.com/maps?q=$lat,$lng"
                } else {
                    "Position inconnue"
                }
                val smsMsg = "ALERTE MARSEL\n$pseudo a declenche une alerte d'urgence.\nPosition: $mapsLink"

                mobilePattern.findAll(contactsJson).forEach { match ->
                    val mobile = match.groupValues[1]
                    if (mobile.isNotEmpty()) {
                        sendSMSDirect(mobile, smsMsg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "forwardEmergencyToContacts: ${e.message}")
        }
    }

    // =========================================================================
    // Legacy WiFi server on port 8888
    // =========================================================================
    private fun startWifiServer() {
        thread {
            try {
                val serverSocket = ServerSocket(8888)
                Log.d(TAG, "WiFi server listening on port 8888")
                while (true) {
                    val client = serverSocket.accept()
                    thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val message = reader.readLine()
                            Log.d("MARSEL_WIFI", "Message recu : $message")
                            runOnUiThread {
                                webView.evaluateJavascript(
                                    "window.recevoirWifiMessage && window.recevoirWifiMessage(${escapeJs(message ?: "")})",
                                    null
                                )
                            }
                            reader.close()
                            client.close()
                        } catch (e: Exception) {
                            Log.e("MARSEL_WIFI", "Client handler: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MARSEL_WIFI", "Serveur erreur: ${e.message}")
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
                Log.e("MARSEL_WIFI", "Erreur client: ${e.message}")
            }
        }
    }

    private fun escapeJs(text: String): String {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
    }

    // =========================================================================
    // MarselBridge – JavaScript Interface
    // =========================================================================
    inner class MarselBridge {

        // -----------------------------------------------------------------------
        // Flash
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun activateFlash() {
            try {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
                cameraManager.setTorchMode(cameraId, true)
                Log.d("MARSEL_BRIDGE", "Flash active")
            } catch (e: Exception) {
                Log.e("MARSEL_BRIDGE", "activateFlash error: ${e.message}")
            }
        }

        @JavascriptInterface
        fun stopFlash() {
            try {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
                cameraManager.setTorchMode(cameraId, false)
                Log.d("MARSEL_BRIDGE", "Flash desactive")
            } catch (e: Exception) {
                Log.e("MARSEL_BRIDGE", "stopFlash error: ${e.message}")
            }
        }

        // -----------------------------------------------------------------------
        // Audio recording
        // -----------------------------------------------------------------------

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
                Log.d("MARSEL_BRIDGE", "Enregistrement audio demarre: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("MARSEL_BRIDGE", "startAudioRecord error: ${e.message}")
            }
        }

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
                Log.d("MARSEL_BRIDGE", "Enregistrement audio arrete")
            } catch (e: Exception) {
                Log.e("MARSEL_BRIDGE", "stopAudioRecord error: ${e.message}")
                mediaRecorder = null
                isRecording = false
            }
        }

        // -----------------------------------------------------------------------
        // Vibration
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun vibrate(ms: Long) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator.vibrate(
                        VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
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

        // -----------------------------------------------------------------------
        // Network type
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun getNetworkType(): String {
            return getNetworkTypeDirect()
        }

        // -----------------------------------------------------------------------
        // SMS
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun sendEmergencySMS(phoneNumber: String, message: String) {
            thread {
                sendSMSDirect(phoneNumber, message)
            }
        }

        // -----------------------------------------------------------------------
        // WiFi Direct relay
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun sendEmergencyViaRelay(emergencyJson: String) {
            thread {
                try {
                    // Ensure discovery is running
                    startP2PDiscovery()

                    synchronized(pendingRelayMessages) {
                        pendingRelayMessages.add(emergencyJson)
                    }

                    // Send to all known peers if already connected
                    val goAddr = groupOwnerAddress
                    if (goAddr != null && !isGroupOwner) {
                        sendRelayToPeer(goAddr, emergencyJson)
                        synchronized(pendingRelayMessages) {
                            pendingRelayMessages.remove(emergencyJson)
                        }
                    } else if (isGroupOwner) {
                        // If we're the GO, just store — clients will connect to us
                        Log.d(TAG, "We are GO; relay stored for incoming clients")
                    } else {
                        Log.d(TAG, "No peers connected; relay queued for when peer connects")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sendEmergencyViaRelay: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun startP2PDiscovery() {
            if (!wifiP2pEnabled) {
                Log.w(TAG, "WiFi P2P not enabled, cannot start discovery")
                return
            }
            wifiP2pManager.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P peer discovery started")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "P2P peer discovery failed: reason=$reason")
                }
            })
        }

        @JavascriptInterface
        fun stopP2PDiscovery() {
            wifiP2pManager.stopPeerDiscovery(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P discovery stopped")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "P2P stop discovery failed: reason=$reason")
                }
            })
        }

        @JavascriptInterface
        fun getP2PPeers(): String {
            return synchronized(peerDevices) {
                peerDevices.joinToString(",", "[", "]") { device ->
                    """{"name":"${device.deviceName.replace("\"", "\\\"")}","address":"${device.deviceAddress}"}"""
                }
            }
        }

        @JavascriptInterface
        fun createP2PGroup() {
            wifiP2pManager.createGroup(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P group created (autonomous GO)")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "createP2PGroup failed: reason=$reason")
                }
            })
        }

        @JavascriptInterface
        fun removeP2PGroup() {
            wifiP2pManager.removeGroup(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P group removed")
                    isGroupOwner = false
                    groupOwnerAddress = null
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "removeP2PGroup failed: reason=$reason")
                }
            })
        }

        // -----------------------------------------------------------------------
        // Notifications
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun showNotification(title: String, body: String) {
            showNotificationDirect(title, body)
        }

        // -----------------------------------------------------------------------
        // Location
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun getLocation(): String {
            return if (hasLocation) {
                """{"lat":$lastLat,"lng":$lastLng,"accuracy":0.0}"""
            } else {
                // Attempt to return last known synchronously
                try {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (loc != null) {
                            lastLat = loc.latitude
                            lastLng = loc.longitude
                            hasLocation = true
                            """{"lat":$lastLat,"lng":$lastLng,"accuracy":${loc.accuracy}}"""
                        } else {
                            ""
                        }
                    } else {
                        ""
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "getLocation SecurityException: ${e.message}")
                    ""
                } catch (e: Exception) {
                    Log.e(TAG, "getLocation: ${e.message}")
                    ""
                }
            }
        }

        @JavascriptInterface
        fun startLocationUpdates() {
            runOnUiThread {
                startLocationUpdatesInternal()
            }
        }

        @JavascriptInterface
        fun stopLocationUpdates() {
            stopLocationUpdatesInternal()
        }

        // -----------------------------------------------------------------------
        // Legacy WiFi Direct send
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun sendWifiMessage(message: String) {
            sendMessage("192.168.49.1", message)
        }
    }

    // =========================================================================
    // WiFi Direct Bridge (legacy — keep existing interface name)
    // =========================================================================
    inner class WifiDirectBridge {
        @JavascriptInterface
        fun sendWifiMessage(message: String) {
            sendMessage("192.168.49.1", message)
        }
    }
}
