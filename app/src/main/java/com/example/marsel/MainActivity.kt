package com.example.marsel

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.content.ContentValues
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import kotlin.concurrent.thread

/**
 * Pair Marsel confirmé : appareil vu via DNS-SD sur _marsel._tcp.
 * Seuls ces appareils peuvent recevoir un connect() ou apparaître dans l'UI.
 */
data class MarselPeer(
    val deviceAddress: String,
    val deviceName: String,
    @Volatile var lastSeenMs: Long
)

// FragmentActivity (superset de ComponentActivity) requis par BiometricPrompt
// (TODO.md §1.2) — androidx.biometric n'a pas d'API pour ComponentActivity nu.
class MainActivity : FragmentActivity() {

    // -------------------------------------------------------------------------
    // Core WebView
    // -------------------------------------------------------------------------
    private lateinit var webView: WebView
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false

    // Audio recording (Section 5) — emplacement pérenne, segments de 5 min.
    // Un crash entre deux segments ne rend jamais l'enregistrement illisible :
    // chaque segment est finalisé (moov écrit) avant de démarrer le suivant.
    private var audioActive = false
    private var audioSegmentIndex = 0
    private var audioBaseName = ""              // marsel_alerte_yyyyMMdd_HHmm
    private var currentAudioUri: Uri? = null    // API29+ : item MediaStore en attente
    private var currentAudioFile: File? = null  // API<29 : fichier public Music/Marsel
    private var currentAudioPfd: ParcelFileDescriptor? = null
    private var lastFinalizedAudioFile: File? = null  // dernier segment (API<29) pour MMS

    // -------------------------------------------------------------------------
    // WiFi P2P fields
    // -------------------------------------------------------------------------
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var wifiP2pChannel: WifiP2pManager.Channel
    private lateinit var wifiP2pReceiver: WifiP2pBroadcastReceiver
    private val peerDevices = mutableListOf<WifiP2pDevice>()

    // Pairs Marsel CONFIRMÉS (vus via DNS-SD _marsel._tcp) — seul filtre autorisé
    // pour connectToPeer() et pour la liste envoyée à l'UI. Un pair non revu
    // depuis MARSEL_PEER_TTL_MS est expiré.
    private val marselPeers = ConcurrentHashMap<String, MarselPeer>()

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

    // A2 : runnable différé qui retire le service marsel-res après 2 min.
    // Tracké pour être annulé/exécuté immédiatement si une nouvelle urgence démarre.
    private var resolvedClearRunnable: Runnable? = null

    // C3/C4 : alertes RELAYÉES (urgences d'autres téléphones re-diffusées par nous).
    // Instances de service distinctes (marsel-alert-<suffix>) : elles coexistent
    // avec notre propre alerte sans jamais l'écraser. Le hop-forwarding est la
    // propriété EXCLUSIVE du Kotlin (le JS ne fait que l'affichage).
    private val relayedRecords = ConcurrentHashMap<String, Map<String, String>>()      // messageId → record
    private val relayedServiceInfos = ConcurrentHashMap<String, WifiP2pDnsSdServiceInfo>() // messageId → info
    private val relayedEmergencyIds = ConcurrentHashMap<String, String>()              // messageId → emergencyId

    // A3/3f : watchdog — timestamp du dernier événement DNS-SD reçu
    @Volatile private var lastDnsSdEventMs = System.currentTimeMillis()

    // A4/3a : une alerte distante active (E/P reçu non résolu) force le mode 8s
    @Volatile private var remoteAlertActiveUntilMs = 0L

    // E1/3c : dédup persistée (SharedPreferences), survit au restart de l'app
    private val dedupLedger by lazy {
        DedupLedger(object : DedupLedger.KeyValueStore {
            private val prefs = getSharedPreferences("marsel_dedup", MODE_PRIVATE)
            override fun all(): Map<String, Long> =
                prefs.all.entries.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
            override fun put(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
            override fun remove(key: String) { prefs.edit().remove(key).apply() }
        })
    }

    // D2 : références serveurs pour arrêt propre dans onDestroy
    @Volatile private var serversRunning = true
    private var relayServerSocket: ServerSocket? = null
    private var legacyServerSocket: ServerSocket? = null

    // ── Suivi d'envoi SMS réel (accusé système Android) ─────────────────
    // Le seul juge fiable de « le SMS est parti » est le sentIntent : SIM
    // prête + réseau affiché peuvent quand même aboutir à un échec radio.
    private val smsTrackCounter = java.util.concurrent.atomic.AtomicInteger(1000)
    private val handledTrackResults = mutableSetOf<String>()
    // Relais : envois en cours (origMessageId → true) et paquet source pour l'ACK
    private val smsInFlight = ConcurrentHashMap<String, Boolean>()
    private val pendingRelaySends = ConcurrentHashMap<String, String>()

    // BUG-3 : identifiants des paquets ÉMIS PAR CE TÉLÉPHONE. Le maillage nous
    // renvoie nos propres paquets en écho (relayés par les voisins) — sans ce
    // registre, on se notifiait de sa PROPRE alerte (vu en test terrain).
    private val ownMessageIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // BUG-5 : découverte déclenchée par événement PEERS_CHANGED (throttle 3s)
    @Volatile private var lastPeerTriggeredDiscoverMs = 0L

    // BUG « storm » : le broadcast sticky WIFI_P2P_STATE_CHANGED est re-délivré
    // à chaque onResume (chaque dialogue de permission) — ne réagir qu'aux
    // VRAIES transitions d'état.
    private var prevP2pEnabled: Boolean? = null

    // PERM-FIX (terrain) : sans NEARBY_WIFI_DEVICES (API 33+) ou FINE_LOCATION
    // (API < 33), TOUTES les opérations WiFi P2P échouent en reason=0 et le MRN
    // est silencieusement mort. On le détecte, on prévient l'UI (qui redemande
    // la permission) et on arrête de marteler le framework.
    @Volatile private var lastPermWarnMs = 0L
    @Volatile private var p2pBlockedNotified = false
    // Filet de sécurité : si reason=0 persiste ALORS QUE les permissions sont
    // OK, le channel est gelé → on le recrée après 6 échecs consécutifs.
    private val consecutiveP2pErrors = java.util.concurrent.atomic.AtomicInteger(0)

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val trackId = intent.getStringExtra("trackId") ?: return
            val ok = resultCode == android.app.Activity.RESULT_OK
            // Multipart = plusieurs callbacks pour le même trackId : le premier
            // résultat fait verdict (les parties suivantes suivent la même radio).
            synchronized(handledTrackResults) {
                if (!handledTrackResults.add(trackId)) return
                if (handledTrackResults.size > 500) handledTrackResults.clear()
            }
            onSmsSendResult(trackId, ok, resultCode)
        }
    }

    // DNS-SD connection-less relay: re-arm périodique adaptatif (A4/3a)
    // 8s quand une alerte est active (émise OU reçue non résolue), 25s en veille.
    private val p2pHandler = Handler(Looper.getMainLooper())
    private val rediscoverRunnable = object : Runnable {
        override fun run() {
            purgeExpiredMarselPeers()
            purgeExpiredRelayedServices()
            // BUG-4 : si le watchdog lance un recovery (clear→add→discover
            // asynchrone), NE PAS lancer restartServiceDiscovery en parallèle —
            // la collision produisait des « discoverServices ÉCHEC reason=3 »
            // (requête effacée pendant le vol) et des cycles perdus de 8-25s.
            val recoveryLaunched = watchdogCheck()
            if (!recoveryLaunched) restartServiceDiscovery()
            // Veille 12s (au lieu de 25s) : discoverServices est ponctuel, la
            // latence de détection ≈ l'intervalle du récepteur. 12s + trigger
            // PEERS_CHANGED = détection typique < 10s pour un coût batterie modéré.
            // SOCKET-FIRST : groupe P2P connecté → le socket TCP est le canal
            // principal ; on espace le DNS-SD (20s) pour ne pas déstabiliser la
            // liaison (le scan off-channel agressif aggravait le flapping
            // DISABLED→ENABLED observé sur Samsung toutes les 20-40s).
            val interval = when {
                groupOwnerAddress != null -> 20_000L
                isAlertActive() -> 8_000L
                else -> 12_000L
            }
            p2pHandler.postDelayed(this, interval)
        }
    }

    // SOCKET-FIRST (terrain, flapping) : tant que le groupe P2P est connecté et
    // qu'on est CLIENT, on tire (pull) les messages du GO par socket toutes les
    // 10s — canal bien plus fiable que le DNS-SD pendant une connexion. Le
    // serveur pousse déjà tous ses pendingRelayMessages à chaque connexion
    // entrante et la déduplication (DedupLedger) absorbe les répétitions.
    // attempts=1 : un poll raté n'est pas grave, le suivant arrive dans 10s.
    private val socketPollRunnable = object : Runnable {
        override fun run() {
            val addr = groupOwnerAddress
            if (addr == null || isGroupOwner) return  // groupe perdu ou on est GO
            sendRelayToPeer(addr, "", attempts = 1)
            p2pHandler.postDelayed(this, 10_000L)
        }
    }

    private fun isAlertActive(): Boolean =
        activeEmergencyRecord != null ||
            relayedRecords.isNotEmpty() ||
            System.currentTimeMillis() < remoteAlertActiveUntilMs

    // Bascule veille→actif immédiate (3a) : relance le cycle sans attendre
    private fun kickDiscoveryNow() {
        p2pHandler.removeCallbacks(rediscoverRunnable)
        p2pHandler.post(rediscoverRunnable)
    }

    // -------------------------------------------------------------------------
    // Location fields
    // -------------------------------------------------------------------------
    private lateinit var locationManager: LocationManager
    // Chantier confidentialité (TODO.md §1) : auth locale + chiffrement au
    // repos, jamais de compte serveur ni de connexion tierce.
    private lateinit var secureStorage: SecureStore
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
        private const val MARSEL_PEER_TTL_MS = 60_000L
        private const val AUDIO_SEGMENT_MS = 5 * 60 * 1000       // rotation 5 min
        private const val AUDIO_MMS_MAX_BYTES = 1_000_000L       // 1 Mo (spec 5b)
        private const val AUDIO_DIR = "Marsel"
        private const val SMS_SENT_ACTION = "com.example.marsel.SMS_SENT"
    }

    // -------------------------------------------------------------------------
    // Permission launcher — flow d'onboarding groupe par groupe (Section 6)
    // -------------------------------------------------------------------------
    private var pendingPermGroup: String = ""
    private var pendingPermList: List<String> = emptyList()

    private val permissionGroupLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val group = pendingPermGroup
        val list = pendingPermList
        val granted = list.isNotEmpty() && list.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        // Refus définitif : non accordé ET plus de rationale à montrer (G1/G2)
        val permanentlyDenied = list.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(it)
        }
        if (granted && (group == "location")) initLocationManager()
        // PERM-FIX : permission proximité tout juste accordée → relancer le MRN
        // immédiatement (re-arm complet + ré-enregistrement des services actifs).
        if (granted && group == "nearby") {
            lastServiceRequestResetMs = 0
            reRegisterActiveServices()
            kickDiscoveryNow()
        }
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onPermissionResult && window.onPermissionResult('$group',$granted,$permanentlyDenied)",
                null
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Mappe un nom de groupe → permissions runtime applicables à l'OS courant.
    private fun permissionsForGroup(group: String): List<String> = when (group) {
        "location" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        "nearby" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyList()
        "sms" -> listOf(Manifest.permission.SEND_SMS)
        "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        "microphone" -> listOf(Manifest.permission.RECORD_AUDIO)
        "camera" -> listOf(Manifest.permission.CAMERA)
        "background_location" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList()
        else -> emptyList()
    }

    private fun launchPermissionGroup(group: String) {
        val perms = permissionsForGroup(group)
        // Groupe sans permission applicable sur cet OS (ex. notifications < API33)
        // ou déjà accordé → succès immédiat, l'onboarding avance.
        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (perms.isEmpty() || notGranted.isEmpty()) {
            webView.evaluateJavascript(
                "window.onPermissionResult && window.onPermissionResult('$group',true,false)", null
            )
            return
        }
        // G1 : background location seulement si la localisation fine est accordée
        if (group == "background_location" && !hasLocationPermission()) {
            webView.evaluateJavascript(
                "window.onPermissionResult && window.onPermissionResult('$group',false,false)", null
            )
            return
        }
        pendingPermGroup = group
        pendingPermList = perms
        permissionGroupLauncher.launch(notGranted.toTypedArray())
    }

    // =========================================================================
    // onCreate
    // =========================================================================
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CRASH-FIX (structurel) : TOUS les champs lateinit sont assignés en
        // premier, avant toute logique susceptible de les utiliser (y compris
        // des callbacks asynchrones comme le ChannelListener WiFi P2P installé
        // plus bas). Bug réel corrigé ici : initLocationManager() était
        // auparavant appelée plus haut dans cette méthode, AVANT l'assignation
        // de `locationManager` — chez tout utilisateur ayant déjà accordé la
        // permission localisation lors d'une session précédente (donc à CHAQUE
        // réouverture suivante), cela levait une UninitializedPropertyAccessException
        // non rattrapée (le try/catch interne ne couvre que SecurityException)
        // → crash garanti à l'ouverture. « Vider le cache » revoke la permission
        // au niveau système, ce qui évitait temporairement le chemin fautif —
        // d'où le cycle « crash → vider le cache → ça remarche → re-crash ».
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        webView = WebView(this)
        secureStorage = SecureStore(this)

        // --- Notification channel (must be created before any notification) ---
        createNotificationChannel()

        // --- Accusés d'envoi SMS (verdict réel du système) ---
        val smsFilter = IntentFilter(SMS_SENT_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, smsFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsSentReceiver, smsFilter)
        }

        // Section 6 : les permissions ne sont PLUS demandées en un bloc ici.
        // Le flow d'onboarding JS (window) les demande groupe par groupe, dans
        // l'ordre, avec explication, via MarselBridge.requestPermissionGroup().
        // ACCESS_BACKGROUND_LOCATION est demandée séparément et en dernier (G1) ;
        // plus aucune permission Bluetooth (G3).

        // --- WiFi P2P setup ---
        // A1/3e : ChannelListener obligatoire — un channel mort sans listener
        // rend le relay sourd ET muet en silence jusqu'au restart de l'app.
        // webView et locationManager sont déjà assignés ci-dessus : ce callback
        // peut désormais s'exécuter sans risque, à tout moment.
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        wifiP2pChannel = wifiP2pManager.initialize(this, mainLooper) { onChannelLost() }
        wifiP2pReceiver = WifiP2pBroadcastReceiver()

        // DNS-SD listeners must be attached once before any discoverServices call.
        // This is the primary relay transport: TXT records are received from nearby
        // phones without connection, pairing, or any user dialog.
        setupDnsSdListeners()
        p2pHandler.postDelayed(rediscoverRunnable, 3_000)

        // --- Location manager : seed avec la dernière position connue si la
        // permission est déjà accordée (utilisateur existant). Sans effet et
        // sans risque si elle ne l'est pas encore (vérifiée en interne). ---
        initLocationManager()

        // --- WebView (configuration — l'instance existe déjà) ---
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
        // D2 : arrêt propre des serveurs — sinon la recréation d'activité
        // laisse un serveur zombie lié à l'ancienne instance (BindException
        // silencieuse pour le nouveau, fuite mémoire pour l'ancien).
        serversRunning = false
        try { relayServerSocket?.close() } catch (e: Exception) { Log.v(TAG, "close relay server: ${e.message}") }
        try { legacyServerSocket?.close() } catch (e: Exception) { Log.v(TAG, "close legacy server: ${e.message}") }
        try { unregisterReceiver(smsSentReceiver) } catch (e: Exception) { Log.v(TAG, "unregister sms receiver: ${e.message}") }
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) cameraManager.setTorchMode(cameraId, false)
        } catch (e: Exception) { /* ignore */ }
        // 5c/D2 : recréation d'activité pendant l'enregistrement — finaliser
        // le segment courant pour qu'il reste lisible (moov écrit), sans perdre
        // le fichier. audioActive reste false ici (l'instance est détruite) ;
        // le JS relancera l'enregistrement si l'alerte est toujours active.
        try {
            if (audioActive || mediaRecorder != null) {
                audioActive = false
                finalizeCurrentSegment()
            }
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

        // I1 : retirer les listeners précédents avant d'en enregistrer de nouveaux,
        // sinon chaque onResume empile un LocationListener → N callbacks par fix GPS.
        stopLocationUpdatesInternal()

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
                    // Le broadcast est STICKY : re-délivré à chaque re-register du
                    // receiver (chaque onResume/dialogue de permission). Ne faire le
                    // recovery complet que sur une VRAIE transition d'état — sinon
                    // tempête de ré-enregistrements/kicks (vue pendant l'onboarding).
                    val isTransition = prevP2pEnabled != wifiP2pEnabled
                    prevP2pEnabled = wifiP2pEnabled
                    if (wifiP2pEnabled && isTransition) {
                        // TEST-FIX : un rebond DISABLED→ENABLED du framework P2P
                        // EFFACE les services locaux — sans ré-enregistrement,
                        // l'alerte cesse d'émettre en silence.
                        logToJs("P2P", "P2P ré-activé — ré-enregistrement des services actifs")
                        lastServiceRequestResetMs = 0  // force un re-arm complet
                        reRegisterActiveServices()
                        kickDiscoveryNow()
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
                    // BUG-5 + CACHE-FIX : quand un téléphone commence à émettre,
                    // le framework nous délivre PEERS_CHANGED. On force alors un
                    // re-arm COMPLET (clear→add→discover) — un simple discover
                    // réutiliserait la requête existante et serait servi depuis
                    // le cache, sans voir le nouveau service. Throttle 5s.
                    val nowPeers = System.currentTimeMillis()
                    if (nowPeers - lastPeerTriggeredDiscoverMs > 5_000) {
                        lastPeerTriggeredDiscoverMs = nowPeers
                        // SOCKET-FIRST : pendant une connexion de groupe, les
                        // événements PEERS_CHANGED sont fréquents (négociation,
                        // heartbeats) — forcer un re-arm complet à chaque fois
                        // détruirait la requête en vol et fragiliserait le lien.
                        // Le socket transporte déjà les messages ; on garde le
                        // re-arm forcé pour le mode déconnecté uniquement.
                        if (groupOwnerAddress == null) {
                            lastServiceRequestResetMs = 0  // force le re-arm complet
                        }
                        restartServiceDiscovery()
                    }
                    wifiP2pManager.requestPeers(wifiP2pChannel) { peers ->
                        synchronized(peerDevices) {
                            peerDevices.clear()
                            peerDevices.addAll(peers.deviceList)
                        }
                        // L'UI ne reçoit QUE les pairs Marsel confirmés via DNS-SD,
                        // jamais la liste brute (TV, imprimantes, téléphones sans Marsel).
                        notifyMarselPeersToJs()

                        // connect() pops an invitation dialog on the other phone, so it is
                        // only a secondary transport: attempt it solely when we actually
                        // have a message to deliver. Alerts travel via DNS-SD regardless.
                        // FILTRAGE STRICT : jamais de connect() vers un appareil absent
                        // de marselPeers — un appareil non-Marsel ne répondra jamais et
                        // bloque le framework P2P (BUSY) pendant toute la négociation.
                        val hasPending = synchronized(pendingRelayMessages) { pendingRelayMessages.isNotEmpty() }
                        if (hasPending && groupOwnerAddress == null) {
                            val target = peers.deviceList.firstOrNull { marselPeers.containsKey(it.deviceAddress) }
                            if (target != null) {
                                connectToPeer(target)
                            } else if (peers.deviceList.isNotEmpty()) {
                                Log.d(TAG, "Peers visibles (${peers.deviceList.size}) mais aucun pair Marsel confirmé — pas de connect()")
                            }
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
                                // SOCKET-FIRST : polling périodique du GO (10s)
                                // tant que le groupe tient — canal principal
                                // pendant la connexion, le DNS-SD passe en retrait.
                                p2pHandler.removeCallbacks(socketPollRunnable)
                                p2pHandler.postDelayed(socketPollRunnable, 10_000L)
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
                        p2pHandler.removeCallbacks(socketPollRunnable)
                        Log.d(TAG, "P2P disconnected")
                        // Retour au mode déconnecté : reprendre immédiatement le
                        // rythme DNS-SD normal (le cycle courant était espacé à 20s).
                        kickDiscoveryNow()
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
                relayServerSocket = server
                Log.d(TAG, "Relay server listening on port 8890")
                while (serversRunning) {
                    val client = try { server.accept() } catch (e: Exception) { break }
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
            val hopCount = extractJsonInt(json, "hopCount") ?: 0
            val maxHops = extractJsonInt(json, "maxHops") ?: MarselProtocol.MAX_HOPS

            // AUDIT-FIX 1 : les paquets F/T (demande de SMS relayé) sont dispatchés
            // AVANT toute déduplication. Sinon un téléphone qui voit la demande
            // pendant qu'il est hors-réseau la marque comme « vue » et ne pourra
            // JAMAIS envoyer le SMS une fois le réseau revenu. handleSmsRequestPacket
            // porte sa propre idempotence (dédup « sms » à l'envoi + propagation unique).
            if (msgType == MarselProtocol.TYPE_RESOLVED_SMS_REQUEST ||
                msgType == MarselProtocol.TYPE_TIMEOUT_SMS_REQUEST
            ) {
                // BUG-3 : écho de NOTRE propre demande relayée par un voisin —
                // ne pas la traiter (on l'a déjà diffusée nous-mêmes).
                if (ownMessageIds.contains(messageId)) return
                handleSmsRequestPacket(json, msgType, messageId, hopCount, maxHops)
                return
            }

            // Dédup niveau 1 : set en RAM (rapide, durée de vie de la session)
            synchronized(processedMessageIds) {
                if (processedMessageIds.contains(messageId)) {
                    return  // silencieux : les TXT records actifs sont re-reçus à chaque cycle
                }
                processedMessageIds.add(messageId)
                if (processedMessageIds.size > 1000) {
                    val iter = processedMessageIds.iterator()
                    repeat(100) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                }
            }
            // Dédup niveau 2 (E1/3c) : persistée, survit à un restart de l'app
            // pendant une alerte — empêche re-notification et re-SMS.
            if (dedupLedger.checkAndMark("msg", messageId)) {
                logToJs("RELAY", "DUP (persisté) $msgType $messageId — dropped")
                return
            }

            logToJs("RELAY", "RECV $msgType id=$messageId")

            // ACK « SMS réellement envoyés » : informer le JS (l'émetteur d'origine
            // annule son timer d'échec et notifie l'utilisateur), puis propager
            // l'ACK plus loin pour qu'il remonte jusqu'à l'émetteur (max 5 sauts).
            if (msgType == MarselProtocol.TYPE_SMS_ACK) {
                // Écho de notre propre ACK relayé par un voisin : ignorer
                if (ownMessageIds.contains(messageId)) return
                val ackedId = extractJsonString(json, "emergencyId")
                    ?: extractJsonString(json, "id") ?: return
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onSmsRelayAck && window.onSmsRelayAck('${ackedId.replace("'", "")}')",
                        null
                    )
                }
                if (hopCount < maxHops) {
                    val ackFwd = """"hopCount"\s*:\s*$hopCount""".toRegex()
                        .replace(json, "\"hopCount\":${hopCount + 1}")
                    registerRelayedService(ackFwd)
                    synchronized(pendingRelayMessages) { pendingRelayMessages.add(ackFwd) }
                    groupOwnerAddress?.let { addr ->
                        if (!isGroupOwner) sendRelayToPeer(addr, ackFwd)
                    }
                }
                return
            }

            // TEST-FIX : ne jamais re-notifier une urgence DÉJÀ RÉSOLUE.
            // Vu en terrain : une vieille E rejouée par le canal socket après la
            // fin d'alerte déclenchait une nouvelle notification. On mémorise les
            // résolutions (dédup persistée « res ») et on ignore les E correspondantes.
            val emergencyKey = extractJsonString(json, "emergencyId")
                ?: extractJsonString(json, "id") ?: messageId
            if (msgType == MarselProtocol.TYPE_RESOLVED) {
                dedupLedger.checkAndMark("res", emergencyKey)
            }
            // BUG-6 : le filtre couvre aussi les POSITION — des paquets de position
            // périmés rejoués par le canal socket APRÈS la résolution recréaient
            // le marqueur sur la carte (tags fantômes vus en test).
            if ((msgType == MarselProtocol.TYPE_EMERGENCY || msgType == MarselProtocol.TYPE_POSITION) &&
                dedupLedger.isSeen("res", emergencyKey)
            ) {
                logToJs("RELAY", "$msgType $messageId ignoré : urgence déjà résolue")
                return
            }

            // Notify JS to display on map (use escapeJs for safe double-quoted string).
            // ANONYMISATION (TODO.md §2) : la WebView n'a jamais besoin du vrai
            // pseudo — même si le paquet natif le porte pour permettre l'envoi
            // du SMS relaySms="1" (entièrement natif, cf. forwardEmergencyToContacts).
            val displayJson = MarselProtocol.anonymizePseudoForDisplay(json)
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onRelayMessageReceived && window.onRelayMessageReceived(${escapeJs(displayJson)})",
                    null
                )
            }

            if (hopCount >= maxHops) {
                logToJs("RELAY", "DROP $messageId: maxHops=$maxHops reached")
                return
            }

            // Show system notification only for actual emergency (not position/resolved)
            if (msgType == MarselProtocol.TYPE_EMERGENCY) {
                // ANONYMISATION (TODO.md §2) : la notification « alerte à
                // proximité » est un affichage PUBLIC (tout utilisateur Marsel
                // à portée, pas seulement les proches) — elle utilise TOUJOURS
                // l'alias, même quand le paquet porte le vrai pseudo pour
                // permettre à forwardEmergencyToContacts() de SMS-er les
                // proches (relaySms="1"). Le vrai nom ne doit apparaître que
                // dans le SMS envoyé aux contacts déclarés, jamais ici.
                val emergencyId = extractJsonString(json, "emergencyId")
                    ?: extractJsonString(json, "id") ?: messageId
                val alias = MarselProtocol.deriveAlertAlias(emergencyId)
                logToJs("RELAY", "NOTIF: showing system notification for $alias")
                showNotificationDirect("🚨 Alerte Marsel", "$alias a été déclenchée à proximité")
            }

            val updatedJson = """"hopCount"\s*:\s*$hopCount""".toRegex()
                .replace(json, "\"hopCount\":${hopCount + 1}")

            // SMS aux proches : uniquement pour une vraie alerte, avec dédup
            // persistée smsHandled (F3) — le réseau est vérifié dans la fonction.
            if (msgType == MarselProtocol.TYPE_EMERGENCY) {
                forwardEmergencyToContacts(json)
            }

            // C4 : hop-forwarding — propriété EXCLUSIVE du Kotlin.
            // E et R sont re-diffusés en DNS-SD (instances distinctes, C3) pour
            // atteindre les téléphones hors de portée de l'émetteur.
            // P (position) ne fait qu'un seul hop DNS-SD : re-diffuser le tracking
            // de proche en proche saturerait le stack sans bénéfice.
            when (msgType) {
                MarselProtocol.TYPE_EMERGENCY, MarselProtocol.TYPE_RESOLVED -> {
                    registerRelayedService(updatedJson)
                }
            }

            // Canal socket secondaire (file + push vers le GO si connecté)
            synchronized(pendingRelayMessages) {
                pendingRelayMessages.add(updatedJson)
            }
            groupOwnerAddress?.let { addr ->
                if (!isGroupOwner) sendRelayToPeer(addr, updatedJson)
            }
        } catch (e: Exception) {
            Log.e("MARSEL_RELAY", "processRelayMessage: ${e.message}")
        }
    }

    // =========================================================================
    // Paquets F (fin d'alerte) / T (timeout 20 min) — SMS relayé silencieux
    // =========================================================================
    // Le téléphone relais avec réseau validé envoie les SMS À LA PLACE de
    // l'émetteur hors-ligne, puis s'arrête. Sans réseau, il propage plus loin.
    // Aucune UI, aucun historique : tout reste en natif.
    private fun handleSmsRequestPacket(
        json: String,
        msgType: String,
        messageId: String,
        hopCount: Int,
        maxHops: Int
    ) {
        val quality = detectNetworkQuality()
        // TEST-FIX : réseau validé ET SIM+réseau cellulaire — un relais en WiFi
        // sans SIM aurait marqué smsHandled sans jamais pouvoir envoyer.
        if (quality != "NONE" && canSendSmsDirect()) {
            // smsHandled n'est marqué qu'à l'accusé de SUCCÈS système ; en cas
            // d'échec radio, le paquet reste vivant pour un autre relais.
            if (dedupLedger.isSeen("sms", messageId)) {
                return
            }
            if (smsInFlight.putIfAbsent(messageId, true) != null) {
                return
            }
            val pseudo = extractJsonString(json, "pseudo") ?: "Utilisateur"
            val lat = extractJsonNumber(json, "lat")
            val lng = extractJsonNumber(json, "lng")
            val mapsLink = if (lat != null && lng != null) {
                "https://maps.google.com/?q=$lat,$lng"
            } else "Position inconnue"
            val audioMention = if (extractJsonString(json, "audio") == "1") {
                "\nUn enregistrement audio de l'alerte est disponible."
            } else ""
            val smsMsg = if (msgType == MarselProtocol.TYPE_TIMEOUT_SMS_REQUEST) {
                "⚠️ ALERTE MARSEL TOUJOURS EN COURS depuis 20 min. $pseudo n'a pas désactivé son alerte.\nPosition : $mapsLink" +
                    (if (extractJsonString(json, "audio") == "1") "\nUn enregistrement audio est en cours." else "")
            } else {
                "✅ FIN D'ALERTE MARSEL\n$pseudo est en sécurité.\nDernière position connue : $mapsLink$audioMention"
            }
            val mobiles = MarselProtocol.extractContactMobiles(json)
            if (mobiles.isEmpty()) { smsInFlight.remove(messageId); return }
            logToJs("RELAY", "SMS-REQ $msgType : envoi de ${mobiles.size} SMS à la place de l'émetteur")
            pendingRelaySends[messageId] = json
            mobiles.forEachIndexed { idx, mobile ->
                sendSMSDirect(mobile, smsMsg, if (idx == 0) "relay|$messageId" else null)
            }
        } else {
            // Pas de réseau validé ici non plus : propager plus loin.
            // AUDIT-FIX 1 : propagation UNIQUE. Le paquet F/T n'est pas dédupliqué
            // (pour permettre l'envoi si le réseau revient), donc il est re-délivré
            // à chaque cycle DNS-SD. On ne le remet en file/re-diffuse qu'une seule
            // fois (relayedRecords fait foi) pour ne pas saturer pendingRelayMessages.
            if (relayedRecords.containsKey(messageId)) return
            if (hopCount >= maxHops) {
                logToJs("RELAY", "SMS-REQ $messageId: maxHops atteint, drop")
                return
            }
            val updatedJson = """"hopCount"\s*:\s*$hopCount""".toRegex()
                .replace(json, "\"hopCount\":${hopCount + 1}")
            logToJs("RELAY", "SMS-REQ $msgType : pas de réseau/SIM ici, propagation hop=${hopCount + 1}")
            registerRelayedService(updatedJson)
            synchronized(pendingRelayMessages) { pendingRelayMessages.add(updatedJson) }
            groupOwnerAddress?.let { addr ->
                if (!isGroupOwner) sendRelayToPeer(addr, updatedJson)
            }
        }
    }

    // =========================================================================
    // Send relay message to a specific peer
    // =========================================================================
    // attempts=1 pour le POLLING périodique (un échec n'est pas grave, le
    // prochain poll arrive dans 10s) ; 3 pour les envois de paquets (D1/3d).
    private fun sendRelayToPeer(address: String, messageJson: String, attempts: Int = 3) {
        thread {
            // D1/3d : sur WiFi Direct la première connexion échoue souvent
            // (ARP pas résolu, GO pas prêt) — 1 essai + 2 retries espacés de 2s.
            var lastError: Exception? = null
            for (attempt in 1..attempts) {
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
                    Log.d("MARSEL_RELAY", "Relay exchange with $address complete (attempt $attempt)")
                    return@thread
                } catch (e: Exception) {
                    lastError = e
                    Log.w("MARSEL_RELAY", "Relay to $address attempt $attempt/3 failed: ${e.message}")
                    if (attempt < 3) {
                        try { Thread.sleep(2_000) } catch (ie: InterruptedException) { return@thread }
                    }
                } finally {
                    try { socket?.close() } catch (ex: Exception) { Log.v(TAG, "close: ${ex.message}") }
                }
            }
            Log.e("MARSEL_RELAY", "Relay to $address abandoned after 3 attempts: ${lastError?.message}")
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
            { instanceName, registrationType, device ->
                lastDnsSdEventMs = System.currentTimeMillis()  // watchdog 3f
                Log.d(TAG, "DNS-SD service found: $instanceName ($registrationType) from ${device.deviceName}")
                if (registrationType.contains("_marsel._tcp", ignoreCase = true) ||
                    instanceName.startsWith("marsel", ignoreCase = true)
                ) {
                    markMarselPeer(device)
                }
            },
            { fullDomain, txtRecord, device ->
                lastDnsSdEventMs = System.currentTimeMillis()  // watchdog 3f
                if (fullDomain.contains("marsel", ignoreCase = true)) {
                    Log.d(TAG, "Marsel TXT record from ${device.deviceName}: $txtRecord")
                    markMarselPeer(device)
                    handleServiceTxtRecord(txtRecord)
                }
            }
        )
    }

    // =========================================================================
    // A1/3e — Recovery du channel WiFi P2P
    // =========================================================================
    private fun onChannelLost() {
        logToJs("P2P", "CHANNEL PERDU — réinitialisation complète du WiFi P2P")
        try {
            wifiP2pChannel = wifiP2pManager.initialize(this, mainLooper) { onChannelLost() }
            setupDnsSdListeners()
            reRegisterActiveServices()
            kickDiscoveryNow()
        } catch (e: Exception) {
            Log.e(TAG, "onChannelLost recovery failed: ${e.message}")
        }
    }

    // =========================================================================
    // PERM-FIX — permission P2P requise + recovery du channel gelé
    // =========================================================================
    private fun hasNearbyPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            // API < 33 : la découverte WiFi P2P exige la localisation fine
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    // true si la permission manque : le MRN est inopérant. Prévient l'UI
    // (une seule fois par état) qui redemande la permission, et log throttlé
    // (1/min) au lieu de marteler le framework avec des reason=0.
    private fun p2pPermissionMissing(): Boolean {
        if (hasNearbyPermission()) {
            if (p2pBlockedNotified) {
                p2pBlockedNotified = false
                runOnUiThread {
                    webView.evaluateJavascript("window.onP2PBlocked && window.onP2PBlocked('')", null)
                }
            }
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastPermWarnMs > 60_000) {
            lastPermWarnMs = now
            logToJs("P2P", "MRN INACTIF : permission « Appareils à proximité » manquante")
        }
        if (!p2pBlockedNotified) {
            p2pBlockedNotified = true
            runOnUiThread {
                webView.evaluateJavascript("window.onP2PBlocked && window.onP2PBlocked('permission')", null)
            }
        }
        return true
    }

    private fun noteP2pError() {
        if (!hasNearbyPermission()) return  // cause connue : permission, pas le channel
        if (consecutiveP2pErrors.incrementAndGet() >= 6) {
            p2pHandler.post { recreateP2pChannel("6 échecs consécutifs reason=0 avec permissions OK") }
        }
    }

    private fun noteP2pSuccess() {
        consecutiveP2pErrors.set(0)
    }

    // Recréation dure du channel (close + initialize) : dernier recours quand
    // le framework répond ERROR en boucle alors que les permissions sont là.
    private fun recreateP2pChannel(why: String) {
        logToJs("P2P", "RECRÉATION du channel WiFi P2P ($why)")
        consecutiveP2pErrors.set(0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try { wifiP2pChannel.close() } catch (e: Exception) { Log.v(TAG, "channel close: ${e.message}") }
            }
        } catch (e: Exception) { /* ignore */ }
        try {
            wifiP2pChannel = wifiP2pManager.initialize(this, mainLooper) { onChannelLost() }
            setupDnsSdListeners()
            lastServiceRequestResetMs = 0
            reRegisterActiveServices()
            kickDiscoveryNow()
        } catch (e: Exception) {
            Log.e(TAG, "recreateP2pChannel: ${e.message}")
        }
    }

    // =========================================================================
    // A3/3f — Watchdog : silence DNS-SD > 60s alors que le P2P est actif
    // =========================================================================
    // Retourne true si un recovery a été lancé (le cycle appelant ne doit alors
    // PAS lancer restartServiceDiscovery en parallèle — collision, BUG-4).
    private fun watchdogCheck(): Boolean {
        if (!wifiP2pEnabled) return false
        val silentMs = System.currentTimeMillis() - lastDnsSdEventMs
        if (silentMs > 60_000) {
            lastDnsSdEventMs = System.currentTimeMillis()
            fullServiceRecovery("aucun événement DNS-SD depuis ${silentMs / 1000}s")
            return true
        }
        return false
    }

    private fun fullServiceRecovery(reason: String) {
        logToJs("DNS-SD", "RECOVERY ($reason)")
        lastServiceRequestResetMs = System.currentTimeMillis()
        try {
            wifiP2pManager.clearServiceRequests(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = step2()
                override fun onFailure(reason: Int) = step2()
                private fun step2() {
                    try {
                        wifiP2pManager.clearLocalServices(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() = finish()
                            override fun onFailure(reason: Int) = finish()
                            private fun finish() {
                                reRegisterActiveServices()
                                addServiceRequestAndDiscover()
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "fullServiceRecovery step2: ${e.message}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "fullServiceRecovery: ${e.message}")
        }
    }

    // Ré-enregistre tous les services actifs (après recovery ou channel perdu) :
    // alerte locale, position, et alertes relayées.
    private fun reRegisterActiveServices() {
        activeEmergencyRecord?.let { rec ->
            val info = WifiP2pDnsSdServiceInfo.newInstance(
                MarselProtocol.alertInstanceName(rec["i"] ?: "x"), "_marsel._tcp", rec
            )
            registerAlertService(info)
        }
        activePositionRecord?.let { rec ->
            val info = WifiP2pDnsSdServiceInfo.newInstance(
                MarselProtocol.POSITION_INSTANCE_NAME, "_marsel._tcp", rec
            )
            activePositionServiceInfo = info
            addPosServiceSafe(info)
        }
        for ((msgId, rec) in relayedRecords) {
            val info = buildRelayedServiceInfo(msgId, rec) ?: continue
            relayedServiceInfos[msgId] = info
            addLocalServiceSafe(info, "relayed-${msgId.takeLast(8)}")
        }
    }

    // =========================================================================
    // C3/C4 — Services relayés (alertes/résolutions d'autres téléphones)
    // =========================================================================
    private fun buildRelayedServiceInfo(msgId: String, rec: Map<String, String>): WifiP2pDnsSdServiceInfo? {
        val instanceName = when (rec["y"]) {
            MarselProtocol.SHORT_EMERGENCY -> MarselProtocol.alertInstanceName(msgId)
            MarselProtocol.SHORT_RESOLVED -> MarselProtocol.resolvedInstanceName(msgId)
            MarselProtocol.SHORT_RESOLVED_SMS_REQUEST,
            MarselProtocol.SHORT_TIMEOUT_SMS_REQUEST -> MarselProtocol.smsReqInstanceName(msgId)
            MarselProtocol.SHORT_SMS_ACK -> MarselProtocol.ackInstanceName(msgId)
            else -> return null
        }
        return WifiP2pDnsSdServiceInfo.newInstance(instanceName, "_marsel._tcp", rec)
    }

    /** Re-diffuse un paquet reçu (hop déjà incrémenté) sous sa propre instance de service. */
    private fun registerRelayedService(json: String) {
        val shortType = MarselProtocol.shortTypeOf(
            MarselProtocol.extractJsonString(json, "type") ?: return
        ) ?: return
        val record = buildTxtRecord(shortType, json) ?: return
        val msgId = record["i"] ?: return

        // Jamais re-diffuser notre propre alerte active sous forme relayée.
        // Exception : nos propres paquets F/T/S passent ICI volontairement
        // (c'est leur canal d'émission) — mais un écho reçu du maillage est
        // déjà bloqué en amont (ownMessageIds dans processRelayMessage).
        if (activeEmergencyRecord?.get("i") == msgId) return
        // BUG-2 : ne pas re-diffuser une urgence résolue
        val relEmergency = record["e"] ?: ""
        if (shortType == MarselProtocol.SHORT_EMERGENCY &&
            relEmergency.isNotEmpty() && dedupLedger.isSeen("res", relEmergency)
        ) return
        if (relayedRecords.containsKey(msgId)) return
        // BUG-7 : au cap, évincer le PLUS VIEUX service non-E au lieu de jeter
        // le nouveau — un ACK arrivait au moment où le cap était plein et était
        // « skip » (vu en test), retardant la confirmation chez l'émetteur.
        if (relayedRecords.size >= 8) {
            val oldest = relayedRecords.entries
                .filter { it.value["y"] != MarselProtocol.SHORT_EMERGENCY }
                .minByOrNull { it.value["s"]?.toLongOrNull() ?: 0L }
                ?: relayedRecords.entries.minByOrNull { it.value["s"]?.toLongOrNull() ?: 0L }
            if (oldest != null) {
                dropRelayedService(oldest.key, "éviction cap")
            } else {
                logToJs("DNS-SD", "RELAYED: cap atteint, skip $msgId")
                return
            }
        }

        relayedRecords[msgId] = record
        record["e"]?.let { relayedEmergencyIds[msgId] = it }
        val info = buildRelayedServiceInfo(msgId, record) ?: return
        relayedServiceInfos[msgId] = info

        runOnUiThread {
            addLocalServiceSafe(info, "relayed-${msgId.takeLast(8)}")
        }
        logToJs("DNS-SD", "RELAYED: re-diffusion ${record["y"]} $msgId (hop=${record["h"]})")
        kickDiscoveryNow()
    }

    // I3 : purge les paquets en file (positions/alerte) d'une urgence résolue,
    // pour ne pas rejouer de vieilles positions aux pairs qui se connectent après.
    // TEST-FIX : match par VALEUR d'identifiant (couvre id / messageId /
    // emergencyId) — les paquets E d'origine ne portent pas de champ emergencyId
    // et échappaient à la purge, d'où une vieille E rejouée par socket en test.
    private fun purgePendingForEmergency(emergencyId: String) {
        if (emergencyId.isEmpty()) return
        synchronized(pendingRelayMessages) {
            pendingRelayMessages.removeAll { it.contains("\"$emergencyId\"") }
        }
    }

    /** Retire les services relayés d'une urgence résolue (appelé à la réception d'un R). */
    private fun removeRelayedForEmergency(emergencyId: String) {
        purgePendingForEmergency(emergencyId)
        val toRemove = relayedEmergencyIds.filterValues { it == emergencyId }.keys
        for (msgId in toRemove) {
            // Ne retirer que les alertes E — la résolution R relayée doit continuer
            // à se diffuser pour nettoyer les cartes des téléphones distants.
            if (relayedRecords[msgId]?.get("y") == MarselProtocol.SHORT_EMERGENCY) {
                dropRelayedService(msgId, "urgence résolue")
            }
        }
    }

    /** Expiration des services relayés : E après 10 min, R/F/T après 3 min. */
    private fun purgeExpiredRelayedServices() {
        val now = System.currentTimeMillis()
        for ((msgId, rec) in relayedRecords) {
            val ts = rec["s"]?.toLongOrNull() ?: continue
            val ttl = if (rec["y"] == MarselProtocol.SHORT_EMERGENCY) 10 * 60_000L else 3 * 60_000L
            if (now - ts > ttl) dropRelayedService(msgId, "expiré")
        }
    }

    private fun dropRelayedService(msgId: String, why: String) {
        relayedRecords.remove(msgId)
        relayedEmergencyIds.remove(msgId)
        val info = relayedServiceInfos.remove(msgId) ?: return
        runOnUiThread { safeRemoveLocalService(info, "relayed-$why") }
    }

    // =========================================================================
    // Helpers add/remove service avec log — toujours appelés sur le main thread
    // =========================================================================
    private fun addLocalServiceSafe(info: WifiP2pDnsSdServiceInfo, label: String, onOk: (() -> Unit)? = null) {
        if (p2pPermissionMissing()) return
        try {
            wifiP2pManager.addLocalService(wifiP2pChannel, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    noteP2pSuccess()
                    logToJs("DNS-SD", "$label registered ✓")
                    onOk?.invoke()
                }
                override fun onFailure(r: Int) {
                    logToJs("DNS-SD", "$label register FAILED: $r")
                    noteP2pError()
                }
            })
        } catch (ex: SecurityException) {
            logToJs("DNS-SD", "$label denied: ${ex.message}")
        }
    }

    private fun safeRemoveLocalService(info: WifiP2pDnsSdServiceInfo, label: String, onDone: (() -> Unit)? = null) {
        try {
            wifiP2pManager.removeLocalService(wifiP2pChannel, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { onDone?.invoke() }
                override fun onFailure(r: Int) {
                    logToJs("DNS-SD", "removeLocalService $label failed: $r")
                    onDone?.invoke()
                }
            })
        } catch (ex: Exception) {
            logToJs("DNS-SD", "removeLocalService $label error: ${ex.message}")
            onDone?.invoke()
        }
    }

    // -------------------------------------------------------------------------
    // Registre des pairs Marsel (Section 2 — filtrage strict)
    // -------------------------------------------------------------------------
    private fun markMarselPeer(device: WifiP2pDevice?) {
        val addr = device?.deviceAddress ?: return
        if (addr.isEmpty()) return
        val existing = marselPeers[addr]
        if (existing == null) {
            marselPeers[addr] = MarselPeer(addr, device.deviceName ?: "?", System.currentTimeMillis())
            logToJs("P2P", "Pair Marsel confirmé: ${device.deviceName} ($addr) — total=${marselPeers.size}")
            notifyMarselPeersToJs()
        } else {
            existing.lastSeenMs = System.currentTimeMillis()
        }
    }

    private fun purgeExpiredMarselPeers() {
        val cutoff = System.currentTimeMillis() - MARSEL_PEER_TTL_MS
        var removed = false
        val iter = marselPeers.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.value.lastSeenMs < cutoff) {
                logToJs("P2P", "Pair Marsel expiré: ${e.value.deviceName}")
                iter.remove()
                removed = true
            }
        }
        if (removed) notifyMarselPeersToJs()
    }

    private fun marselPeersJson(): String {
        return marselPeers.values.joinToString(",", "[", "]") { p ->
            """{"name":"${p.deviceName.replace("\"", "\\\"")}","address":"${p.deviceAddress}"}"""
        }
    }

    private fun notifyMarselPeersToJs() {
        val json = marselPeersJson()
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onPeersDiscovered && window.onPeersDiscovered($json)",
                null
            )
        }
    }

    private fun handleServiceTxtRecord(record: Map<String, String?>) {
        try {
            noteP2pSuccess()  // réception réelle = stack P2P sain
            val json = MarselProtocol.txtRecordToJson(record, System.currentTimeMillis()) ?: run {
                logToJs("DNS-SD", "TXT record invalide/incomplet: $record")
                return
            }
            val type = MarselProtocol.extractJsonString(json, "type") ?: return
            val pseudo = MarselProtocol.extractJsonString(json, "pseudo") ?: "?"
            val hop = MarselProtocol.extractJsonInt(json, "hopCount") ?: 0
            val emergencyId = MarselProtocol.extractJsonString(json, "emergencyId")
            logToJs("DNS-SD", "RX TXT type=$type pseudo=$pseudo hop=$hop")

            // A4/3a : suivi d'alerte distante pour l'intervalle adaptatif.
            // Bascule immédiate veille→actif à la première réception.
            when (type) {
                MarselProtocol.TYPE_EMERGENCY, MarselProtocol.TYPE_POSITION -> {
                    val wasActive = isAlertActive()
                    remoteAlertActiveUntilMs = System.currentTimeMillis() + 120_000
                    if (!wasActive) kickDiscoveryNow()
                }
                MarselProtocol.TYPE_RESOLVED -> {
                    remoteAlertActiveUntilMs = 0
                    emergencyId?.let { removeRelayedForEmergency(it) }
                }
            }

            processRelayMessage(json)
        } catch (e: Exception) {
            logToJs("DNS-SD", "handleServiceTxtRecord ERROR: ${e.message}")
        }
    }

    // Encodage TXT délégué au protocole pur (testable, Section 7). La clé "c"
    // transporte les numéros des proches pour la chaîne relay hors-ligne,
    // "u" le flag enregistrement audio.
    private fun buildTxtRecord(shortType: String, json: String): Map<String, String>? =
        MarselProtocol.buildTxtRecord(shortType, json, System.currentTimeMillis())

    private fun broadcastViaService(json: String) {
        if (!wifiP2pEnabled) {
            logToJs("DNS-SD", "WiFi P2P disabled — cannot broadcast")
            return
        }
        if (p2pPermissionMissing()) return
        try {
            // BUG-1 (terrain) : le mapping local ne connaissait que E/P/R — les
            // paquets F/T (SMS de fin / relance 20 min délégués) tombaient dans
            // « Unknown type, skip » et n'étaient JAMAIS diffusés en DNS-SD.
            // Résultat : pas de SMS de fin via relais, timeout ACK chez l'émetteur.
            val shortType = MarselProtocol.shortTypeOf(extractJsonString(json, "type") ?: "")
                ?: run { logToJs("DNS-SD", "Unknown type, skip"); return }
            val record = buildTxtRecord(shortType, json) ?: run {
                logToJs("DNS-SD", "buildTxtRecord failed for $shortType")
                return
            }

            // Must run on main thread — WifiP2pManager is not thread-safe
            runOnUiThread {
                when (shortType) {
                    "E" -> {
                        // BUG-2 (terrain) : ne JAMAIS (re-)diffuser une urgence déjà
                        // RÉSOLUE. Le flush JS re-poussait de vieilles alertes finies
                        // → marsel-alert-* fantômes, marqueurs qui s'accumulent,
                        // re-notifications et re-SMS chez les voisins.
                        val resolvedKey = record["e"] ?: record["i"] ?: ""
                        if (resolvedKey.isNotEmpty() && dedupLedger.isSeen("res", resolvedKey)) {
                            logToJs("DNS-SD", "EMERGENCY $resolvedKey déjà résolue — pas de re-diffusion")
                            return@runOnUiThread
                        }

                        // A2 : annuler tout clear différé d'une résolution précédente
                        // AVANT d'enregistrer la nouvelle alerte, sinon le runnable
                        // de 2 min effacerait le marsel-alert fraîchement posé.
                        resolvedClearRunnable?.let { p2pHandler.removeCallbacks(it) }
                        resolvedClearRunnable = null

                        // C3 : instance distincte par urgence (marsel-alert-<suffix>).
                        // On retire proprement l'ancienne alerte locale (removeLocalService,
                        // pas clearLocalServices) pour ne pas toucher aux services relayés.
                        val msgId = record["i"] ?: ""
                        val oldAlertInfo = activeAlertServiceInfo
                        activeEmergencyRecord = record
                        activePositionRecord = null
                        activePositionServiceInfo = null
                        lastPositionBroadcastMs = 0
                        val info = WifiP2pDnsSdServiceInfo.newInstance(
                            MarselProtocol.alertInstanceName(msgId), "_marsel._tcp", record
                        )
                        logToJs("DNS-SD", "EMERGENCY: registering ${MarselProtocol.alertInstanceName(msgId)}")
                        if (oldAlertInfo != null) {
                            safeRemoveLocalService(oldAlertInfo, "old-alert") { registerAlertService(info) }
                        } else {
                            registerAlertService(info)
                        }
                        kickDiscoveryNow()  // 3b : découverte relancée immédiatement
                    }
                    "P" -> {
                        // Position update: NEVER touch the alert service.
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
                        val newPosInfo = WifiP2pDnsSdServiceInfo.newInstance(
                            MarselProtocol.POSITION_INSTANCE_NAME, "_marsel._tcp", record
                        )
                        val oldPosInfo = activePositionServiceInfo
                        activePositionServiceInfo = newPosInfo
                        if (oldPosInfo != null) {
                            safeRemoveLocalService(oldPosInfo, "old-pos") { addPosServiceSafe(newPosInfo) }
                        } else {
                            addPosServiceSafe(newPosInfo)
                        }
                        logToJs("DNS-SD", "POSITION: updating marsel-pos (alert untouched)")
                    }
                    "R" -> {
                        // Emergency resolved: retirer NOTRE alerte + position, diffuser R
                        // pendant 2 min. On ne touche PAS aux services relayés (C3).
                        val msgId = record["i"] ?: ""
                        // BUG-2 : mémoriser NOTRE PROPRE résolution — bloque toute
                        // re-diffusion ultérieure de cette urgence (garde du "E").
                        record["e"]?.let {
                            dedupLedger.checkAndMark("res", it)
                            purgePendingForEmergency(it)  // I3
                        }
                        activeAlertServiceInfo?.let { safeRemoveLocalService(it, "resolved-alert") }
                        activePositionServiceInfo?.let { safeRemoveLocalService(it, "resolved-pos") }
                        activeEmergencyRecord = null
                        activePositionRecord = null
                        activeAlertServiceInfo = null
                        activePositionServiceInfo = null
                        logToJs("DNS-SD", "RESOLVED: broadcasting ${MarselProtocol.resolvedInstanceName(msgId)}")
                        val resolvedInfo = WifiP2pDnsSdServiceInfo.newInstance(
                            MarselProtocol.resolvedInstanceName(msgId), "_marsel._tcp", record
                        )
                        val resInfoRef = resolvedInfo
                        addLocalServiceSafe(resolvedInfo, "marsel-resolved")
                        kickDiscoveryNow()
                        // A2 : clear ciblé du SEUL service resolved après 2 min,
                        // tracké pour annulation si une nouvelle urgence démarre avant.
                        val runnable = Runnable {
                            safeRemoveLocalService(resInfoRef, "resolved-expiry")
                            resolvedClearRunnable = null
                        }
                        resolvedClearRunnable = runnable
                        p2pHandler.postDelayed(runnable, 120_000)
                    }
                    // BUG-1 : diffusion des demandes de SMS relayé (fin d'alerte F /
                    // relance 20 min T) émises par CE téléphone. Avant ce correctif
                    // elles tombaient dans « Unknown type, skip » → jamais diffusées,
                    // pas de SMS de fin via relais, timeout ACK garanti.
                    "F", "T" -> {
                        logToJs("DNS-SD", "SMS-REQ $shortType : diffusion ${MarselProtocol.smsReqInstanceName(record["i"] ?: "x")}")
                        registerRelayedService(json)
                        kickDiscoveryNow()
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
        if (p2pPermissionMissing()) return
        try {
            wifiP2pManager.addLocalService(wifiP2pChannel, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    noteP2pSuccess()
                    activeAlertServiceInfo = info
                    logToJs("DNS-SD", "marsel-alert registered ✓ — broadcasting continuously")
                }
                override fun onFailure(r: Int) {
                    logToJs("DNS-SD", "marsel-alert register FAILED: $r")
                    noteP2pError()
                }
            })
        } catch (ex: SecurityException) {
            logToJs("DNS-SD", "registerAlertService denied: ${ex.message}")
        }
    }

    // Add a position service without touching the alert service.
    private fun addPosServiceSafe(info: WifiP2pDnsSdServiceInfo) {
        if (p2pPermissionMissing()) return
        try {
            wifiP2pManager.addLocalService(wifiP2pChannel, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    noteP2pSuccess()
                    logToJs("DNS-SD", "marsel-pos registered ✓")
                }
                override fun onFailure(r: Int) {
                    logToJs("DNS-SD", "marsel-pos register FAILED: $r")
                    noteP2pError()
                }
            })
        } catch (ex: SecurityException) {
            logToJs("DNS-SD", "addPosServiceSafe denied: ${ex.message}")
        }
    }

    // TEST-FIX latence : le cycle complet clear→add→discover à CHAQUE période
    // (8s) provoque du throttling framework — trous de réception de 30s+
    // observés en test terrain. On ne ré-arme la requête de service que toutes
    // les 2 min (ou sur échec/recovery) ; entre-temps, simple discoverServices.
    @Volatile private var lastServiceRequestResetMs = 0L

    private fun restartServiceDiscovery() {
        if (!wifiP2pEnabled) return
        // PERM-FIX : sans permission proximité, tout échouerait en reason=0 —
        // on prévient l'UI (qui redemande) au lieu de marteler le framework.
        if (p2pPermissionMissing()) return
        val now = System.currentTimeMillis()
        // CACHE-FIX (terrain, 2e appel invisible) : une requête de service
        // réutilisée est servie depuis le CACHE du framework — un service
        // enregistré par un pair APRÈS notre requête reste invisible jusqu'au
        // re-arm complet (clear→add→discover). 120s était bien trop long :
        // re-arm toutes les 15s en alerte, 45s en veille. Entre deux re-arms,
        // discoverServices seul (pas de churn).
        // SOCKET-FIRST : groupe connecté → les messages passent par socket, le
        // re-arm complet (churn clear→add) peut être bien plus rare (60s).
        val rearmInterval = when {
            groupOwnerAddress != null -> 60_000L
            isAlertActive() -> 15_000L
            else -> 45_000L
        }
        if (now - lastServiceRequestResetMs > rearmInterval) {
            lastServiceRequestResetMs = now
            try {
                wifiP2pManager.clearServiceRequests(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { addServiceRequestAndDiscover() }
                    override fun onFailure(reason: Int) { addServiceRequestAndDiscover() }
                })
            } catch (e: Exception) {
                Log.e(TAG, "restartServiceDiscovery: ${e.message}")
            }
        } else {
            discoverServicesOnly()
        }
    }

    private fun addServiceRequestAndDiscover() {
        try {
            val request = WifiP2pDnsSdServiceRequest.newInstance()
            wifiP2pManager.addServiceRequest(wifiP2pChannel, request, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { discoverServicesOnly() }
                override fun onFailure(reason: Int) {
                    logToJs("DNS-SD", "addServiceRequest ÉCHEC reason=$reason")
                    lastServiceRequestResetMs = 0  // re-arm complet au prochain cycle
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "addServiceRequestAndDiscover: ${e.message}")
        }
    }

    private fun discoverServicesOnly() {
        if (p2pPermissionMissing()) return
        try {
            wifiP2pManager.discoverServices(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    noteP2pSuccess()
                    Log.d(TAG, "DNS-SD discovery running")
                }
                override fun onFailure(reason: Int) {
                    // Échec visible dans l'overlay (avant : Log.w invisible en test
                    // mobile) + re-arm complet au prochain cycle.
                    logToJs("DNS-SD", "discoverServices ÉCHEC reason=$reason — re-arm au prochain cycle")
                    lastServiceRequestResetMs = 0
                    noteP2pError()
                }
            })
        } catch (e: SecurityException) {
            logToJs("DNS-SD", "discoverServices refusé (NEARBY_WIFI_DEVICES?): ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "discoverServicesOnly: ${e.message}")
        }
    }

    // =========================================================================
    // Helper: JSON extraction without external library
    // =========================================================================
    // I4 : délégué au protocole pur, qui tolère les guillemets/antislashs
    // échappés dans les valeurs (un pseudo contenant " ne corrompt plus l'extraction).
    private fun extractJsonString(json: String, key: String): String? =
        MarselProtocol.extractJsonString(json, key)

    private fun extractJsonInt(json: String, key: String): Int? =
        MarselProtocol.extractJsonInt(json, key)

    private fun extractJsonNumber(json: String, key: String): Double? =
        MarselProtocol.extractJsonNumber(json, key)

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
    // TEST-FIX — Capacité SMS réelle (SIM présente et prête)
    // =========================================================================
    // Le check réseau (WiFi validé) ne dit RIEN de la capacité à envoyer un SMS :
    // un téléphone en WiFi SANS SIM croyait pouvoir SMS-er ses proches lui-même
    // (relaySms=0) → échec silencieux ET aucun relais. Vu en test terrain.
    private fun canSendSmsDirect(): Boolean {
        return try {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) return false
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            if (tm.simState != android.telephony.TelephonyManager.SIM_STATE_READY) return false
            // SIM prête ne suffit pas : sans RÉSEAU cellulaire enregistré (zone
            // blanche, mode avion partiel), le SMS échouera. networkOperator est
            // vide tant que le téléphone n'est pas enregistré sur un réseau.
            // Le verdict FINAL reste l'accusé d'envoi système (onSmsSendResult).
            !tm.networkOperator.isNullOrEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "canSendSmsDirect: ${e.message}")
            false
        }
    }

    // =========================================================================
    // F1 / Section 1 — Qualité réseau avec NET_CAPABILITY_VALIDATED
    // =========================================================================
    // Un WiFi/mobile connecté mais SANS accès internet réel (portail captif,
    // box sans ligne) est traité comme NONE : le relais ne croit pas à tort
    // pouvoir envoyer le SMS, et le paquet continue de se propager dans le MRN.
    private fun detectNetworkQuality(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return "NONE"
                val caps = cm.getNetworkCapabilities(network) ?: return "NONE"
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                MarselProtocol.classifyNetworkQuality(
                    hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    validated = validated,
                    hasInternet = internet
                )
            } else {
                // API < 23 : pas de NET_CAPABILITY_VALIDATED, on se rabat sur
                // la connectivité déclarée (best effort).
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                if (info?.isConnected != true) "NONE"
                else when (info.type) {
                    ConnectivityManager.TYPE_MOBILE -> "MOBILE_STABLE"
                    ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET -> "WIFI_STABLE"
                    else -> "NONE"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "detectNetworkQuality: ${e.message}")
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
    // trackId != null → l'accusé d'envoi système revient dans smsSentReceiver
    // (onSmsSendResult) : c'est le SEUL verdict fiable de « le SMS est parti ».
    private fun sendSMSDirect(phoneNumber: String, message: String, trackId: String? = null) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w("MARSEL_SMS", "SEND_SMS permission not granted")
                trackId?.let { onSmsSendResult(it, false, -1) }
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
                trackId?.let { onSmsSendResult(it, false, -1) }
                return
            }
            val sentPi = trackId?.let {
                val intent = Intent(SMS_SENT_ACTION)
                    .setPackage(packageName)
                    .putExtra("trackId", it)
                PendingIntent.getBroadcast(
                    this, smsTrackCounter.incrementAndGet(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, sentPi, null)
            } else {
                val sentIntents = if (sentPi != null) ArrayList(parts.map { sentPi }) else null
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null)
            }
            Log.d("MARSEL_SMS", "SMS submitted to $phoneNumber (track=$trackId)")
        } catch (e: SecurityException) {
            Log.e("MARSEL_SMS", "SecurityException sending SMS to $phoneNumber: ${e.message}")
            trackId?.let { onSmsSendResult(it, false, -1) }
        } catch (e: Exception) {
            Log.e("MARSEL_SMS", "SMS failed to $phoneNumber: ${e.message}")
            trackId?.let { onSmsSendResult(it, false, -1) }
        }
    }

    // =========================================================================
    // Verdict d'envoi SMS + ACK maillage
    // =========================================================================
    // trackId "relay|<origId>" = envoi fait EN TANT QUE RELAIS pour un émetteur
    // hors réseau → succès : marquer smsHandled + diffuser l'ACK qui remontera
    // jusqu'à l'émetteur ; échec : libérer pour qu'un autre relais s'en charge.
    // Tout autre trackId = envoi local (nos propres proches) → remonté au JS
    // qui bascule sur le MRN en cas d'échec.
    private fun onSmsSendResult(trackId: String, ok: Boolean, resultCode: Int) {
        logToJs("SMS", "résultat envoi track=$trackId ok=$ok code=$resultCode")
        if (trackId.startsWith("relay|")) {
            val origId = trackId.removePrefix("relay|")
            val sourceJson = pendingRelaySends.remove(origId)
            smsInFlight.remove(origId)
            if (ok) {
                dedupLedger.checkAndMark("sms", origId)
                sourceJson?.let { broadcastSmsAck(origId, it) }
            } else {
                logToJs("RELAY", "SMS relais ÉCHEC pour $origId — non marqué, un autre relais peut reprendre")
            }
        } else {
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onSmsSendResult && window.onSmsSendResult('${trackId.replace("'", "")}',$ok,$resultCode)",
                    null
                )
            }
        }
    }

    // Diffuse l'accusé « SMS réellement envoyés » (type S) : remonte de proche
    // en proche (max 5 sauts) jusqu'à l'émetteur d'origine.
    private fun broadcastSmsAck(origMessageId: String, sourceJson: String) {
        try {
            // ANONYMISATION (TODO.md §2.2) : l'ACK est un accusé technique, pas
            // un affichage — inutile qu'il porte le vrai pseudo (extrait avant
            // depuis le F/T source, qui le préserve légitimement pour la
            // composition du SMS). Alias dérivé du VRAI emergencyId du paquet
            // source (QA-FIX) — PAS de origMessageId, qui est en réalité le
            // messageId du F/T (`<emergencyId>_<tag>_<timestamp>`, cf.
            // sendCascadeViaMrn) : dériver l'alias depuis origMessageId aurait
            // produit un alias DIFFÉRENT de celui affiché ailleurs pour la
            // même alerte (notification, carte). "emergencyId"/"id" du paquet
            // ACK restent volontairement égaux à origMessageId ci-dessous :
            // c'est cet id-là que armSmsAckWait() (script.js) attend pour
            // corréler l'accusé à sa demande, pas le vrai emergencyId.
            val realEmergencyId = extractJsonString(sourceJson, "emergencyId") ?: origMessageId
            val pseudo = MarselProtocol.deriveAlertAlias(realEmergencyId)
            val lat = extractJsonNumber(sourceJson, "lat") ?: return
            val lng = extractJsonNumber(sourceJson, "lng") ?: return
            val ackJson = """{"type":"${MarselProtocol.TYPE_SMS_ACK}","messageId":"${origMessageId}_ack","id":"$origMessageId","emergencyId":"$origMessageId","pseudo":"$pseudo","lat":$lat,"lng":$lng,"timestamp":${System.currentTimeMillis()},"hopCount":0,"maxHops":${MarselProtocol.MAX_HOPS},"contacts":[]}"""
            // Notre propre ACK : l'écho relayé ne doit pas être re-traité (BUG-3)
            ownMessageIds.add("${origMessageId}_ack")
            logToJs("RELAY", "ACK diffusé : SMS envoyés pour $origMessageId")
            registerRelayedService(ackJson)
            synchronized(pendingRelayMessages) { pendingRelayMessages.add(ackJson) }
            groupOwnerAddress?.let { addr ->
                if (!isGroupOwner) sendRelayToPeer(addr, ackJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "broadcastSmsAck: ${e.message}")
        }
    }

    // =========================================================================
    // Biométrie (TODO.md §1.2) — complément du mot de passe local, JAMAIS un
    // remplacement : le champ mot de passe reste toujours disponible côté JS.
    // =========================================================================
    private fun biometricAvailable(): Boolean {
        val manager = BiometricManager.from(this)
        val canAuth = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return canAuth == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPromptInternal() {
        if (!biometricAvailable()) {
            webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(false)", null)
            return
        }
        // QA-FIX : lier le prompt à un CryptoObject (clé Keystore dédiée,
        // setInvalidatedByBiometricEnrollment) — sans ça, BiometricPrompt ne
        // vérifie qu'« un moyen biométrique fort existe sur ce téléphone »,
        // et N'IMPORTE QUELLE empreinte/visage enrôlé (y compris ajouté après
        // coup par un tiers ayant eu un accès bref au téléphone) déverrouille
        // l'app — exactement le scénario que §1.3 visait à couvrir.
        val cipher: Cipher
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secureStorage.getOrCreateBiometricGateKey())
        } catch (e: KeyPermanentlyInvalidatedException) {
            // L'enrôlement biométrique a changé depuis la création de la clé
            // (nouvelle empreinte ajoutée, etc.) : on refuse ce déverrouillage
            // biométrique-ci (repli mot de passe) et on régénère la clé pour
            // les prochaines tentatives.
            secureStorage.resetBiometricGateKey()
            webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(false)", null)
            return
        } catch (e: Exception) {
            Log.e(TAG, "showBiometricPromptInternal cipher init: ${e.message}")
            webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(false)", null)
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(true)", null)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Inclut l'annulation utilisateur : dans tous les cas on retombe
                // sur le mot de passe (déjà affiché), jamais de blocage.
                webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(false)", null)
            }
            override fun onAuthenticationFailed() {
                // Empreinte/visage non reconnu : le prompt système reste ouvert
                // et retente lui-même — pas de callback JS ici.
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller Marsel")
            .setSubtitle("Utilisez votre empreinte ou votre visage")
            .setNegativeButtonText("Utiliser le mot de passe")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    // =========================================================================
    // Enregistrement audio (Section 5) — emplacement pérenne + segments 5 min
    // =========================================================================
    // Doit tourner sur le main thread (setOnInfoListener + rotation).
    private fun startAudioRecordingInternal() {
        if (audioActive) { logToJs("AUDIO", "déjà actif — double-start ignoré"); return }
        // H3 : vérifier la permission RECORD_AUDIO avant de démarrer
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            logToJs("AUDIO", "permission RECORD_AUDIO refusée — pas d'enregistrement")
            notifyRecordingState(false, "permission_refusee")
            return
        }
        audioBaseName = "marsel_alerte_" + formatNow("yyyyMMdd_HHmm")
        audioSegmentIndex = 0
        audioActive = true
        val ok = startAudioSegment()
        notifyRecordingState(ok, if (ok) "" else "erreur_demarrage")
    }

    // Démarre un nouveau segment. Retourne false en cas d'échec.
    private fun startAudioSegment(): Boolean {
        return try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            val name = if (audioSegmentIndex == 0) "$audioBaseName.m4a"
                       else "${audioBaseName}_part${audioSegmentIndex + 1}.m4a"

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // MediaStore : visible dans l'app Musique/Fichiers, pas de permission stockage
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + AUDIO_DIR)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: run { logToJs("AUDIO", "MediaStore insert échoué"); return false }
                currentAudioUri = uri
                val pfd = contentResolver.openFileDescriptor(uri, "w")
                    ?: run { logToJs("AUDIO", "openFileDescriptor null"); return false }
                currentAudioPfd = pfd
                recorder.setOutputFile(pfd.fileDescriptor)
            } else {
                // API<29 : fichier dans Music/Marsel public (WRITE_EXTERNAL_STORAGE, maxSdk 28)
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), AUDIO_DIR)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                currentAudioFile = file
                recorder.setOutputFile(file.absolutePath)
            }

            // H2 : rotation par segments — un segment finalisé reste toujours lisible
            recorder.setMaxDuration(AUDIO_SEGMENT_MS)
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    runOnUiThread { rotateAudioSegment() }
                }
            }
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            isRecording = true
            logToJs("AUDIO", "segment ${audioSegmentIndex + 1} démarré: $name")
            true
        } catch (e: Exception) {
            logToJs("AUDIO", "startAudioSegment ERROR: ${e.message}")
            cleanupFailedSegment()
            false
        }
    }

    private fun rotateAudioSegment() {
        if (!audioActive) return
        finalizeCurrentSegment()
        audioSegmentIndex += 1
        startAudioSegment()
    }

    // Finalise le segment courant (stop + release + publie MediaStore).
    private fun finalizeCurrentSegment() {
        try {
            mediaRecorder?.apply {
                try { stop() } catch (e: Exception) { Log.w(TAG, "recorder stop: ${e.message}") }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "finalizeCurrentSegment: ${e.message}")
        }
        mediaRecorder = null
        isRecording = false

        // Publier l'item MediaStore (IS_PENDING=0) → visible dans Musique
        currentAudioUri?.let { uri ->
            try {
                currentAudioPfd?.close()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, values, null, null)
                }
            } catch (e: Exception) { Log.w(TAG, "publish MediaStore: ${e.message}") }
        }
        currentAudioPfd = null
        currentAudioUri = null
        currentAudioFile?.let { lastFinalizedAudioFile = it }
        currentAudioFile = null
    }

    private fun cleanupFailedSegment() {
        try { currentAudioPfd?.close() } catch (e: Exception) { Log.v(TAG, "pfd close: ${e.message}") }
        currentAudioPfd = null
        currentAudioUri?.let { try { contentResolver.delete(it, null, null) } catch (e: Exception) {} }
        currentAudioUri = null
        currentAudioFile = null
    }

    private fun stopAudioRecordingInternal() {
        if (!audioActive) { logToJs("AUDIO", "stop sans enregistrement actif — ignoré"); return }
        audioActive = false
        finalizeCurrentSegment()
        logToJs("AUDIO", "enregistrement arrêté ($audioBaseName)")
        notifyRecordingState(false, "arret_normal")
    }

    private fun notifyRecordingState(recording: Boolean, reason: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onRecordingStateChanged && window.onRecordingStateChanged($recording,'$reason')",
                null
            )
        }
    }

    private fun formatNow(pattern: String): String =
        java.text.SimpleDateFormat(pattern, java.util.Locale.US).format(java.util.Date())

    // Liste JSON des enregistrements Marsel (nom, date, durée ms, taille octets).
    private fun listRecordingsJson(): String {
        val items = mutableListOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val proj = arrayOf(
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE
                )
                val sel = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                val args = arrayOf("%${AUDIO_DIR}%")
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    val ni = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val di = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val du = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val si = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    while (c.moveToNext()) {
                        val name = c.getString(ni).replace("\"", "")
                        items.add("""{"name":"$name","date":${c.getLong(di) * 1000},"duration":${c.getLong(du)},"size":${c.getLong(si)}}""")
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), AUDIO_DIR)
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                    items.add("""{"name":"${f.name.replace("\"", "")}","date":${f.lastModified()},"duration":0,"size":${f.length()}}""")
                }
            }
        } catch (e: Exception) {
            logToJs("AUDIO", "listRecordings ERROR: ${e.message}")
        }
        return items.joinToString(",", "[", "]")
    }

    // URI + taille du dernier enregistrement Marsel (le plus récent), ou null.
    private fun lastRecordingUriAndSize(): Pair<Uri, Long>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE)
                val sel = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, arrayOf("%${AUDIO_DIR}%"),
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                        val size = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                        )
                        return Pair(uri, size)
                    }
                }
                null
            } else {
                lastFinalizedAudioFile?.takeIf { it.exists() }?.let {
                    Pair(Uri.fromFile(it), it.length())
                }
            }
        } catch (e: Exception) {
            logToJs("AUDIO", "lastRecordingUriAndSize: ${e.message}"); null
        }
    }

    // 5b (best-effort) : MMS du dernier enregistrement si ≤ 1 Mo. L'envoi MMS
    // dépend du réseau opérateur et peut échouer silencieusement — le SMS texte
    // de fin (avec mention audio) part de toute façon via la cascade JS.
    private fun sendLastRecordingMms(phoneNumber: String) {
        try {
            if (detectNetworkQuality() != "MOBILE_STABLE") return
            val (uri, size) = lastRecordingUriAndSize() ?: return
            if (size > AUDIO_MMS_MAX_BYTES) {
                logToJs("AUDIO", "MMS ignoré: segment ${size / 1024}Ko > 1Mo (mention texte seule)")
                return
            }
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            } ?: return
            try { grantUriPermission("com.android.mms", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            val sentPi = PendingIntent.getBroadcast(
                this, 0, Intent("com.example.marsel.MMS_SENT"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
            val configOverrides = Bundle().apply {
                putString("to", phoneNumber)  // certains OEM lisent le destinataire ici
            }
            smsManager.sendMultimediaMessage(this, uri, null, configOverrides, sentPi)
            logToJs("AUDIO", "MMS audio tenté vers $phoneNumber (${size / 1024}Ko)")
        } catch (e: Exception) {
            logToJs("AUDIO", "sendLastRecordingMms ERROR: ${e.message}")
        }
    }

    // =========================================================================
    // Forward emergency to contacts via SMS (relais avec réseau, chaîne hors-ligne)
    // =========================================================================
    // Le relais n'envoie les SMS que s'il a un réseau VALIDÉ (F1) et une seule
    // fois par messageId (dédup smsHandled persistée, F3). Sans réseau, il ne
    // fait rien ici : le hop-forwarding DNS-SD (registerRelayedService) propage
    // le paquet plus loin jusqu'à un téléphone connecté.
    private fun forwardEmergencyToContacts(json: String) {
        try {
            // AUDIT-FIX 2 : ne relayer les SMS QUE si l'émetteur a demandé le relais
            // (relaySms="1" = il était hors réseau). Si l'émetteur avait du réseau,
            // il a déjà SMS-é ses proches → un relais ne doit pas renvoyer (sinon
            // double SMS, cas Test-1 : A a la data ET B a la data).
            if (extractJsonString(json, "relaySms") != "1") {
                return
            }
            val quality = detectNetworkQuality()
            if (quality == "NONE") {
                logToJs("RELAY", "SMS relais : pas de réseau validé ici, propagation MRN uniquement")
                return
            }
            // TEST-FIX : un relais SANS SIM/réseau cellulaire ne peut pas envoyer —
            // il ne doit ni marquer smsHandled ni s'arrêter là (la propagation MRN
            // continue dans processRelayMessage jusqu'à un téléphone capable).
            if (!canSendSmsDirect()) {
                logToJs("RELAY", "SMS relais : pas de SIM/réseau cellulaire ici, propagation MRN uniquement")
                return
            }
            val messageId = extractJsonString(json, "messageId") ?: return
            // smsHandled n'est marqué qu'à l'ACCUSÉ DE SUCCÈS système
            // (onSmsSendResult) — pas avant l'envoi. smsInFlight évite les
            // doublons pendant que l'accusé est en route.
            if (dedupLedger.isSeen("sms", messageId)) {
                return
            }
            if (smsInFlight.putIfAbsent(messageId, true) != null) {
                return
            }
            val mobiles = MarselProtocol.extractContactMobiles(json)
            if (mobiles.isEmpty()) { smsInFlight.remove(messageId); return }
            val pseudo = extractJsonString(json, "pseudo") ?: "Utilisateur"
            val lat = extractJsonNumber(json, "lat")
            val lng = extractJsonNumber(json, "lng")
            val mapsLink = if (lat != null && lng != null) {
                "https://maps.google.com/maps?q=$lat,$lng"
            } else "Position inconnue"
            val smsMsg = "ALERTE MARSEL\n$pseudo a declenche une alerte d'urgence.\nPosition: $mapsLink"
            logToJs("RELAY", "SMS relais ($quality) : envoi à ${mobiles.size} proche(s) à la place de l'émetteur")
            pendingRelaySends[messageId] = json
            mobiles.forEachIndexed { idx, mobile ->
                // Le premier envoi porte le suivi : son accusé fait verdict pour
                // le lot (même radio) → succès = smsHandled + ACK vers l'émetteur.
                sendSMSDirect(mobile, smsMsg, if (idx == 0) "relay|$messageId" else null)
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
                legacyServerSocket = serverSocket
                Log.d(TAG, "WiFi server listening on port 8888")
                while (serversRunning) {
                    val client = try { serverSocket.accept() } catch (e: Exception) { break }
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
        // Authentification locale + stockage chiffré (TODO.md §1)
        // Aucun compte serveur, aucune connexion Google/Facebook/Apple : tout
        // est vérifié et chiffré sur l'appareil via SecureStore (Keystore).
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun hasLocalAuth(): Boolean = secureStorage.hasLocalAuth()

        @JavascriptInterface
        fun setLocalSecret(secret: String): Boolean = secureStorage.setLocalSecret(secret)

        @JavascriptInterface
        fun verifyLocalSecret(secret: String): Boolean = secureStorage.verifyLocalSecret(secret)

        @JavascriptInterface
        fun secureStore(key: String, value: String) {
            secureStorage.secureStore(key, value)
        }

        @JavascriptInterface
        fun secureRetrieve(key: String): String? = secureStorage.secureRetrieve(key)

        @JavascriptInterface
        fun secureRemove(key: String) {
            secureStorage.secureRemove(key)
        }

        @JavascriptInterface
        fun isBiometricAvailable(): Boolean = biometricAvailable()

        @JavascriptInterface
        fun showBiometricPrompt() {
            runOnUiThread { showBiometricPromptInternal() }
        }

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
            runOnUiThread { startAudioRecordingInternal() }
        }

        @JavascriptInterface
        fun stopAudioRecord() {
            runOnUiThread { stopAudioRecordingInternal() }
        }

        // Liste des enregistrements pour l'UI (nom, date, durée, taille).
        @JavascriptInterface
        fun getRecordings(): String = listRecordingsJson()

        // Lieux sûrs : contenu brut de assets/safeplace.csv
        // (format : nom_emplacement, lat, long). Lu via le bridge car la WebView
        // bloque fetch() sur file:// — le JS filtre ensuite selon la position réelle.
        @JavascriptInterface
        fun getSafePlacesCsv(): String {
            return try {
                assets.open("safeplace.csv").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                Log.w(TAG, "getSafePlacesCsv: ${e.message}")
                ""
            }
        }

        // 5b : MMS best-effort du dernier enregistrement (fin d'alerte, MOBILE_STABLE)
        @JavascriptInterface
        fun sendRecordingMms(phoneNumber: String) {
            thread { sendLastRecordingMms(phoneNumber) }
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

        // F1 / Section 1 : qualité réseau validée (MOBILE_STABLE / WIFI_STABLE / NONE)
        @JavascriptInterface
        fun detectNetworkQuality(): String {
            return this@MainActivity.detectNetworkQuality()
        }

        // TEST-FIX : capacité SMS réelle (SIM prête + permission). Un téléphone
        // en WiFi sans SIM doit déléguer ses SMS au réseau Marsel (relaySms=1).
        @JavascriptInterface
        fun canSendSms(): Boolean {
            return canSendSmsDirect()
        }

        // -----------------------------------------------------------------------
        // Permissions (Section 6) — flow d'onboarding groupe par groupe
        // -----------------------------------------------------------------------

        // Demande un groupe ; résultat via window.onPermissionResult(group, granted, permanentlyDenied)
        @JavascriptInterface
        fun requestPermissionGroup(group: String) {
            runOnUiThread { launchPermissionGroup(group) }
        }

        @JavascriptInterface
        fun hasPermissionGroup(group: String): Boolean {
            val perms = permissionsForGroup(group)
            if (perms.isEmpty()) return true // rien à demander sur cet OS
            return perms.all {
                ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
            }
        }

        // Ouvre les réglages système de l'app (cas refus définitif)
        @JavascriptInterface
        fun openAppSettings() {
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "openAppSettings: ${e.message}")
            }
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

        // Envoi SUIVI : l'accusé système revient via window.onSmsSendResult
        // (trackId, ok, code) — en cas d'échec réel, le JS bascule sur le MRN.
        @JavascriptInterface
        fun sendEmergencySMSTracked(phoneNumber: String, message: String, trackId: String) {
            thread {
                sendSMSDirect(phoneNumber, message, trackId)
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
        fun sendEmergencyViaRelay(rawEmergencyJson: String) {
            thread {
                try {
                    // ANONYMISATION (TODO.md §2.2) : à partir d'ici, le paquet
                    // quitte le contexte local de l'émetteur pour être diffusé
                    // sur le MRN (DNS-SD ET socket) — le vrai pseudo est
                    // remplacé par un alias dérivé de l'emergencyId AVANT tout
                    // envoi. L'état JS local (marsel_user, marsel_emergency)
                    // n'est pas touché : il garde le vrai pseudo pour l'écran
                    // de l'émetteur et pour le SMS envoyé à SES PROPRES contacts.
                    val emergencyJson = MarselProtocol.anonymizePseudoForTransit(rawEmergencyJson)

                    val t = extractJsonString(emergencyJson, "type") ?: "?"
                    logToJs("RELAY", "SEND $t via DNS-SD + socket transport")

                    // BUG-3 : marquer NOS paquets sortants — le maillage nous les
                    // renvoie en écho (relayés par les voisins) et, sans ce marquage,
                    // on se notifiait de sa propre alerte et on re-relayait ses
                    // propres paquets.
                    extractJsonString(emergencyJson, "messageId")?.let { ownId ->
                        ownMessageIds.add(ownId)
                        if (ownMessageIds.size > 300) ownMessageIds.clear()
                        synchronized(processedMessageIds) { processedMessageIds.add(ownId) }
                        dedupLedger.checkAndMark("msg", ownId)
                    }

                    // PRIMARY transport: connection-less DNS-SD broadcast.
                    // broadcastViaService enchaîne register→kickDiscoveryNow (3b) :
                    // pas de restartServiceDiscovery() en plus ici, qui créerait une
                    // contention pendant le clear/add en cours (C1).
                    broadcastViaService(emergencyJson)

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
            // Ne retourne que les pairs Marsel confirmés (Section 2)
            return marselPeersJson()
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
