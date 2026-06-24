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
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    // DNS-SD service tracking: keep EMERGENCY alive alongside POSITION_UPDATE
    private var activeEmergencyRecord: Map<String, String>? = null
    private var activePositionRecord: Map<String, String>? = null
    // Keep WifiP2pServiceInfo references so we can removeLocalService for position
    // without ever touching the alert service (avoids the clear→gap→re-add cycle).
    private var activeAlertServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private var activePositionServiceInfo: WifiP2pDnsSdServiceInfo? = null
    // Rate-limit position broadcasts: max 1 per 8s (interval fires every 10s)
    private var lastPositionBroadcastMs: Long = 0

    // DNS-SD connection-less relay: periodic re-arm of service discovery
    private val p2pHandler = Handler(Looper.getMainLooper())
    private val rediscoverRunnable = object : Runnable {
        override fun run() {
            restartServiceDiscovery()
            p2pHandler.postDelayed(this, 20_000)
        }
    }

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
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
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

        // DNS-SD listeners must be attached once before any discoverServices call.
        // This is the primary relay transport: TXT records are received from nearby
        // phones without connection, pairing, or any user dialog.
        setupDnsSdListeners()
        p2pHandler.postDelayed(rediscoverRunnable, 3_000)

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
        restartServiceDiscovery()
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
        p2pHandler.removeCallbacksAndMessages(null)
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
                        "window.onLocationUpdate && window.onLocationUpdate($lastLat,$lastLng,$acc)",
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
                        "window.onLocationUpdate && window.onLocationUpdate(${location.latitude},${location.longitude},$acc)",
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
                    // Auto-start discovery so we can receive alerts from nearby phones
                    if (wifiP2pEnabled) {
                        startP2PDiscoveryInternal()
                        restartServiceDiscovery()
                    }
                    // Tell JS so the UI can warn the user (WiFi off = relay impossible)
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onP2PStateChanged && window.onP2PStateChanged($wifiP2pEnabled)",
                            null
                        )
                    }
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
                        // connect() pops an invitation dialog on the other phone, so it is
                        // only a secondary transport: attempt it solely when we actually
                        // have a message to deliver. Alerts travel via DNS-SD regardless.
                        val hasPending = synchronized(pendingRelayMessages) { pendingRelayMessages.isNotEmpty() }
                        if (hasPending && peers.deviceList.isNotEmpty() && groupOwnerAddress == null) {
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

                            if (!isGroupOwner) {
                                // Client: send pending messages to GO AND pull any from GO
                                val snapshot = synchronized(pendingRelayMessages) {
                                    val s = pendingRelayMessages.toList()
                                    pendingRelayMessages.clear()
                                    s
                                }
                                if (snapshot.isNotEmpty()) {
                                    snapshot.forEach { msg -> sendRelayToPeer(address, msg) }
                                } else {
                                    // No messages to send — pull any emergency alerts from GO
                                    sendRelayToPeer(address, "")
                                }
                            }
                            // If GO: pending messages will be pushed to clients when they connect

                            val safeAddr = address.replace("'", "")
                            runOnUiThread {
                                webView.evaluateJavascript(
                                    "window.onP2PConnected && window.onP2PConnected({isOwner:$isGroupOwner,address:'$safeAddr'})",
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
                            val writer = OutputStreamWriter(client.getOutputStream())

                            // Read incoming message (blank = pull-only from client)
                            val line = reader.readLine()
                            if (!line.isNullOrBlank()) processRelayMessage(line)

                            // Push any pending relay messages back to the connecting peer
                            // This handles the case where WE are the GO with an emergency
                            val pending = synchronized(pendingRelayMessages) { pendingRelayMessages.toList() }
                            pending.forEach { msg -> writer.write(msg + "\n") }
                            writer.flush()
                        } catch (e: Exception) {
                            Log.e("MARSEL_RELAY", "Client handler error: ${e.message}")
                        } finally {
                            try { client.close() } catch (ex: Exception) { Log.v(TAG, "close: ${ex.message}") }
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
                logToJs("RELAY", "DROP: no messageId in packet")
                return
            }
            val msgType = extractJsonString(json, "type") ?: "?"

            // Duplicate prevention
            synchronized(processedMessageIds) {
                if (processedMessageIds.contains(messageId)) {
                    logToJs("RELAY", "DUP $msgType $messageId — dropped")
                    return
                }
                processedMessageIds.add(messageId)
                if (processedMessageIds.size > 1000) {
                    val iter = processedMessageIds.iterator()
                    repeat(100) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                }
            }

            logToJs("RELAY", "RECV $msgType id=$messageId")

            // Notify JS to display on map (use escapeJs for safe double-quoted string)
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onRelayMessageReceived && window.onRelayMessageReceived(${escapeJs(json)})",
                    null
                )
            }

            val hopCount = extractJsonInt(json, "hopCount") ?: 0
            val maxHops = extractJsonInt(json, "maxHops") ?: 10

            if (hopCount >= maxHops) {
                logToJs("RELAY", "DROP $messageId: maxHops=$maxHops reached")
                return
            }

            // Show system notification only for actual emergency (not position/resolved)
            if (msgType == "MARSEL_EMERGENCY") {
                val pseudo = extractJsonString(json, "pseudo") ?: "Utilisateur"
                logToJs("RELAY", "NOTIF: showing system notification for $pseudo")
                showNotificationDirect("🚨 Alerte Marsel", "$pseudo a declenche une alerte d'urgence a proximite")
            }

            val netType = getNetworkTypeDirect()
            if (netType == "WIFI" || netType == "MOBILE") {
                // Only forward to contacts via SMS for actual emergency alerts
                // (not for position updates or resolved messages — that would spam contacts)
                if (msgType == "MARSEL_EMERGENCY") {
                    forwardEmergencyToContacts(json)
                }
            } else {
                // No internet — increment hop and relay further
                // Use a pattern that tolerates spaces around the colon (robustness)
                val updatedJson = """"hopCount"\s*:\s*$hopCount""".toRegex()
                    .replace(json, "\"hopCount\":${hopCount + 1}")
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
            var socket: Socket? = null
            try {
                socket = Socket()
                // Explicit connect timeout prevents indefinite thread block on unreachable hosts
                socket.connect(java.net.InetSocketAddress(address, 8890), 4000)
                socket.soTimeout = 10_000
                val writer = OutputStreamWriter(socket.getOutputStream())
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Send our message (empty string = pull-only)
                writer.write(messageJson + "\n")
                writer.flush()

                // Read any relay messages the peer (GO) sends back
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) processRelayMessage(line)
                    line = reader.readLine()
                }
                Log.d("MARSEL_RELAY", "Relay exchange with $address complete")
            } catch (e: Exception) {
                Log.e("MARSEL_RELAY", "Relay to $address failed: ${e.message}")
            } finally {
                try { socket?.close() } catch (ex: Exception) { Log.v(TAG, "close: ${ex.message}") }
            }
        }
    }

    // =========================================================================
    // Connect to a WiFi P2P peer
    // =========================================================================
    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0  // prefer client role so we reliably send to GO's relay server
        }
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
    // P2P discovery — ONLY via discoverServices (which calls discoverPeers
    // internally). Running both simultaneously causes BUSY (reason=2) errors
    // that silently kill the DNS-SD TXT record delivery.
    // =========================================================================
    private fun startP2PDiscoveryInternal() {
        // Delegate entirely to restartServiceDiscovery() which runs discoverServices.
        // discoverServices already triggers peer discovery and fires
        // WIFI_P2P_PEERS_CHANGED_ACTION — calling discoverPeers on top of it
        // causes resource contention on the P2P channel.
        restartServiceDiscovery()
    }

    // =========================================================================
    // DNS-SD connection-less relay transport
    // -------------------------------------------------------------------------
    // WifiP2pManager.connect() between two unpaired phones shows a system
    // invitation dialog that the OTHER user must accept — unusable for an
    // emergency alert. DNS-SD service discovery broadcasts the emergency packet
    // inside a TXT record that every nearby Marsel phone receives passively:
    // no connection, no pairing, no user action.
    // =========================================================================

    private fun setupDnsSdListeners() {
        wifiP2pManager.setDnsSdResponseListeners(
            wifiP2pChannel,
            { instanceName, _, device ->
                Log.d(TAG, "DNS-SD service found: $instanceName from ${device.deviceName}")
            },
            { fullDomain, txtRecord, device ->
                if (fullDomain.contains("marsel", ignoreCase = true)) {
                    Log.d(TAG, "Marsel TXT record from ${device.deviceName}: $txtRecord")
                    handleServiceTxtRecord(txtRecord)
                }
            }
        )
    }

    private fun handleServiceTxtRecord(record: Map<String, String?>) {
        try {
            val msgId = record["i"] ?: run { logToJs("DNS-SD", "TXT record missing 'i' field"); return }
            val shortType = record["y"] ?: run { logToJs("DNS-SD", "TXT record missing 'y' field"); return }
            val type = when (shortType) {
                "E" -> "MARSEL_EMERGENCY"
                "P" -> "MARSEL_POSITION_UPDATE"
                "R" -> "MARSEL_EMERGENCY_RESOLVED"
                else -> { logToJs("DNS-SD", "TXT record unknown type: $shortType"); return }
            }
            val pseudo = (record["p"] ?: "Utilisateur").replace("\\", "").replace("\"", "")
            val lat = record["a"]?.toDoubleOrNull() ?: run { logToJs("DNS-SD", "TXT record missing lat"); return }
            val lng = record["o"]?.toDoubleOrNull() ?: run { logToJs("DNS-SD", "TXT record missing lng"); return }
            val ts = record["s"]?.toLongOrNull() ?: System.currentTimeMillis()
            val hop = record["h"]?.toIntOrNull() ?: 0
            val emergencyId = record["e"] ?: msgId

            // Décode le champ "c" : numéros mobiles séparés par virgule pour la chaîne relay hors-ligne.
            // Le Téléphone B (avec réseau) les utilise pour envoyer SMS aux contacts du Téléphone A
            // (ex. Téléphone C) même quand A n'a pas d'internet.
            val contactsJson = record["c"]?.takeIf { it.isNotEmpty() }?.let { encoded ->
                encoded.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(",", "[", "]") { num ->
                        val safe = num.replace("\\", "\\\\").replace("\"", "\\\"")
                        """{"mobile":"$safe"}"""
                    }
            } ?: "[]"

            val hasContacts = contactsJson != "[]"
            logToJs("DNS-SD", "RX TXT type=$type pseudo=$pseudo lat=$lat hop=$hop contacts=${if (hasContacts) "yes" else "none"}")

            // Rebuild a full packet for processRelayMessage / the JS layer.
            val json = """{"type":"$type","messageId":"$msgId","id":"$emergencyId","emergencyId":"$emergencyId","pseudo":"$pseudo","lat":$lat,"lng":$lng,"timestamp":$ts,"hopCount":$hop,"maxHops":10,"contacts":$contactsJson}"""
            processRelayMessage(json)
        } catch (e: Exception) {
            logToJs("DNS-SD", "handleServiceTxtRecord ERROR: ${e.message}")
        }
    }

    // Builds a compact TXT record map from a JSON relay packet.
    private fun buildTxtRecord(shortType: String, json: String): Map<String, String>? {
        val msgId = extractJsonString(json, "messageId") ?: return null
        val emergencyId = extractJsonString(json, "emergencyId")
            ?: extractJsonString(json, "id") ?: msgId
        val pseudo = (extractJsonString(json, "pseudo") ?: "").take(24)
        val lat = extractJsonNumber(json, "lat") ?: return null
        val lng = extractJsonNumber(json, "lng") ?: return null
        val ts = extractJsonNumber(json, "timestamp")?.toLong() ?: System.currentTimeMillis()
        val hop = extractJsonInt(json, "hopCount") ?: 0

        // Extrait jusqu'à 5 numéros mobiles pour la chaîne relay hors-ligne (clé "c")
        val contactMobiles = """"mobile"\s*:\s*"([^"]+)"""".toRegex()
            .findAll(json)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { it.isNotEmpty() }
            .take(5)
            .joinToString(",")

        val record = mutableMapOf(
            "y" to shortType,
            "i" to msgId.take(40),
            "e" to emergencyId.take(40),
            "p" to pseudo,
            "a" to String.format(java.util.Locale.US, "%.5f", lat),
            "o" to String.format(java.util.Locale.US, "%.5f", lng),
            "s" to ts.toString(),
            "h" to hop.toString()
        )
        if (contactMobiles.isNotEmpty()) {
            record["c"] = contactMobiles.take(240)
        }
        return record
    }

    private fun broadcastViaService(json: String) {
        if (!wifiP2pEnabled) {
            logToJs("DNS-SD", "WiFi P2P disabled — cannot broadcast")
            return
        }
        try {
            val shortType = when (extractJsonString(json, "type")) {
                "MARSEL_EMERGENCY" -> "E"
                "MARSEL_POSITION_UPDATE" -> "P"
                "MARSEL_EMERGENCY_RESOLVED" -> "R"
                else -> { logToJs("DNS-SD", "Unknown type, skip"); return }
            }
            val record = buildTxtRecord(shortType, json) ?: run {
                logToJs("DNS-SD", "buildTxtRecord failed for $shortType")
                return
            }

            // Must run on main thread — WifiP2pManager is not thread-safe
            runOnUiThread {
                when (shortType) {
                    "E" -> {
                        // New emergency: clear everything, register fresh alert service.
                        activeEmergencyRecord = record
                        activePositionRecord = null
                        activeAlertServiceInfo = null
                        activePositionServiceInfo = null
                        lastPositionBroadcastMs = 0
                        logToJs("DNS-SD", "EMERGENCY: registering alert service")
                        val info = WifiP2pDnsSdServiceInfo.newInstance("marsel-alert", "_marsel._tcp", record)
                        wifiP2pManager.clearLocalServices(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { registerAlertService(info) }
                            override fun onFailure(r: Int) { registerAlertService(info) }
                        })
                    }
                    "P" -> {
                        // Position update: NEVER touch the alert service.
                        // Use removeLocalService on the OLD position service then addLocalService
                        // for the new one. This keeps marsel-alert always visible.
                        if (activeEmergencyRecord == null) {
                            logToJs("DNS-SD", "POSITION: no active emergency, skip")
                            return@runOnUiThread
                        }
                        // Rate-limit: skip if < 8s since last broadcast
                        val now = System.currentTimeMillis()
                        if (now - lastPositionBroadcastMs < 8_000) {
                            logToJs("DNS-SD", "POSITION: rate-limited, skip (${now - lastPositionBroadcastMs}ms)")
                            return@runOnUiThread
                        }
                        lastPositionBroadcastMs = now
                        activePositionRecord = record
                        val newPosInfo = WifiP2pDnsSdServiceInfo.newInstance("marsel-pos", "_marsel._tcp", record)
                        val oldPosInfo = activePositionServiceInfo
                        activePositionServiceInfo = newPosInfo
                        if (oldPosInfo != null) {
                            try {
                                wifiP2pManager.removeLocalService(wifiP2pChannel, oldPosInfo, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() { addPosServiceSafe(newPosInfo) }
                                    override fun onFailure(r: Int) {
                                        logToJs("DNS-SD", "removeLocalService pos failed: $r — adding anyway")
                                        addPosServiceSafe(newPosInfo)
                                    }
                                })
                            } catch (ex: SecurityException) {
                                logToJs("DNS-SD", "removeLocalService denied: ${ex.message}")
                                addPosServiceSafe(newPosInfo)
                            }
                        } else {
                            addPosServiceSafe(newPosInfo)
                        }
                        logToJs("DNS-SD", "POSITION: updating marsel-pos (alert untouched)")
                    }
                    "R" -> {
                        // Emergency resolved: stop all active services, broadcast R for 2 min
                        activeEmergencyRecord = null
                        activePositionRecord = null
                        activeAlertServiceInfo = null
                        activePositionServiceInfo = null
                        logToJs("DNS-SD", "RESOLVED: clearing emergency services, broadcasting resolved")
                        val resolvedInfo = WifiP2pDnsSdServiceInfo.newInstance(
                            "marsel-resolved", "_marsel._tcp", record
                        )
                        wifiP2pManager.clearLocalServices(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                try {
                                    wifiP2pManager.addLocalService(wifiP2pChannel, resolvedInfo, object : WifiP2pManager.ActionListener {
                                        override fun onSuccess() { logToJs("DNS-SD", "Resolved service broadcasting OK") }
                                        override fun onFailure(r: Int) { logToJs("DNS-SD", "Resolved service broadcast FAILED: $r") }
                                    })
                                } catch (e: SecurityException) {
                                    logToJs("DNS-SD", "addLocalService denied: ${e.message}")
                                }
                            }
                            override fun onFailure(reason: Int) { logToJs("DNS-SD", "clearLocalServices failed: $reason") }
                        })
                        p2pHandler.postDelayed({
                            try { wifiP2pManager.clearLocalServices(wifiP2pChannel, null) } catch (e: Exception) {
                                Log.w(TAG, "clearLocalServices resolved: ${e.message}")
                            }
                        }, 120_000)
                    }
                }
            }
        } catch (e: SecurityException) {
            logToJs("DNS-SD", "broadcastViaService DENIED (NEARBY_WIFI_DEVICES?): ${e.message}")
        } catch (e: Exception) {
            logToJs("DNS-SD", "broadcastViaService error: ${e.message}")
        }
    }

    // Register the emergency alert service (called once on EMERGENCY, after clearLocalServices).
    private fun registerAlertService(info: WifiP2pDnsSdServiceInfo) {
        try {
            wifiP2pManager.addLocalService(wifiP2pChannel, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    activeAlertServiceInfo = info
                    logToJs("DNS-SD", "marsel-alert registered ✓ — broadcasting continuously")
                }
                override fun onFailure(r: Int) { logToJs("DNS-SD", "marsel-alert register FAILED: $r") }
            })
        } catch (ex: SecurityException) {
            logToJs("DNS-SD", "registerAlertService denied: ${ex.message}")
        }
    }

    // Add a position service without touching the alert service.
    private fun addPosServiceSafe(info: WifiP2pDnsSdServiceInfo) {
        try {
            wifiP2pManager.addLocalService(wifiP2pChannel, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { logToJs("DNS-SD", "marsel-pos registered ✓") }
                override fun onFailure(r: Int) { logToJs("DNS-SD", "marsel-pos register FAILED: $r") }
            })
        } catch (ex: SecurityException) {
            logToJs("DNS-SD", "addPosServiceSafe denied: ${ex.message}")
        }
    }

    private fun restartServiceDiscovery() {
        if (!wifiP2pEnabled) return
        try {
            wifiP2pManager.clearServiceRequests(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { addServiceRequestAndDiscover() }
                override fun onFailure(reason: Int) { addServiceRequestAndDiscover() }
            })
        } catch (e: Exception) {
            Log.e(TAG, "restartServiceDiscovery: ${e.message}")
        }
    }

    private fun addServiceRequestAndDiscover() {
        try {
            val request = WifiP2pDnsSdServiceRequest.newInstance()
            wifiP2pManager.addServiceRequest(wifiP2pChannel, request, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    try {
                        wifiP2pManager.discoverServices(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { Log.d(TAG, "DNS-SD discovery running") }
                            override fun onFailure(reason: Int) { Log.w(TAG, "discoverServices failed: reason=$reason") }
                        })
                    } catch (e: SecurityException) {
                        Log.e(TAG, "discoverServices denied (NEARBY_WIFI_DEVICES?): ${e.message}")
                    }
                }
                override fun onFailure(reason: Int) { Log.w(TAG, "addServiceRequest failed: reason=$reason") }
            })
        } catch (e: Exception) {
            Log.e(TAG, "addServiceRequestAndDiscover: ${e.message}")
        }
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

    private fun extractJsonNumber(json: String, key: String): Double? {
        val pattern = """"$key"\s*:\s*(-?\d+(?:\.\d+)?)""".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    // =========================================================================
    // In-app log relay (sends log lines to the JS debug overlay)
    // =========================================================================
    private fun logToJs(tag: String, msg: String) {
        Log.d("MARSEL_$tag", msg)
        val escaped = msg.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
            .replace("\r", "")
        runOnUiThread {
            webView.evaluateJavascript(
                "window.marselLog && window.marselLog('K','$tag','$escaped')",
                null
            )
        }
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
            if (smsManager == null) {
                Log.e("MARSEL_SMS", "SmsManager not available on this device")
                return
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

        @JavascriptInterface
        fun hasSMSPermission(): Boolean {
            return ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED
        }

        // -----------------------------------------------------------------------
        // WiFi Direct relay
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun sendEmergencyViaRelay(emergencyJson: String) {
            thread {
                try {
                    val t = extractJsonString(emergencyJson, "type") ?: "?"
                    logToJs("RELAY", "SEND $t via DNS-SD + socket transport")

                    // PRIMARY transport: connection-less DNS-SD broadcast.
                    broadcastViaService(emergencyJson)
                    restartServiceDiscovery()

                    // Ensure peer discovery is also running (secondary socket transport)
                    startP2PDiscovery()

                    synchronized(pendingRelayMessages) {
                        pendingRelayMessages.add(emergencyJson)
                        // Position updates arrive every 10s — cap the queue so a long
                        // emergency doesn't grow it unboundedly (keep most recent)
                        while (pendingRelayMessages.size > 50) pendingRelayMessages.removeAt(0)
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
            startP2PDiscoveryInternal()
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
