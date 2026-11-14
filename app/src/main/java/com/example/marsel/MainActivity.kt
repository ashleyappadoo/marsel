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
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.BatteryManager
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
import android.webkit.WebResourceResponse
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

// FragmentActivity (superset de ComponentActivity) requis par BiometricPrompt
// (TODO.md §1.2) — androidx.biometric n'a pas d'API pour ComponentActivity nu.
class MainActivity : FragmentActivity() {

    // -------------------------------------------------------------------------
    // Core WebView
    // -------------------------------------------------------------------------
    private lateinit var webView: WebView

    // Chantier confidentialité (TODO.md §1) : auth locale + chiffrement au
    // repos, jamais de compte serveur ni de connexion tierce.
    private lateinit var secureStorage: SecureStore
    // Moteur réseau (réseau Marsel, SMS, GPS, audio, flash) : process-level,
    // il peut survivre à l'écran (ForegroundService). L'écran s'y attache.
    private lateinit var engine: MarselEngine

    // Action « Terminer l'alerte » de la notification d'alerte en cours :
    // mémorisée ici, le JavaScript la lit (consumeEndAlertRequest) quand il
    // est prêt — après le déverrouillage de l'app si l'écran de verrouillage
    // Marsel est affiché.
    @Volatile private var pendingEndAlert = false

    companion object {
        private const val TAG = "MARSEL"
        private const val OSM_TILE_HOST = "tile.openstreetmap.org"
        private const val TILE_REFRESH_MS = 7L * 24 * 3600 * 1000     // re-téléchargée après 7 j
        private const val TILE_CACHE_MAX_AGE_MS = 30L * 24 * 3600 * 1000
        private const val TILE_CACHE_MAX_BYTES = 60L * 1024 * 1024
    }

    private fun escapeJs(text: String): String = MarselEngine.escapeJs(text)

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
        if (granted && (group == "location")) engine.onLocationPermissionGranted()
        // PERM-FIX : permission proximité tout juste accordée → relancer le MRN
        // immédiatement (re-arm complet + ré-enregistrement des services actifs).
        if (granted && group == "nearby") engine.onNearbyPermissionGranted()
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onPermissionResult && window.onPermissionResult('$group',$granted,$permanentlyDenied)",
                null
            )
        }
    }

    // Import d'un proche depuis les contacts du téléphone (TODO.md). Le
    // sélecteur SYSTÈME (Intent.ACTION_PICK) est utilisé à dessein : il ne
    // nécessite PAS la permission READ_CONTACTS — l'utilisateur choisit un
    // contact dans l'app Contacts elle-même, et seul ce contact précis est
    // retourné ici. Aucun accès à la liste complète du carnet d'adresses.
    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            runOnUiThread { webView.evaluateJavascript("window.onContactPicked && window.onContactPicked(null)", null) }
            return@registerForActivityResult
        }
        // QA-FIX : la requête au fournisseur de contacts est un appel bloquant
        // (IPC + disque) — jamais sur le thread UI (callback d'ActivityResult),
        // même pattern `thread {}` que le reste du fichier.
        thread {
            var nom = ""
            var mobile = ""
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        if (nameIdx >= 0) nom = cursor.getString(nameIdx) ?: ""
                        if (numberIdx >= 0) mobile = cursor.getString(numberIdx) ?: ""
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "contactPickerLauncher query: ${e.message}")
            }
            // QA-FIX : escapeJs() (déjà utilisé partout ailleurs dans ce fichier
            // pour injecter du texte arbitraire dans du JS évalué) plutôt qu'un
            // nettoyage ad-hoc — un nom avec apostrophe (« O'Brien ») n'est plus
            // corrompu, et un retour à la ligne ne casse plus le literal JS.
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onContactPicked && window.onContactPicked({nom:${escapeJs(nom)}, mobile:${escapeJs(mobile)}})",
                    null
                )
            }
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
        // "background_location" retiré : permission supprimée du manifeste
        // (publication Play, cf. AndroidManifest.xml / TODO.md).
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

        // CRASH-FIX (structurel) : tous les champs lateinit sont assignés en
        // premier, avant toute logique susceptible de les utiliser.
        webView = WebView(this)
        secureStorage = SecureStore(this)

        // Section 6 : les permissions ne sont PLUS demandées en un bloc ici.
        // Le flow d'onboarding JS (window) les demande groupe par groupe, dans
        // l'ordre, avec explication, via MarselBridge.requestPermissionGroup().
        // ACCESS_BACKGROUND_LOCATION n'est plus demandée (retirée du manifeste
        // pour la publication Play) ; plus aucune permission Bluetooth (G3).

        // --- Moteur réseau (WiFi P2P, DNS-SD, serveurs relais, SMS, GPS) ---
        // Démarré par le moteur lui-même au premier rattachement ; s'il tourne
        // déjà (alerte en cours ou relais en arrière-plan alors que l'écran
        // avait été fermé), l'écran s'y rattache simplement.
        engine = MarselEngine.get(this)
        engine.attachUi(webView)
        if (intent?.action == MarselForegroundService.ACTION_END_ALERT) pendingEndAlert = true

        // --- WebView (configuration — l'instance existe déjà) ---
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.setGeolocationEnabled(true)
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {
            // Tuiles OpenStreetMap : récupérées côté natif (thread de fond de la
            // WebView), cf. fetchOsmTile(). Toute autre requête suit son cours.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (request.method == "GET" && uri.scheme == "https" && uri.host == OSM_TILE_HOST) {
                    return fetchOsmTile(uri)
                }
                return super.shouldInterceptRequest(view, request)
            }

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
                    // Lien web cliqué dans la page (ex. attribution OpenStreetMap
                    // de la carte) : navigateur externe. Sans ça, la WebView
                    // quittait l'application Marsel pour afficher le site.
                    url.startsWith("https://") || url.startsWith("http://") -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            Log.e(TAG, "lien externe ($url): ${e.message}")
                        }
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

        trimTileCache()
    }

    // =========================================================================
    // Tuiles OpenStreetMap (fond de carte)
    // =========================================================================
    // Politique d'usage OSM : une page web doit envoyer un Referer — la page
    // Marsel est chargée en file://, donc sans Referer, et OSM la bloque. Une
    // APPLI doit en revanche s'identifier par un User-Agent propre et mettre
    // les tuiles en cache : c'est ce que fait ce proxy natif. Bonus sécurité :
    // les zones déjà consultées restent affichées hors connexion.
    private val appVersionName: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
    }

    private val tileUserAgent: String by lazy {
        "Marsel/$appVersionName (Android ${Build.VERSION.RELEASE}; $packageName; +https://marselapp.com)"
    }

    private fun tileResponse(bytes: ByteArray): WebResourceResponse =
        WebResourceResponse("image/png", null, 200, "OK",
            mapOf("Cache-Control" to "max-age=86400"), java.io.ByteArrayInputStream(bytes))

    private fun tileUnavailable(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, reason, null, java.io.ByteArrayInputStream(ByteArray(0)))

    private fun fetchOsmTile(uri: Uri): WebResourceResponse {
        // Chemin attendu : /{z}/{x}/{y}.png, chiffres uniquement (aucune
        // traversée de répertoire possible dans le cache).
        val segs = uri.pathSegments
        if (segs.size != 3 || !segs[2].endsWith(".png") ||
            segs.any { s -> val n = s.removeSuffix(".png"); n.isEmpty() || !n.all { it.isDigit() } }
        ) {
            return tileUnavailable(400, "Bad tile path")
        }
        val path = "${segs[0]}/${segs[1]}/${segs[2]}"
        val cacheFile = File(cacheDir, "tiles/$path")
        // Tuile en cache, même ancienne : servie TOUT DE SUITE (jamais de
        // réseau bloquant quand on a déjà une image — sur un WiFi sans
        // internet, chaque tuile aurait sinon attendu les délais réseau avant
        // l'affichage). Si elle a plus de 7 jours, rafraîchie en arrière-plan.
        if (cacheFile.exists()) {
            try {
                val bytes = cacheFile.readBytes()
                if (System.currentTimeMillis() - cacheFile.lastModified() > TILE_REFRESH_MS &&
                    tileRefreshInFlight.add(path)
                ) {
                    try {
                        tileRefreshExecutor.execute {
                            try { downloadOsmTile(path, cacheFile) } finally { tileRefreshInFlight.remove(path) }
                        }
                    } catch (e: java.util.concurrent.RejectedExecutionException) {
                        tileRefreshInFlight.remove(path)  // activité en cours de destruction
                    }
                }
                return tileResponse(bytes)
            } catch (e: Exception) { /* fichier illisible : re-téléchargement */ }
        }
        val bytes = downloadOsmTile(path, cacheFile) ?: return tileUnavailable(504, "Tile unavailable")
        return tileResponse(bytes)
    }

    private val tileRefreshInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Rafraîchissements en arrière-plan : un seul thread, file d'attente.
    private val tileRefreshExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // Politique OSM : au plus 2 connexions simultanées vers le serveur de tuiles
    // (les threads de la WebView peuvent en demander bien plus d'un coup).
    private val tileDownloadSlots = java.util.concurrent.Semaphore(2)

    /** Télécharge une tuile OSM et l'enregistre en cache ; null si indisponible. */
    private fun downloadOsmTile(path: String, cacheFile: File): ByteArray? {
        try {
            if (!tileDownloadSlots.tryAcquire(15, java.util.concurrent.TimeUnit.SECONDS)) return null
        } catch (e: InterruptedException) {
            return null
        }
        try {
            return downloadOsmTileLocked(path, cacheFile)
        } finally {
            tileDownloadSlots.release()
        }
    }

    private fun downloadOsmTileLocked(path: String, cacheFile: File): ByteArray? {
        try {
            val conn = java.net.URL("https://$OSM_TILE_HOST/$path")
                .openConnection() as java.net.HttpURLConnection
            try {
                conn.connectTimeout = 6_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", tileUserAgent)
                if (conn.responseCode != 200) {
                    Log.w(TAG, "tuile OSM HTTP ${conn.responseCode} pour $path")
                    return null
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                try {
                    cacheFile.parentFile?.mkdirs()
                    val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(cacheFile)) {
                        tmp.delete()
                        Log.w(TAG, "cache tuile : renommage impossible pour $path")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "cache tuile: ${e.message}")
                }
                return bytes
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "tuile OSM indisponible (${e.message})")
            return null
        }
    }

    // Borne le cache de tuiles : supprime les tuiles de plus de 30 jours, puis
    // les plus anciennes tant que le total dépasse 60 Mo.
    private fun trimTileCache() {
        thread {
            try {
                val root = File(cacheDir, "tiles")
                if (!root.exists()) return@thread
                val now = System.currentTimeMillis()
                val cutoff = now - TILE_CACHE_MAX_AGE_MS
                val files = root.walkTopDown().filter { it.isFile }.toMutableList()
                // .tmp : seulement les orphelins (> 10 min) — un .tmp récent est
                // une tuile en cours d'écriture par fetchOsmTile au même moment.
                files.filter {
                    it.lastModified() < cutoff ||
                        (it.name.endsWith(".tmp") && now - it.lastModified() > 10 * 60_000L)
                }.forEach {
                    it.delete()
                    files.remove(it)
                }
                var total = files.sumOf { it.length() }
                if (total > TILE_CACHE_MAX_BYTES) {
                    for (f in files.sortedBy { it.lastModified() }) {
                        if (total <= TILE_CACHE_MAX_BYTES) break
                        total -= f.length()
                        f.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "trimTileCache: ${e.message}")
            }
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================
    override fun onResume() {
        super.onResume()
        engine.onUiResumed()
        // Relais en arrière-plan activé mais service non démarré (ex. démarrage
        // refusé par Android en arrière-plan) : l'écran au premier plan le relance.
        MarselForegroundService.sync(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MarselForegroundService.ACTION_END_ALERT) {
            pendingEndAlert = true
            webView.evaluateJavascript(
                "window.marselCheckEndAlertRequest && window.marselCheckEndAlertRequest()", null
            )
        }
    }

    override fun onPause() {
        super.onPause()
        // Le moteur garde son récepteur WiFi P2P si une alerte ou le relais
        // en arrière-plan tourne ; le serveur relais reste toujours actif.
        engine.onUiPaused()
    }

    override fun onDestroy() {
        super.onDestroy()
        tileRefreshExecutor.shutdownNow()
        // Sans alerte en cours ni relais en arrière-plan, le moteur s'arrête
        // comme avant (serveurs, GPS, flash, enregistrement finalisé) ; sinon
        // il continue de tourner dans le ForegroundService.
        engine.detachUi(webView)
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
    // Feedback bêta
    // =========================================================================
    // 1) Envoi DIRECT (postFeedback) vers le script Google Apps Script d'ONA,
    //    qui transmet formulaire + journaux par Gmail — aucun serveur Marsel.
    // 2) Repli (sendFeedbackEmailInternal) : brouillon complet dans l'appli
    //    mail du testeur, qui garde la main pour l'envoi final.
    /**
     * POST JSON vers le script Apps Script d'ONA. Retourne (succès, détail).
     * Le script répond {"ok":true} ; Google répond d'abord par une redirection
     * 302 vers script.googleusercontent.com, suivie automatiquement (le POST
     * est déjà traité par le script à ce stade).
     */
    private fun postFeedback(
        endpoint: String, token: String,
        nom: String, prenom: String, email: String, avis: String, logText: String
    ): Pair<Boolean, String> {
        if (!endpoint.startsWith("https://")) return false to "endpoint non https"
        return try {
            // Même date (fuseau du téléphone) que l'envoi par appli mail, pour
            // un objet identique « Marsel Feedback_dd-MM-yyyy_Nom ».
            val dateStr = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.FRANCE).format(java.util.Date())
            val body = org.json.JSONObject().apply {
                put("token", token)
                put("date", dateStr)
                put("nom", nom)
                put("prenom", prenom)
                put("email", email)
                put("avis", avis)
                put("logs", logText)
                put("appVersion", appVersionName)
                put("android", Build.VERSION.RELEASE ?: "?")
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            }.toString().toByteArray(Charsets.UTF_8)
            val conn = java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                // Généreux : le script envoie l'e-mail AVANT de répondre ; un
                // délai trop court (démarrage à froid d'Apps Script) ferait
                // conclure à un échec et ouvrir l'appli mail → doublon.
                conn.readTimeout = 60_000
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val text = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) { "" }
                val ok = code in 200..299 && Regex(""""ok"\s*:\s*true""").containsMatchIn(text)
                if (ok) true to "ok" else false to "HTTP $code ${text.take(120)}"
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "postFeedback: ${e.message}")
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun sendFeedbackEmailInternal(nom: String, prenom: String, email: String, avis: String, logText: String) {
        try {
            val dateStr = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.FRANCE).format(java.util.Date())
            val subject = "Marsel Feedback_${dateStr}_$nom"
            val body = buildString {
                append("Nom : ").append(nom).append('\n')
                append("Prénom : ").append(prenom).append('\n')
                append("Email : ").append(email).append("\n\n")
                append("Avis :\n").append(avis)
            }

            // Fichier de logs joint (cf. res/xml/file_paths.xml — seul ce
            // sous-dossier du cache est exposé via FileProvider).
            val feedbackDir = File(cacheDir, "feedback").apply { mkdirs() }
            // QA-FIX : purger les pièces jointes des envois précédents avant
            // d'en écrire une nouvelle — sans ça, chaque feedback envoyé
            // pendant la bêta laissait un fichier orphelin dans le cache,
            // jamais nettoyé.
            feedbackDir.listFiles()?.forEach { it.delete() }
            val logFile = File(feedbackDir, "marsel_logs_${System.currentTimeMillis()}.txt")
            logFile.writeText(logText)
            val logUri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", logFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("ona.action@gmail.com"))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, logUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Envoyer le feedback via..."))
            engine.showNotification("Marsel", "Merci pour votre retour !")
        } catch (e: Exception) {
            Log.e(TAG, "sendFeedbackEmailInternal: ${e.message}")
            webView.evaluateJavascript(
                "window.showToast && window.showToast('Impossible de préparer l\\'email')",
                null
            )
        }
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
        // Import d'un proche depuis les contacts (TODO.md) — Intent.ACTION_PICK,
        // pas de permission READ_CONTACTS.
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun pickContact() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                    contactPickerLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "pickContact launch failed: ${e.message}")
                    webView.evaluateJavascript("window.onContactPicked && window.onContactPicked(null)", null)
                }
            }
        }

        // -----------------------------------------------------------------------
        // Feedback bêta + liens externes (préparation bêta publique)
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun sendFeedbackEmail(nom: String, prenom: String, email: String, avis: String, logText: String) {
            runOnUiThread { sendFeedbackEmailInternal(nom, prenom, email, avis, logText) }
        }

        // Envoi DIRECT du feedback (formulaire + logs) vers le point de
        // réception d'ONA (script Google Apps Script), sans passer par
        // l'appli mail du testeur. Réseau sur un thread de fond ; le verdict
        // revient au JS via window.onFeedbackSubmitted(ok, détail).
        @JavascriptInterface
        fun submitFeedback(
            endpoint: String, token: String,
            nom: String, prenom: String, email: String, avis: String, logText: String
        ) {
            thread {
                val (ok, rawDetail) = postFeedback(endpoint, token, nom, prenom, email, avis, logText)
                // Détail = extrait de réponse serveur / message d'exception : on
                // ne garde que des caractères sûrs, pour que le JS généré ne
                // puisse jamais échouer à l'analyse (sinon le bouton resterait
                // bloqué sur « Envoi… » sans repli vers l'appli mail).
                val detail = rawDetail.replace(Regex("[^\\p{L}\\p{N} .,:;_/()=+-]"), " ").take(160)
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onFeedbackSubmitted && window.onFeedbackSubmitted($ok, ${escapeJs(detail)})",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Log.e(TAG, "openExternalUrl($url) failed: ${e.message}")
                }
            }
        }

        // -----------------------------------------------------------------------
        // Flash, audio, réseau, SMS, réseau Marsel, notifications, GPS :
        // délégués au moteur (MarselEngine), qui peut survivre à l'écran.
        // Noms et signatures inchangés pour le JavaScript.
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun activateFlash() = engine.activateFlash()

        @JavascriptInterface
        fun stopFlash() = engine.stopFlash()

        @JavascriptInterface
        fun startAudioRecord() = engine.startAudioRecord()

        @JavascriptInterface
        fun stopAudioRecord() = engine.stopAudioRecord()

        // Liste des enregistrements pour l'UI (nom, date, durée, taille).
        @JavascriptInterface
        fun getRecordings(): String = engine.getRecordings()

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
        fun sendRecordingMms(phoneNumber: String) = engine.sendRecordingMms(phoneNumber)

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
        fun getNetworkType(): String = engine.getNetworkType()

        // F1 / Section 1 : qualité réseau validée (MOBILE_STABLE / WIFI_STABLE / NONE)
        @JavascriptInterface
        fun detectNetworkQuality(): String = engine.currentNetworkQuality()

        // TEST-FIX : capacité SMS réelle (SIM prête + permission). Un téléphone
        // en WiFi sans SIM doit déléguer ses SMS au réseau Marsel (relaySms=1).
        @JavascriptInterface
        fun canSendSms(): Boolean = engine.canSendSms()

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
        fun sendEmergencySMS(phoneNumber: String, message: String) =
            engine.sendEmergencySms(phoneNumber, message)

        // Envoi SUIVI : l'accusé système revient via window.onSmsSendResult
        // (trackId, ok, code) — en cas d'échec réel, le JS bascule sur le MRN.
        @JavascriptInterface
        fun sendEmergencySMSTracked(phoneNumber: String, message: String, trackId: String) =
            engine.sendEmergencySmsTracked(phoneNumber, message, trackId)

        @JavascriptInterface
        fun hasSMSPermission(): Boolean = engine.hasSmsPermission()

        // -----------------------------------------------------------------------
        // WiFi Direct relay
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun sendEmergencyViaRelay(rawEmergencyJson: String) = engine.sendEmergencyViaRelay(rawEmergencyJson)

        @JavascriptInterface
        fun startP2PDiscovery() = engine.startP2PDiscovery()

        @JavascriptInterface
        fun stopP2PDiscovery() = engine.stopP2PDiscovery()

        @JavascriptInterface
        fun getP2PPeers(): String = engine.getP2PPeersJson()

        @JavascriptInterface
        fun createP2PGroup() = engine.createP2PGroup()

        @JavascriptInterface
        fun removeP2PGroup() = engine.removeP2PGroup()

        // -----------------------------------------------------------------------
        // Notifications
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun showNotification(title: String, body: String) = engine.showNotification(title, body)

        // -----------------------------------------------------------------------
        // Location
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun getLocation(): String = engine.getLocationJson()

        @JavascriptInterface
        fun startLocationUpdates() = engine.startLocationUpdates()

        @JavascriptInterface
        fun stopLocationUpdates() = engine.stopLocationUpdates()

        // -----------------------------------------------------------------------
        // Legacy WiFi Direct send
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun sendWifiMessage(message: String) = engine.sendWifiMessage(message)

        // -----------------------------------------------------------------------
        // ForegroundService — mode A « alerte en cours » (TODO.md)
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun startAlertService(emergencyJson: String) {
            runOnUiThread { engine.startAlertMode(emergencyJson) }
        }

        @JavascriptInterface
        fun stopAlertService() {
            runOnUiThread { engine.stopAlertMode() }
        }

        @JavascriptInterface
        fun isAlertTimeoutHandled(emergencyId: String): Boolean = engine.isAlertTimeoutHandled(emergencyId)

        @JavascriptInterface
        fun noteAlertTimeoutHandled(emergencyId: String) {
            runOnUiThread { engine.noteAlertTimeoutHandled(emergencyId) }
        }

        /** true une seule fois après un appui sur « Terminer l'alerte » dans la notification. */
        @JavascriptInterface
        fun consumeEndAlertRequest(): Boolean {
            val pending = pendingEndAlert
            pendingEndAlert = false
            return pending
        }

        // -----------------------------------------------------------------------
        // ForegroundService — mode B « relais en arrière-plan » (Réglages)
        // -----------------------------------------------------------------------

        @JavascriptInterface
        fun getRelayStatus(): String = MarselForegroundService.statusJson(this@MainActivity)

        @JavascriptInterface
        fun setRelayEnabled(enabled: Boolean) {
            RelaySettings.setEnabled(this@MainActivity, enabled)
            runOnUiThread { MarselForegroundService.sync(this@MainActivity) }
        }

        @JavascriptInterface
        fun setRelayChargingOnly(chargingOnly: Boolean) {
            RelaySettings.setChargingOnly(this@MainActivity, chargingOnly)
            runOnUiThread { MarselForegroundService.sync(this@MainActivity) }
        }
    }

    // =========================================================================
    // WiFi Direct Bridge (legacy — keep existing interface name)
    // =========================================================================
    inner class WifiDirectBridge {
        @JavascriptInterface
        fun sendWifiMessage(message: String) = engine.sendWifiMessage(message)
    }
}
